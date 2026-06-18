#include <jni.h>
#include <android/bitmap.h>
#include <android/imagedecoder.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <linux/videodev2.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {

constexpr const char* kTag = "ahd_v4l2_jni";
constexpr uint32_t kDefaultBufferCount = 4;
constexpr int kPollTimeoutMs = 1500;
constexpr uint32_t kTargetFps = 30;
std::mutex g_last_error_mutex;
std::string g_last_error;

enum PixelFormat : jint {
    PIXEL_FORMAT_MJPEG = 0,
};

struct MappedBuffer {
    void* start = nullptr;
    size_t length = 0;
};

struct CameraContext {
    std::string device_path;
    int fd = -1;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t requested_width = 0;
    uint32_t requested_height = 0;
    uint32_t requested_fps = kTargetFps;
    uint32_t pixel_format = V4L2_PIX_FMT_MJPEG;
    bool configured = false;
    bool streaming = false;
    bool preview_started = false;
    std::vector<MappedBuffer> buffers;
    std::vector<uint8_t> rgba_buffer;
    ANativeWindow* window = nullptr;
    std::thread preview_thread;
    std::atomic<bool> stop_requested{false};
    std::mutex mutex;
};

void LogPrint(int priority, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priority, kTag, fmt, args);
    va_end(args);
}

void SetLastError(const std::string& error) {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    g_last_error = error;
}

void ClearLastError() {
    SetLastError("");
}

std::string ReadLastError() {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    return g_last_error;
}

std::string ErrnoMessage(const std::string& prefix) {
    std::ostringstream stream;
    stream << prefix << ": errno=" << errno << " (" << strerror(errno) << ")";
    return stream.str();
}

jlong ToHandle(CameraContext* context) {
    return reinterpret_cast<jlong>(context);
}

CameraContext* FromHandle(jlong handle) {
    return reinterpret_cast<CameraContext*>(handle);
}

std::string JStringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return "";
    }

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return "";
    }

    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

int IoctlRetry(int fd, unsigned long request, void* arg) {
    int result;
    do {
        result = ioctl(fd, request, arg);
    } while (result == -1 && errno == EINTR);
    return result;
}

uint32_t ResolvePixelFormat(jint pixel_format) {
    switch (pixel_format) {
        case PIXEL_FORMAT_MJPEG:
        default:
            return V4L2_PIX_FMT_MJPEG;
    }
}

void ReleaseWindow(CameraContext* context) {
    if (context->window != nullptr) {
        ANativeWindow_release(context->window);
        context->window = nullptr;
    }
}

void StopStreamingLocked(CameraContext* context) {
    if (context->streaming && context->fd >= 0) {
        enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (IoctlRetry(context->fd, VIDIOC_STREAMOFF, &type) == -1) {
            LogPrint(ANDROID_LOG_WARN, "VIDIOC_STREAMOFF failed for %s: %s",
                    context->device_path.c_str(), strerror(errno));
        }
    }
    context->streaming = false;
    context->preview_started = false;
}

void UnmapBuffers(CameraContext* context) {
    for (const auto& buffer : context->buffers) {
        if (buffer.start != nullptr && buffer.length > 0) {
            munmap(buffer.start, buffer.length);
        }
    }
    context->buffers.clear();
}

void CloseDevice(CameraContext* context) {
    if (context->fd >= 0) {
        close(context->fd);
        context->fd = -1;
    }
}

bool CopyRgbToWindow(const uint8_t* rgba, int width, int height, ANativeWindow* window) {
    if (rgba == nullptr || window == nullptr || width <= 0 || height <= 0) {
        return false;
    }

    if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) != 0) {
        LogPrint(ANDROID_LOG_WARN, "ANativeWindow_setBuffersGeometry failed: %dx%d", width, height);
    }

    ANativeWindow_Buffer buffer{};
    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) {
        LogPrint(ANDROID_LOG_WARN, "ANativeWindow_lock failed");
        return false;
    }

    const int dst_stride = static_cast<int>(buffer.stride) * 4;
    const int src_stride = width * 4;
    auto* dst = static_cast<uint8_t*>(buffer.bits);
    for (int y = 0; y < height; ++y) {
        std::memcpy(dst + y * dst_stride, rgba + y * src_stride, static_cast<size_t>(src_stride));
    }

    ANativeWindow_unlockAndPost(window);
    return true;
}

