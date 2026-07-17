cmake_minimum_required(VERSION 3.22.1)

project(ahd_v4l2)

add_library(ahd_v4l2 SHARED
    ahd_v4l2_jni.cpp
)

target_compile_features(ahd_v4l2 PRIVATE cxx_std_17)

target_include_directories(ahd_v4l2 PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/librga/include
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/librga/im2d_api
)

add_library(rga SHARED IMPORTED)
set_target_properties(rga PROPERTIES
    IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librga.so
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(ahd_v4l2
    rga
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
