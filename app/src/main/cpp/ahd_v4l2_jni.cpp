#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <linux/videodev2.h>

#include "im2d.h"
#include "rga.h"

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <limits>
#include <sstream>
#include <string>
#include <thread>
#include <chrono>
#include <vector>

#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <termios.h>
#include <unistd.h>

#ifndef BASE_VIDIOC_PRIVATE
#define BASE_VIDIOC_PRIVATE 192
#endif

namespace {

constexpr const char* kTag = "ahd_v4l2_jni";
constexpr uint32_t kDefaultBufferCount = 4;
constexpr int kPollTimeoutMs = 1500;
constexpr uint32_t kTargetFps = 30;
constexpr int kRemoteWidth = 640;
constexpr int kRemoteHeight = 360;
std::atomic<void*> gRemoteFrameSource{nullptr};
std::atomic<bool> gRemoteFramesEnabled{false};
JavaVM* gRemoteVm = nullptr;
jclass gRemoteNativeClass = nullptr;
jmethodID gRemoteFrameMethod = nullptr;

enum PixelFormat : jint {
    PIXEL_FORMAT_NV12 = 1,
};

// Keep this ABI definition local because the Android NDK does not ship the
// Rockchip-specific rk-camera-module.h header used by the board kernel.
struct RkmoduleVcHotplugInfo {
    uint8_t detect_status;
} __attribute__((packed));

constexpr unsigned long kRkmoduleGetVcHotplugInfo =
        _IOR('V', BASE_VIDIOC_PRIVATE + 13, RkmoduleVcHotplugInfo);

constexpr int kRgaFormatRgba8888 = 0x0 << 8;
constexpr int kRgaFormatNv16 = 0x8 << 8;
constexpr int kRgaFormatNv12 = 0xa << 8;
constexpr int kRgaFormatNv61 = 0xc << 8;
constexpr int kRgaFormatNv21 = 0xe << 8;
constexpr int kRgaFormatYvyu422 = 0x18 << 8;
constexpr int kRgaFormatVyuy422 = 0x1a << 8;
constexpr int kRgaFormatYuyv422 = 0x1c << 8;
constexpr int kRgaFormatUyvy422 = 0x1e << 8;

std::mutex gLastErrorMutex;
std::string gLastError;
std::mutex gMotorMutex;
int gMotorFd = -1;
uint8_t gMotorSequence = 0;
// Keep fragmented counter reports across short reads. Guarded by gMotorMutex.
std::vector<uint8_t> gCounterReceived;

struct MappedBuffer {
    void* start = nullptr;
    size_t length = 0;
    int dmaFd = -1;
};

struct CameraContext {
    std::string devicePath;
    int fd = -1;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t requestedWidth = 0;
    uint32_t requestedHeight = 0;
    uint32_t requestedFps = kTargetFps;
    uint32_t pixelFormat = V4L2_PIX_FMT_NV12;
    uint32_t bytesPerLine = 0;
    uint32_t sizeImage = 0;
    v4l2_buf_type bufferType = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    uint32_t planeCount = 1;
    bool configured = false;
    bool streaming = false;
    bool previewStarted = false;
    std::vector<MappedBuffer> buffers;
    std::vector<uint8_t> rgbaBuffer;
    std::vector<uint8_t> remoteNv12Buffer;
    uint32_t remotePublishedFrames = 0;
    uint32_t remoteResizeFailures = 0;
    bool remoteFormatWarningLogged = false;
    ANativeWindow* window = nullptr;
    std::thread previewThread;
    std::atomic<bool> stopRequested{false};
    uint32_t renderedFrameCount = 0;
    std::mutex mutex;
};

void LogPrint(int priority, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(priority, kTag, fmt, args);
    va_end(args);
}

void SetLastError(const std::string& error) {
    std::lock_guard<std::mutex> lock(gLastErrorMutex);
    gLastError = error;
}

void ClearLastError() {
    SetLastError("");
}

std::string ReadLastError() {
    std::lock_guard<std::mutex> lock(gLastErrorMutex);
    return gLastError;
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

uint32_t ResolvePixelFormat(jint pixelFormat) {
    switch (pixelFormat) {
        case PIXEL_FORMAT_NV12:
        default:
            return V4L2_PIX_FMT_NV12;
    }
}

int ResolveRgaFormat(uint32_t pixelFormat) {
    switch (pixelFormat) {
        case V4L2_PIX_FMT_NV16:
            return kRgaFormatNv16;
        case V4L2_PIX_FMT_NV61:
            return kRgaFormatNv61;
        case V4L2_PIX_FMT_NV12:
            return kRgaFormatNv12;
        case V4L2_PIX_FMT_NV21:
            return kRgaFormatNv21;
        case V4L2_PIX_FMT_YUYV:
            return kRgaFormatYuyv422;
        case V4L2_PIX_FMT_YVYU:
            return kRgaFormatYvyu422;
        case V4L2_PIX_FMT_UYVY:
            return kRgaFormatUyvy422;
        case V4L2_PIX_FMT_VYUY:
            return kRgaFormatVyuy422;
        default:
            return -1;
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
        enum v4l2_buf_type type = context->bufferType;
        if (IoctlRetry(context->fd, VIDIOC_STREAMOFF, &type) == -1) {
            LogPrint(ANDROID_LOG_WARN, "VIDIOC_STREAMOFF failed for %s: %s",
                    context->devicePath.c_str(), strerror(errno));
        }
    }
    context->streaming = false;
    context->previewStarted = false;
}

void UnmapBuffers(CameraContext* context) {
    for (const auto& buffer : context->buffers) {
        if (buffer.start != nullptr && buffer.length > 0) {
            munmap(buffer.start, buffer.length);
        }
        if (buffer.dmaFd >= 0) {
            close(buffer.dmaFd);
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

void CloseMotorLocked() {
    gCounterReceived.clear();
    if (gMotorFd >= 0) {
        close(gMotorFd);
        gMotorFd = -1;
    }
}

bool ConfigureMotorSerial(int fd) {
    termios settings{};
    if (tcgetattr(fd, &settings) == -1) {
        SetLastError(ErrnoMessage("tcgetattr failed for /dev/ttyS3"));
        return false;
    }
    cfmakeraw(&settings);
    if (cfsetispeed(&settings, B9600) == -1 || cfsetospeed(&settings, B9600) == -1) {
        SetLastError(ErrnoMessage("Unable to set /dev/ttyS3 to 9600 baud"));
        return false;
    }
    settings.c_cflag |= static_cast<tcflag_t>(CLOCAL | CREAD);
    settings.c_cflag &= static_cast<tcflag_t>(~(PARENB | CSTOPB | CSIZE));
    settings.c_cflag |= CS8;
    // Android/toybox 的 raw 设置在部分系统上仍可能保留 ICRNL；必须显式关闭，
    // 否则计米器帧尾的 0x0D 会在接收时被翻译成 0x0A，校验帧尾失败。
    settings.c_iflag &= static_cast<tcflag_t>(~(ICRNL | INLCR | IGNCR | IXON | IXOFF));
    settings.c_cc[VMIN] = 0;
    settings.c_cc[VTIME] = 0;
    if (tcsetattr(fd, TCSANOW, &settings) == -1) {
        SetLastError(ErrnoMessage("tcsetattr failed for /dev/ttyS3"));
        return false;
    }
    return true;
}

bool SendSerialCommandLocked(uint8_t deviceId, uint8_t command, uint8_t level) {
    if (gMotorFd < 0) {
        SetLastError("Motor serial port is not open");
        return false;
    }
    // 0x05/0x06 是 VCM 档位，0x0e 是全部补光灯开启；这些命令帧需要携带 1 字节数据。
    const bool hasLevel = deviceId == 0x01
            && (command == 0x05 || command == 0x06 || command == 0x0e)

            ;
    const uint8_t length = hasLevel ? 1 : 0;
    const uint8_t sequence = gMotorSequence++;
    uint8_t checksum = static_cast<uint8_t>(deviceId ^ sequence ^ command ^ length);
    if (hasLevel) {
        checksum ^= level;
    }

    // 帧格式：55 AA DEV_ID SEQ CMD LEN [DATA] CHECKSUM 0D 0A。
    uint8_t frame[10] = {0x55, 0xaa, deviceId, sequence, command, length, level, checksum, 0x0d, 0x0a};
    const size_t frameLength = hasLevel ? 10 : 9;
    if (!hasLevel) {
        frame[6] = checksum;
        frame[7] = 0x0d;
        frame[8] = 0x0a;
    }

    size_t sent = 0;
    while (sent < frameLength) {
        const ssize_t result = write(gMotorFd, frame + sent, frameLength - sent);
        if (result > 0) {
            sent += static_cast<size_t>(result);
            continue;
        }
        if (result < 0 && errno == EINTR) {
            continue;
        }
        SetLastError(ErrnoMessage("write failed for /dev/ttyS3"));
        return false;
    }
    LogPrint(ANDROID_LOG_DEBUG, "RS485 command sent: device=%02x seq=%u command=%02x level=%u",
            deviceId, sequence, command, level);
    return true;
}

bool ReadCounterValueLocked(int32_t* counterValue) {
    if (gMotorFd < 0 || counterValue == nullptr) {
        SetLastError("Counter serial port is not open");
        return false;
    }

    auto& received = gCounterReceived;
    // Do not hold the shared serial lock for hundreds of milliseconds: motor
    // start/stop commands must be able to reach the MCU promptly.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(25);
    while (std::chrono::steady_clock::now() < deadline) {
        const auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
                deadline - std::chrono::steady_clock::now()).count();
        pollfd pollFd{gMotorFd, POLLIN, 0};
        const int pollResult = poll(&pollFd, 1, static_cast<int>(std::max<int64_t>(1, remaining)));
        if (pollResult == 0) {
            break;
        }
        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            SetLastError(ErrnoMessage("poll failed for /dev/ttyS3"));
            return false;
        }

        uint8_t buffer[64];
        const ssize_t bytesRead = read(gMotorFd, buffer, sizeof(buffer));
        if (bytesRead < 0) {
            if (errno == EINTR || errno == EAGAIN) {
                continue;
            }
            SetLastError(ErrnoMessage("read failed for /dev/ttyS3"));
            return false;
        }
        if (bytesRead == 0) {
            continue;
        }
        received.insert(received.end(), buffer, buffer + bytesRead);

        while (received.size() >= 9) {
            if (received[0] != 0x55 || received[1] != 0xaa) {
                received.erase(received.begin());
                continue;
            }
            const size_t frameLength = 9 + received[5];
            if (received[5] > 32) {
                received.erase(received.begin());
                continue;
            }
            if (received.size() < frameLength) {
                break;
            }
            if (received[frameLength - 2] != 0x0d || received[frameLength - 1] != 0x0a) {
                received.erase(received.begin());
                continue;
            }
            uint8_t checksum = 0;
            for (size_t index = 2; index < frameLength - 3; ++index) {
                checksum ^= received[index];
            }
            if (checksum != received[frameLength - 3]) {
                received.erase(received.begin());
                continue;
            }

            const uint8_t deviceId = received[2];
            const uint8_t command = received[4];
            const uint8_t length = received[5];
            const bool activeReport = deviceId == 0x02 && command == 0x01 && length == 4;
            if (activeReport) {
                const size_t valueIndex = 6;
                const uint32_t unsignedValue = (static_cast<uint32_t>(received[valueIndex]) << 24)
                        | (static_cast<uint32_t>(received[valueIndex + 1]) << 16)
                        | (static_cast<uint32_t>(received[valueIndex + 2]) << 8)
                        | static_cast<uint32_t>(received[valueIndex + 3]);
                *counterValue = static_cast<int32_t>(unsignedValue);
                return true;
            }
            received.erase(received.begin(), received.begin() + frameLength);
        }
    }
    SetLastError("Timed out waiting for DnkCounter response");
    return false;
}

bool EnsureRgbaBuffer(CameraContext* context, int width, int height) {
    if (width <= 0 || height <= 0) {
        return false;
    }
    const size_t required = static_cast<size_t>(width) * static_cast<size_t>(height) * 4;
    if (context->rgbaBuffer.size() != required) {
        context->rgbaBuffer.resize(required);
    }
    return true;
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

    const int dstStride = static_cast<int>(buffer.stride) * 4;
    const int srcStride = width * 4;
    auto* dst = static_cast<uint8_t*>(buffer.bits);
    for (int y = 0; y < height; ++y) {
        std::memcpy(dst + y * dstStride, rgba + y * srcStride, static_cast<size_t>(srcStride));
    }

    ANativeWindow_unlockAndPost(window);
    return true;
}

bool RenderFrame(CameraContext* context, const MappedBuffer& mapped, size_t bytesUsed) {
    if (context->window == nullptr || mapped.dmaFd < 0) {
        LogPrint(ANDROID_LOG_WARN, "RenderFrame skipped: window=%p dmaFd=%d",
                context->window, mapped.dmaFd);
        return false;
    }

    const int width = static_cast<int>(context->width);
    const int height = static_cast<int>(context->height);
    const int srcStride = static_cast<int>(context->bytesPerLine == 0 ? context->width : context->bytesPerLine);
    const size_t expected = context->sizeImage != 0 ? static_cast<size_t>(context->sizeImage) : mapped.length;
    if (bytesUsed < expected) {
        LogPrint(ANDROID_LOG_WARN,
                "Frame too small: format=%08x got=%zu expected=%zu stride=%d sizeImage=%u",
                context->pixelFormat, bytesUsed, expected, srcStride, context->sizeImage);
        return false;
    }

    const int srcFormat = ResolveRgaFormat(context->pixelFormat);
    if (srcFormat < 0) {
        LogPrint(ANDROID_LOG_WARN, "Unsupported preview pixel format: %08x", context->pixelFormat);
        return false;
    }

    if (!EnsureRgbaBuffer(context, width, height)) {
        return false;
    }

    rga_buffer_t src = wrapbuffer_fd_t(mapped.dmaFd, width, height, srcStride, height, srcFormat);
    rga_buffer_t dst = wrapbuffer_virtualaddr_t(
            context->rgbaBuffer.data(),
            width,
            height,
            width,
            height,
            kRgaFormatRgba8888);

    const IM_STATUS status = imcvtcolor(src, dst, srcFormat, kRgaFormatRgba8888);
    if (status != IM_STATUS_SUCCESS) {
        LogPrint(ANDROID_LOG_ERROR,
                "RGA imcvtcolor failed: fd=%d format=%08x stride=%d sizeImage=%u status=%d msg=%s",
                mapped.dmaFd, context->pixelFormat, srcStride, context->sizeImage, status, imStrError(status));
        return false;
    }

    return CopyRgbToWindow(context->rgbaBuffer.data(), width, height, context->window);
}

void PublishRemoteFrame(CameraContext* context, const MappedBuffer& mapped, size_t bytesUsed) {
    if (!gRemoteFramesEnabled.load() || gRemoteFrameSource.load() != context
            || mapped.dmaFd < 0
            || gRemoteVm == nullptr || gRemoteNativeClass == nullptr || gRemoteFrameMethod == nullptr) {
        return;
    }
    if (context->pixelFormat != V4L2_PIX_FMT_NV12) {
        if (!context->remoteFormatWarningLogged) {
            LogPrint(ANDROID_LOG_WARN, "Remote direct frames need NV12; actual format=%08x for %s",
                    context->pixelFormat, context->devicePath.c_str());
            context->remoteFormatWarningLogged = true;
        }
        return;
    }
    if (bytesUsed < static_cast<size_t>(context->sizeImage)) return;
    const size_t frameSize = kRemoteWidth * kRemoteHeight * 3 / 2;
    if (context->remoteNv12Buffer.size() != frameSize) {
        context->remoteNv12Buffer.resize(frameSize);
    }
    const int stride = context->bytesPerLine == 0 ? static_cast<int>(context->width)
            : static_cast<int>(context->bytesPerLine);
    rga_buffer_t src = wrapbuffer_fd_t(mapped.dmaFd, context->width, context->height,
            stride, context->height, kRgaFormatNv12);
    rga_buffer_t dst = wrapbuffer_virtualaddr_t(context->remoteNv12Buffer.data(),
            kRemoteWidth, kRemoteHeight, kRemoteWidth, kRemoteHeight, kRgaFormatNv12);
    const IM_STATUS resizeStatus = imresize(src, dst);
    if (resizeStatus != IM_STATUS_SUCCESS) {
        if (++context->remoteResizeFailures == 1 || context->remoteResizeFailures % 100 == 0) {
            LogPrint(ANDROID_LOG_WARN, "Remote NV12 resize failed: status=%d msg=%s count=%u",
                    resizeStatus, imStrError(resizeStatus), context->remoteResizeFailures);
        }
        return;
    }

    JNIEnv* env = nullptr;
    if (gRemoteVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    jbyteArray bytes = env->NewByteArray(frameSize);
    if (bytes != nullptr) {
        env->SetByteArrayRegion(bytes, 0, frameSize,
                reinterpret_cast<const jbyte*>(context->remoteNv12Buffer.data()));
        env->CallStaticVoidMethod(gRemoteNativeClass, gRemoteFrameMethod, bytes);
        env->DeleteLocalRef(bytes);
        if (++context->remotePublishedFrames == 1 || context->remotePublishedFrames % 150 == 0) {
            LogPrint(ANDROID_LOG_INFO, "Remote raw frames published=%u source=%s",
                    context->remotePublishedFrames, context->devicePath.c_str());
        }
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    gRemoteVm->DetachCurrentThread();
}

bool QueueAllBuffers(CameraContext* context) {
    for (size_t index = 0; index < context->buffers.size(); ++index) {
        v4l2_buffer buffer{};
        buffer.type = context->bufferType;
        buffer.memory = V4L2_MEMORY_MMAP;
        buffer.index = static_cast<uint32_t>(index);
        v4l2_plane planes[VIDEO_MAX_PLANES]{};
        if (context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
            buffer.length = context->planeCount;
            buffer.m.planes = planes;
        }
        if (IoctlRetry(context->fd, VIDIOC_QBUF, &buffer) == -1) {
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QBUF failed for %s buffer %zu: %s",
                    context->devicePath.c_str(), index, strerror(errno));
            return false;
        }
    }
    return true;
}

void PreviewLoop(CameraContext* context) {
    using Clock = std::chrono::steady_clock;
    auto fpsWindowStart = Clock::now();
    uint32_t fpsWindowFrames = 0;
    int consecutivePollTimeouts = 0;

    {
        std::lock_guard<std::mutex> lock(context->mutex);
        if (!QueueAllBuffers(context)) {
            context->previewStarted = false;
            ReleaseWindow(context);
            return;
        }

        enum v4l2_buf_type type = context->bufferType;
        if (IoctlRetry(context->fd, VIDIOC_STREAMON, &type) == -1) {
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_STREAMON failed for %s: %s",
                    context->devicePath.c_str(), strerror(errno));
            context->previewStarted = false;
            ReleaseWindow(context);
            return;
        }
        context->streaming = true;
    }

    LogPrint(ANDROID_LOG_INFO, "Preview loop started: %s %ux%u format=%08x",
            context->devicePath.c_str(), context->width, context->height, context->pixelFormat);

    while (!context->stopRequested.load()) {
        pollfd pollFd{};
        pollFd.fd = context->fd;
        pollFd.events = POLLIN;

        const int pollResult = poll(&pollFd, 1, kPollTimeoutMs);
        if (pollResult == 0) {
            consecutivePollTimeouts++;
            LogPrint(ANDROID_LOG_WARN, "Preview poll timeout for %s (%d/2)",
                    context->devicePath.c_str(), consecutivePollTimeouts);
            // 热拔出后设备节点可能仍存在。连续约3秒没有帧时结束本路预览，
            // 由 Java 侧的拓扑监控切换布局并等待重新连接。
            if (consecutivePollTimeouts >= 2) {
                LogPrint(ANDROID_LOG_ERROR,
                        "Preview stream disconnected after repeated timeouts: %s",
                        context->devicePath.c_str());
                break;
            }
            continue;
        }
        if (pollResult < 0) {
            if (errno == EINTR) {
                continue;
            }
            LogPrint(ANDROID_LOG_ERROR, "poll failed for %s: %s",
                    context->devicePath.c_str(), strerror(errno));
            break;
        }
        consecutivePollTimeouts = 0;

        v4l2_buffer buffer{};
        buffer.type = context->bufferType;
        buffer.memory = V4L2_MEMORY_MMAP;
        v4l2_plane planes[VIDEO_MAX_PLANES]{};
        if (context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
            buffer.length = context->planeCount;
            buffer.m.planes = planes;
        }
        if (IoctlRetry(context->fd, VIDIOC_DQBUF, &buffer) == -1) {
            if (errno == EAGAIN) {
                continue;
            }
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_DQBUF failed for %s: %s",
                    context->devicePath.c_str(), strerror(errno));
            break;
        }

        if (buffer.index < context->buffers.size()) {
            const auto& mapped = context->buffers[buffer.index];
            const size_t bytesUsed = context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE
                    ? static_cast<size_t>(buffer.m.planes[0].bytesused)
                    : static_cast<size_t>(buffer.bytesused);
            PublishRemoteFrame(context, mapped, std::min(bytesUsed, mapped.length));
            if (!RenderFrame(context, mapped, std::min(bytesUsed, mapped.length))) {
                LogPrint(ANDROID_LOG_WARN,
                        "RenderFrame failed: index=%u bytesUsed=%zu mapped=%zu format=%08x",
                        buffer.index, bytesUsed, mapped.length, context->pixelFormat);
            } else {
                context->renderedFrameCount++;
                fpsWindowFrames++;
                const auto now = Clock::now();
                const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - fpsWindowStart).count();
                if (elapsedMs >= 1000) {
                    const double fps = fpsWindowFrames * 1000.0 / static_cast<double>(elapsedMs);
                    LogPrint(ANDROID_LOG_INFO,
                            "Preview stats: %s %ux%u format=%08x fps=%.2f rendered=%u",
                            context->devicePath.c_str(),
                            context->width,
                            context->height,
                            context->pixelFormat,
                            fps,
                            context->renderedFrameCount);
                    fpsWindowStart = now;
                    fpsWindowFrames = 0;
                }
            }
        }

        if (IoctlRetry(context->fd, VIDIOC_QBUF, &buffer) == -1) {
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QBUF recycle failed for %s: %s",
                    context->devicePath.c_str(), strerror(errno));
            break;
        }
    }