bool EnsureRgbaBuffer(CameraContext* context, int width, int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }
    const size_t required = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
    if (context->rgba_buffer.size() != required) {
        context->rgba_buffer.resize(required);
    }
    return true;
}

inline uint8_t ClampToByte(int value) {
    return static_cast<uint8_t>(std::clamp(value, 0, 255));
}

bool RenderYuyvFrame(CameraContext* context, const uint8_t* data, size_t length) {
    if (context->window == nullptr || data == nullptr) {
        return false;
    }

    const int width = static_cast<int>(context->width);
    const int height = static_cast<int>(context->height);
    const size_t expected = static_cast<size_t>(width) * static_cast<size_t>(height) * 2;
    if (length < expected) {
        LogPrint(ANDROID_LOG_WARN, "YUYV frame too small: got=%zu expected=%zu", length, expected);
        return false;
    }

    if (!EnsureRgbaBuffer(context, width, height)) {
        return false;
    }
    auto& rgba = context->rgba_buffer;
    size_t src_index = 0;
    size_t dst_index = 0;
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; x += 2) {
            const int y0 = data[src_index + 0];
            const int u = data[src_index + 1] - 128;
            const int y1 = data[src_index + 2];
            const int v = data[src_index + 3] - 128;
            src_index += 4;

            const auto write_pixel = [&](int y_value) {
                const int c = y_value - 16;
                const int d = u;
                const int e = v;
                const int r = (298 * c + 409 * e + 128) >> 8;
                const int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
                const int b = (298 * c + 516 * d + 128) >> 8;
                rgba[dst_index + 0] = ClampToByte(r);
                rgba[dst_index + 1] = ClampToByte(g);
                rgba[dst_index + 2] = ClampToByte(b);
                rgba[dst_index + 3] = 255;
                dst_index += 4;
            };

            write_pixel(y0);
            write_pixel(y1);
        }
    }

    return CopyRgbToWindow(rgba.data(), width, height, context->window);
}

bool RenderMjpegFrame(CameraContext* context, const uint8_t* data, size_t length) {
    if (context->window == nullptr || data == nullptr || length == 0) {
        return false;
    }

    AImageDecoder* decoder = nullptr;
    const int create_result = AImageDecoder_createFromBuffer(data, length, &decoder);
    if (create_result != ANDROID_IMAGE_DECODER_SUCCESS || decoder == nullptr) {
        LogPrint(ANDROID_LOG_WARN, "AImageDecoder_createFromBuffer failed: %d", create_result);
        return false;
    }

    const AImageDecoderHeaderInfo* header = AImageDecoder_getHeaderInfo(decoder);
    const int width = AImageDecoderHeaderInfo_getWidth(header);
    const int height = AImageDecoderHeaderInfo_getHeight(header);
    if (width <= 0 || height <= 0) {
        AImageDecoder_delete(decoder);
        LogPrint(ANDROID_LOG_WARN, "Decoded MJPEG header has invalid size: %dx%d", width, height);
        return false;
    }

    AImageDecoder_setAndroidBitmapFormat(decoder, ANDROID_BITMAP_FORMAT_RGBA_8888);
    AImageDecoder_setUnpremultipliedRequired(decoder, false);

    if (!EnsureRgbaBuffer(context, width, height)) {
        AImageDecoder_delete(decoder);
        return false;
    }
    auto& rgba = context->rgba_buffer;
    const int decode_result = AImageDecoder_decodeImage(
            decoder,
            rgba.data(),
            static_cast<size_t>(width) * 4,
            rgba.size());
    AImageDecoder_delete(decoder);

    if (decode_result != ANDROID_IMAGE_DECODER_SUCCESS) {
        LogPrint(ANDROID_LOG_WARN, "AImageDecoder_decodeImage failed: %d", decode_result);
        return false;
    }

    return CopyRgbToWindow(rgba.data(), width, height, context->window);
}

