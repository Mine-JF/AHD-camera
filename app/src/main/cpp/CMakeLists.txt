cmake_minimum_required(VERSION 3.22.1)

project(ahd_v4l2)

add_library(ahd_v4l2 SHARED
    ahd_v4l2_jni.cpp
)

target_compile_features(ahd_v4l2 PRIVATE cxx_std_17)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(ahd_v4l2
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