    std::lock_guard<std::mutex> lock(context->mutex);
    StopStreamingLocked(context);
    ReleaseWindow(context);
    LogPrint(ANDROID_LOG_INFO, "Preview loop stopped: %s", context->devicePath.c_str());
}

bool ConfigureDevice(CameraContext* context) {
    v4l2_capability capability{};
    if (IoctlRetry(context->fd, VIDIOC_QUERYCAP, &capability) == -1) {
        SetLastError(ErrnoMessage("VIDIOC_QUERYCAP failed for " + context->devicePath));
        LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QUERYCAP failed for %s: %s",
                context->devicePath.c_str(), strerror(errno));
        return false;
    }

    const uint32_t capabilityFlags = capability.device_caps != 0
            ? capability.device_caps
            : capability.capabilities;
    if ((capabilityFlags & V4L2_CAP_VIDEO_CAPTURE) != 0) {
        context->bufferType = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    } else if ((capabilityFlags & V4L2_CAP_VIDEO_CAPTURE_MPLANE) != 0) {
        context->bufferType = V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    } else {
        SetLastError("Device is not a V4L2 capture node: " + context->devicePath);
        LogPrint(ANDROID_LOG_ERROR, "Device is not a capture node: %s", context->devicePath.c_str());
        return false;
    }

    if ((capabilityFlags & V4L2_CAP_STREAMING) == 0) {
        SetLastError("Device does not support V4L2 streaming IO: " + context->devicePath);
        LogPrint(ANDROID_LOG_ERROR, "Device does not support streaming IO: %s", context->devicePath.c_str());
        return false;
    }

    v4l2_format format{};
    format.type = context->bufferType;
    if (context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
        format.fmt.pix_mp.width = context->requestedWidth;
        format.fmt.pix_mp.height = context->requestedHeight;
        format.fmt.pix_mp.field = V4L2_FIELD_ANY;
        format.fmt.pix_mp.num_planes = 1;
    } else {
        format.fmt.pix.width = context->requestedWidth;
        format.fmt.pix.height = context->requestedHeight;
        format.fmt.pix.field = V4L2_FIELD_ANY;
    }

    const uint32_t preferredFormats[] = {
            V4L2_PIX_FMT_NV12,
            V4L2_PIX_FMT_NV21,
            V4L2_PIX_FMT_YUYV,
            V4L2_PIX_FMT_YVYU,
            V4L2_PIX_FMT_UYVY,
            V4L2_PIX_FMT_VYUY,
            V4L2_PIX_FMT_NV16,
            V4L2_PIX_FMT_NV61,
    };

    bool formatConfigured = false;
    for (uint32_t candidate : preferredFormats) {
        if (context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
            format.fmt.pix_mp.pixelformat = candidate;
        } else {
            format.fmt.pix.pixelformat = candidate;
        }
        if (IoctlRetry(context->fd, VIDIOC_S_FMT, &format) == 0) {
            formatConfigured = true;
            break;
        }
        LogPrint(ANDROID_LOG_WARN, "VIDIOC_S_FMT failed for candidate format %08x on %s: %s",
                candidate, context->devicePath.c_str(), strerror(errno));
    }
    if (!formatConfigured) {
        SetLastError(ErrnoMessage("VIDIOC_S_FMT failed for " + context->devicePath));
        LogPrint(ANDROID_LOG_ERROR, "No supported preview format on %s", context->devicePath.c_str());
        return false;
    }

    if (context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
        context->width = format.fmt.pix_mp.width;
        context->height = format.fmt.pix_mp.height;
        context->pixelFormat = format.fmt.pix_mp.pixelformat;
        context->bytesPerLine = format.fmt.pix_mp.plane_fmt[0].bytesperline;
        context->sizeImage = format.fmt.pix_mp.plane_fmt[0].sizeimage;
        context->planeCount = std::max<uint32_t>(format.fmt.pix_mp.num_planes, 1U);
    } else {
        context->width = format.fmt.pix.width;
        context->height = format.fmt.pix.height;
        context->pixelFormat = format.fmt.pix.pixelformat;
        context->bytesPerLine = format.fmt.pix.bytesperline;
        context->sizeImage = format.fmt.pix.sizeimage;
        context->planeCount = 1;
    }

    v4l2_streamparm streamparm{};
    streamparm.type = context->bufferType;
    streamparm.parm.capture.timeperframe.numerator = 1;
    streamparm.parm.capture.timeperframe.denominator = context->requestedFps;
    if (IoctlRetry(context->fd, VIDIOC_S_PARM, &streamparm) == -1) {
        LogPrint(ANDROID_LOG_WARN, "VIDIOC_S_PARM failed for %s: %s",
                context->devicePath.c_str(), strerror(errno));
    }

    v4l2_requestbuffers request{};
    request.count = kDefaultBufferCount;
    request.type = context->bufferType;
    request.memory = V4L2_MEMORY_MMAP;
    if (IoctlRetry(context->fd, VIDIOC_REQBUFS, &request) == -1) {
        SetLastError(ErrnoMessage("VIDIOC_REQBUFS failed for " + context->devicePath));
        LogPrint(ANDROID_LOG_ERROR, "VIDIOC_REQBUFS failed for %s: %s",
                context->devicePath.c_str(), strerror(errno));
        return false;
    }
    if (request.count < 2) {
        SetLastError("VIDIOC_REQBUFS returned too few buffers for " + context->devicePath);
        LogPrint(ANDROID_LOG_ERROR, "Insufficient V4L2 buffers for %s: %u",
                context->devicePath.c_str(), request.count);
        return false;
    }

    context->buffers.reserve(request.count);
    for (uint32_t index = 0; index < request.count; ++index) {
        v4l2_buffer buffer{};
        buffer.type = context->bufferType;
        buffer.memory = V4L2_MEMORY_MMAP;
        buffer.index = index;
        v4l2_plane planes[VIDEO_MAX_PLANES]{};
        if (context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
            buffer.length = context->planeCount;
            buffer.m.planes = planes;
        }
        if (IoctlRetry(context->fd, VIDIOC_QUERYBUF, &buffer) == -1) {
            SetLastError(ErrnoMessage("VIDIOC_QUERYBUF failed for " + context->devicePath));
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_QUERYBUF failed for %s buffer %u: %s",
                    context->devicePath.c_str(), index, strerror(errno));
            return false;
        }

        size_t mapLength = 0;
        off_t mapOffset = 0;
        if (context->bufferType == V4L2_BUF_TYPE_VIDEO_CAPTURE_MPLANE) {
            mapLength = buffer.m.planes[0].length;
            mapOffset = static_cast<off_t>(buffer.m.planes[0].m.mem_offset);
        } else {
            mapLength = buffer.length;
            mapOffset = static_cast<off_t>(buffer.m.offset);
        }

        v4l2_exportbuffer expbuf{};
        expbuf.type = context->bufferType;
        expbuf.index = index;
        expbuf.plane = 0;
        expbuf.flags = O_CLOEXEC;
        if (IoctlRetry(context->fd, VIDIOC_EXPBUF, &expbuf) == -1) {
            SetLastError(ErrnoMessage("VIDIOC_EXPBUF failed for " + context->devicePath));
            LogPrint(ANDROID_LOG_ERROR, "VIDIOC_EXPBUF failed for %s buffer %u: %s",
                    context->devicePath.c_str(), index, strerror(errno));
            return false;
        }

        void* start = mmap(nullptr, mapLength, PROT_READ | PROT_WRITE, MAP_SHARED,
                context->fd, mapOffset);
        if (start == MAP_FAILED) {
            close(expbuf.fd);
            SetLastError(ErrnoMessage("mmap failed for " + context->devicePath));
            LogPrint(ANDROID_LOG_ERROR, "mmap failed for %s buffer %u: %s",
                    context->devicePath.c_str(), index, strerror(errno));
            return false;
        }

        context->buffers.push_back({start, mapLength, expbuf.fd});
    }

    context->configured = true;
    LogPrint(ANDROID_LOG_INFO,
            "Configured %s: type=%u %ux%u format=%08x stride=%u sizeImage=%u planes=%u buffers=%zu",
            context->devicePath.c_str(), static_cast<unsigned>(context->bufferType),
            context->width, context->height, context->pixelFormat,
            context->bytesPerLine, context->sizeImage,
            context->planeCount, context->buffers.size());
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
    context->devicePath = JStringToUtf8(env, devicePath);
    context->requestedWidth = static_cast<uint32_t>(std::max(width, 1));
    context->requestedHeight = static_cast<uint32_t>(std::max(height, 1));
    context->pixelFormat = ResolvePixelFormat(pixelFormat);

    context->fd = open(context->devicePath.c_str(), O_RDWR | O_NONBLOCK);
    if (context->fd < 0) {
        SetLastError(ErrnoMessage("open failed for " + context->devicePath));
        LogPrint(ANDROID_LOG_ERROR, "open failed for %s: %s",
                context->devicePath.c_str(), strerror(errno));
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
    if (context->previewStarted) {
        LogPrint(ANDROID_LOG_DEBUG, "nativeStartPreview skipped: preview already running for %s",
                context->devicePath.c_str());
        return 0;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        LogPrint(ANDROID_LOG_ERROR, "ANativeWindow_fromSurface returned null for %s",
                context->devicePath.c_str());
        return -1;
    }

    if (context->previewThread.joinable()) {
        context->previewThread.join();
    }

    context->stopRequested.store(false);
    context->window = window;
    context->previewStarted = true;
    context->previewThread = std::thread(PreviewLoop, context);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeSetRemoteFrameSource(
        JNIEnv* env, jclass clazz, jlong handle) {
    gRemoteFrameSource.store(FromHandle(handle));
    LogPrint(ANDROID_LOG_INFO, "Remote frame source handle=%p", FromHandle(handle));
    if (gRemoteNativeClass == nullptr) {
        env->GetJavaVM(&gRemoteVm);
        gRemoteNativeClass = static_cast<jclass>(env->NewGlobalRef(clazz));
        gRemoteFrameMethod = env->GetStaticMethodID(clazz, "onRemoteFrame", "([B)V");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeSetRemoteFramesEnabled(
        JNIEnv* /* env */, jclass /* clazz */, jboolean enabled) {
    gRemoteFramesEnabled.store(enabled == JNI_TRUE);
    LogPrint(ANDROID_LOG_INFO, "Remote direct frames enabled=%d", enabled == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeIsPreviewActive(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jlong handle) {
    CameraContext* context = FromHandle(handle);
    if (context == nullptr) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(context->mutex);
    return context->previewStarted ? JNI_TRUE : JNI_FALSE;
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

    context->stopRequested.store(true);
    if (context->previewThread.joinable()) {
        context->previewThread.join();
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

    context->stopRequested.store(true);
    if (context->previewThread.joinable()) {
        context->previewThread.join();
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

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeGetAhdInputPresence(
        JNIEnv* env,
        jclass /* clazz */,
        jstring subDevicePath) {
    const std::string path = JStringToUtf8(env, subDevicePath);
    if (path.empty()) {
        return -1;
    }

    const int fd = open(path.c_str(), O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        LogPrint(ANDROID_LOG_WARN, "open hotplug subdev %s failed: %s",
                path.c_str(), strerror(errno));
        return -1;
    }

    RkmoduleVcHotplugInfo info{};
    const int result = IoctlRetry(fd, kRkmoduleGetVcHotplugInfo, &info);
    const int savedErrno = errno;
    close(fd);
    if (result != 0) {
        LogPrint(ANDROID_LOG_WARN, "RKMODULE_GET_VC_HOTPLUG_INFO failed for %s: %s",
                path.c_str(), strerror(savedErrno));
        return -1;
    }

    const bool driverReportsPlugged = (info.detect_status & 0x01u) != 0;
    // The ARK7128 driver follows rkmodule_vc_hotplug_info's documented ABI:
    // bit 0 is 1 for a valid AHD input and 0 for no input.
    const jint normalizedPresence = driverReportsPlugged ? 1 : 0;
    LogPrint(ANDROID_LOG_INFO,
            "AHD hotplug: subdev=%s raw_detect_status=0x%02x normalized_presence=%d",
            path.c_str(), info.detect_status, normalizedPresence);
    return normalizedPresence;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeMotorOpen(
        JNIEnv* env,
        jclass /* clazz */,
        jstring devicePath) {
    const std::string path = JStringToUtf8(env, devicePath);
    if (path.empty()) {
        SetLastError("Motor serial device path is empty");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(gMotorMutex);
    if (gMotorFd >= 0) {
        return JNI_TRUE;
    }
    ClearLastError();
    gMotorFd = open(path.c_str(), O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (gMotorFd < 0) {
        SetLastError(ErrnoMessage("open failed for " + path));
        return JNI_FALSE;
    }
    if (!ConfigureMotorSerial(gMotorFd)) {
        CloseMotorLocked();
        return JNI_FALSE;
    }
    gMotorSequence = 0;
    gCounterReceived.clear();
    LogPrint(ANDROID_LOG_INFO, "Motor serial opened: %s at 9600 8N1", path.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeMotorSend(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jint command,
        jint level) {
    // 0x0e/0x0f 分别为全部补光灯开/关。
    const bool validCommand = (command >= 0x01 && command <= 0x0d)
            || command == 0x0e || command == 0x0f;
    if (!validCommand || level < 0 || level > 10) {
        SetLastError("Invalid motor command or level");
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(gMotorMutex);
    return SendSerialCommandLocked(0x01, static_cast<uint8_t>(command), static_cast<uint8_t>(level))
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeCounterReset(
        JNIEnv* /* env */,
        jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(gMotorMutex);
    return SendSerialCommandLocked(0x02, 0x02, 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeCounterRead(
        JNIEnv* /* env */,
        jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(gMotorMutex);
    // DnkCounter 在计数变化时自行发送 13 字节主动上报帧，此处只接收、绝不查询。
    int32_t counterValue = 0;
    if (!ReadCounterValueLocked(&counterValue)) {
        return std::numeric_limits<jlong>::min();
    }
    return static_cast<jlong>(counterValue);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ahd_1camera_V4l2CameraNative_nativeMotorClose(
        JNIEnv* /* env */,
        jclass /* clazz */) {
    std::lock_guard<std::mutex> lock(gMotorMutex);
    CloseMotorLocked();
    LogPrint(ANDROID_LOG_INFO, "Motor serial closed");
}