bool RenderFrame(CameraContext* context, const uint8_t* data, size_t length) {
    switch (context->pixel_format) {
        case V4L2_PIX_FMT_MJPEG:
            return RenderMjpegFrame(context, data, length);
        case V4L2_PIX_FMT_YUYV:
            return RenderYuyvFrame(context, data, length);
        default:
            LogPrint(ANDROID_LOG_WARN, "Unsupported pixel format: %08x", context->pixel_format);
            return false;
    }
}

bool QueueAllBuffers(CameraContext* context) {
    for (size_t index = 0; index < context->buffers.size(); ++index) {
        v4l2_buffer buffer{};
        buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buffer.memory = V4L2_MEMORY_MMAP;
        buffer.index = static_cast<uint32_t>(index);
        if (IoctlRetry(context->fd, VIDIOC_QBUF, &buffer) == -1) {
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QBUF failed for %s buffer %zu: %s",
                    context->device_path.c_str(), index, strerror(errno));
            return false;
        }
    }
    return true;
}

void PreviewLoop(CameraContext* context) {
    {
        std::lock_guard<std::mutex> lock(context->mutex);
        if (!QueueAllBuffers(context)) {
            context->preview_started = false;
            ReleaseWindow(context);
            return;
        }

        enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        if (IoctlRetry(context->fd, VIDIOC_STREAMON, &type) == -1) {
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_STREAMON failed for %s: %s",
                    context->device_path.c_str(), strerror(errno));
            context->preview_started = false;
            ReleaseWindow(context);
            return;
        }

        context->streaming = true;
    }

    LogPrint(ANDROID_LOG_INFO, "Preview loop started: %s %ux%u format=%08x",
            context->device_path.c_str(), context->width, context->height, context->pixel_format);

    while (!context->stop_requested.load()) {
        pollfd poll_fd{};
        poll_fd.fd = context->fd;
        poll_fd.events = POLLIN;

        const int poll_result = poll(&poll_fd, 1, kPollTimeoutMs);
        if (poll_result == 0) {
            continue;
        }
        if (poll_result < 0) {
            if (errno == EINTR) {
                continue;
            }
            LogPrint(ANDROID_LOG_ERROR, "poll failed for %s: %s",
                    context->device_path.c_str(), strerror(errno));
            break;
        }

        v4l2_buffer buffer{};
        buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buffer.memory = V4L2_MEMORY_MMAP;
        if (IoctlRetry(context->fd, VIDIOC_DQBUF, &buffer) == -1) {
            if (errno == EAGAIN) {
                continue;
            }
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_DQBUF failed for %s: %s",
                    context->device_path.c_str(), strerror(errno));
            break;
        }

        if (buffer.index < context->buffers.size()) {
            const auto& mapped = context->buffers[buffer.index];
            RenderFrame(context,
                    static_cast<const uint8_t*>(mapped.start),
                    std::min(static_cast<size_t>(buffer.bytesused), mapped.length));
        }

        if (IoctlRetry(context->fd, VIDIOC_QBUF, &buffer) == -1) {
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QBUF recycle failed for %s: %s",
                    context->device_path.c_str(), strerror(errno));
            break;
        }
    }

    std::lock_guard<std::mutex> lock(context->mutex);
    StopStreamingLocked(context);
    ReleaseWindow(context);
    LogPrint(ANDROID_LOG_INFO, "Preview loop stopped: %s", context->device_path.c_str());
}

bool ConfigureDevice(CameraContext* context) {
    v4l2_capability capability{};
    if (IoctlRetry(context->fd, VIDIOC_QUERYCAP, &capability) == -1) {
        SetLastError(ErrnoMessage("VIDIOC_QUERYCAP failed for " + context->device_path));
        LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QUERYCAP failed for %s: %s",
                context->device_path.c_str(), strerror(errno));
        return false;
    }

    if ((capability.capabilities & V4L2_CAP_VIDEO_CAPTURE) == 0) {
        SetLastError("Device is not a V4L2 capture node: " + context->device_path);
        LogPrint(ANDROID_LOG_ERROR, "Device is not a capture node: %s", context->device_path.c_str());
        return false;
    }

    if ((capability.capabilities & V4L2_CAP_STREAMING) == 0) {
        SetLastError("Device does not support V4L2 streaming IO: " + context->device_path);
        LogPrint(ANDROID_LOG_ERROR, "Device does not support streaming IO: %s", context->device_path.c_str());
        return false;
    }

    v4l2_format format{};
    format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    format.fmt.pix.width = context->requested_width;
    format.fmt.pix.height = context->requested_height;
    format.fmt.pix.pixelformat = context->pixel_format;
    format.fmt.pix.field = V4L2_FIELD_ANY;

    if (IoctlRetry(context->fd, VIDIOC_S_FMT, &format) == -1) {
        LogPrint(ANDROID_LOG_WARN, "VIDIOC_S_FMT failed for requested format %08x on %s: %s",
                context->pixel_format, context->device_path.c_str(), strerror(errno));
        format.fmt.pix.pixelformat = V4L2_PIX_FMT_YUYV;
        if (IoctlRetry(context->fd, VIDIOC_S_FMT, &format) == -1) {
            SetLastError(ErrnoMessage("VIDIOC_S_FMT failed for " + context->device_path));
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_S_FMT fallback YUYV failed on %s: %s",
                    context->device_path.c_str(), strerror(errno));
            return false;
        }
    }

    context->width = format.fmt.pix.width;
    context->height = format.fmt.pix.height;
    context->pixel_format = format.fmt.pix.pixelformat;

    v4l2_streamparm streamparm{};
    streamparm.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    streamparm.parm.capture.timeperframe.numerator = 1;
    streamparm.parm.capture.timeperframe.denominator = context->requested_fps;
    if (IoctlRetry(context->fd, VIDIOC_S_PARM, &streamparm) == -1) {
        LogPrint(ANDROID_LOG_WARN, "VIDIOC_S_PARM failed for %s: %s",
                context->device_path.c_str(), strerror(errno));
    }

    v4l2_requestbuffers request{};
    request.count = kDefaultBufferCount;
    request.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    request.memory = V4L2_MEMORY_MMAP;
    if (IoctlRetry(context->fd, VIDIOC_REQBUFS, &request) == -1) {
        SetLastError(ErrnoMessage("VIDIOC_REQBUFS failed for " + context->device_path));
        LogPrint(ANDROID_LOG_ERROR, "VIDIOC_REQBUFS failed for %s: %s",
                context->device_path.c_str(), strerror(errno));
        return false;
    }
    if (request.count < 2) {
        SetLastError("VIDIOC_REQBUFS returned too few buffers for " + context->device_path);
        LogPrint(ANDROID_LOG_ERROR, "Insufficient V4L2 buffers for %s: %u",
                context->device_path.c_str(), request.count);
        return false;
    }

    context->buffers.reserve(request.count);
    for (uint32_t index = 0; index < request.count; ++index) {
        v4l2_buffer buffer{};
        buffer.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buffer.memory = V4L2_MEMORY_MMAP;
        buffer.index = index;
        if (IoctlRetry(context->fd, VIDIOC_QUERYBUF, &buffer) == -1) {
            SetLastError(ErrnoMessage("VIDIOC_QUERYBUF failed for " + context->device_path));
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QUERYBUF failed for %s buffer %u: %s",
                    context->device_path.c_str(), index, strerror(errno));
            return false;
        }

        void* start = mmap(nullptr, buffer.length, PROT_READ | PROT_WRITE, MAP_SHARED,
                context->fd, static_cast<off_t>(buffer.m.offset));
        if (start == MAP_FAILED) {
            SetLastError(ErrnoMessage("mmap failed for " + context->device_path));
            LogPrint(ANDROID_LOG_ERROR, "mmap failed for %s buffer %u: %s",
                    context->device_path.c_str(), index, strerror(errno));
            return false;
        }

        context->buffers.push_back({start, buffer.length});
    }

    context->configured = true;
    LogPrint(ANDROID_LOG_INFO, "Configured %s: %ux%u format=%08x buffers=%zu",
            context->device_path.c_str(), context->width, context->height,
            context->pixel_format, context->buffers.size());
    return true;
}

void CleanupContext(CameraContext* context) {
    if (context == nullptr) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(context->mutex);
        StopStreamingLocked(context);
        ReleaseWindow(context);
    }

    UnmapBuffers(context);
    CloseDevice(context);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeOpen(
        JNIEnv* env,
        jclass /* clazz */,
        jstring devicePath,
        jint width,
        jint height,
        jint pixelFormat) {
    auto context = std::make_unique<CameraContext>();
    ClearLastError();
    context->device_path = JStringToUtf8(env, devicePath);
    context->requested_width = static_cast<uint32_t>(std::max(width, 1));
    context->requested_height = static_cast<uint32_t>(std::max(height, 1));
    context->pixel_format = ResolvePixelFormat(pixelFormat);

    context->fd = open(context->device_path.c_str(), O_RDWR | O_NONBLOCK);
    if (context->fd < 0) {
        SetLastError(ErrnoMessage("open failed for " + context->device_path));
        LogPrint(ANDROID_LOG_ERROR, "open failed for %s: %s",
                context->device_path.c_str(), strerror(errno));
        return 0;
    }

    if (!ConfigureDevice(context.get())) {
        CleanupContext(context.get());
        return 0;
    }

    return ToHandle(context.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeStartPreview(
        JNIEnv* env,
        jclass /* clazz */,
        jlong handle,
        jobject surface) {
    CameraContext* context = FromHandle(handle);
    if (context == nullptr) {
        LogPrint(ANDROID_LOG_ERROR, "nativeStartPreview failed: null handle");
        return -1;
    }
    if (surface == nullptr) {
        LogPrint(ANDROID_LOG_ERROR, "nativeStartPreview failed: null surface");
        return -1;
    }

    std::lock_guard<std::mutex> lock(context->mutex);
    if (!context->configured || context->fd < 0) {
        LogPrint(ANDROID_LOG_ERROR, "nativeStartPreview failed: device not configured");
        return -1;
    }
    if (context->preview_started) {
        LogPrint(ANDROID_LOG_DEBUG, "nativeStartPreview skipped: preview already running for %s",
                context->device_path.c_str());
        return 0;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LogPrint(ANDROID_LOG_ERROR, "ANativeWindow_fromSurface returned null for %s",
                context->device_path.c_str());
        return -1;
    }

    if (context->preview_thread.joinable()) {
        context->preview_thread.join();
    }

    context->stop_requested.store(false);
    context->window = window;
    context->preview_started = true;
    context->preview_thread = std::thread(PreviewLoop, context);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeStopPreview(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong handle) {
    CameraContext* context = FromHandle(handle);
    if (context == nullptr) {
        return;
    }

    context->stop_requested.store(true);
    if (context->preview_thread.joinable()) {
        context->preview_thread.join();
    }

    std::lock_guard<std::mutex> lock(context->mutex);
    StopStreamingLocked(context);
    ReleaseWindow(context);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeClose(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong handle) {
    CameraContext* context = FromHandle(handle);
    if (context == nullptr) {
        return;
    }

    context->stop_requested.store(true);
    if (context->preview_thread.joinable()) {
        context->preview_thread.join();
    }

    CleanupContext(context);
    delete context;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeGetLastError(
        JNIEnv* env,
        jclass /* clazz */) {
    const std::string error = ReadLastError();
    return env->NewStringUTF(error.c_str());
}
