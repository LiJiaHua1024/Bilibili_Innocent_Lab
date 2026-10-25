cmake_minimum_required(VERSION 3.31)
project(innocent_frame_interpolation LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
set(ncnn_archive "https://github.com/Tencent/ncnn/releases/download/20261020/ncnn-20261020-android-vulkan.zip")
if(INNOCENT_NCNN_ARCHIVE)
    set(ncnn_archive "${INNOCENT_NCNN_ARCHIVE}")
endif()
FetchContent_Declare(ncnn_android
    URL "${ncnn_archive}"
    URL_HASH SHA256=6e0c5f9b397faf32b923de2ef6210af9bf375851869dceff06a8005331981086
    DOWNLOAD_EXTRACT_TIMESTAMP FALSE)
FetchContent_MakeAvailable(ncnn_android)
find_package(ncnn 20261020 EXACT CONFIG REQUIRED NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH
    PATHS "${ncnn_android_SOURCE_DIR}/${ANDROID_ABI}/lib/cmake/ncnn")

add_subdirectory(player_interpolation)
add_library(innocent_frame_interpolation SHARED frame_jni.cpp)
target_link_libraries(innocent_frame_interpolation PRIVATE innocent_frame_engine)
target_compile_options(innocent_frame_interpolation PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
set_source_files_properties(frame_jni.cpp PROPERTIES COMPILE_OPTIONS -fexceptions)
target_link_options(innocent_frame_interpolation PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")

# A private per-variant copy, independent of AGP's filtered merge artifact.
if(INNOCENT_FRAME_PAYLOAD_DIR)
    if(NOT INNOCENT_FRAME_BUILD_KEY MATCHES "^[0-9a-f]+$")
        message(FATAL_ERROR "A frame payload requires its build-owned source/configuration key")
    endif()
    set(frame_payload "${INNOCENT_FRAME_PAYLOAD_DIR}/${ANDROID_ABI}/libinnocent_frame_interpolation.so")
    set(frame_receipt "${INNOCENT_FRAME_PAYLOAD_DIR}/${ANDROID_ABI}/frame-build-key.txt")
    set(frame_key_source "${CMAKE_CURRENT_BINARY_DIR}/frame-build-key.txt")
    file(GENERATE OUTPUT "${frame_key_source}" CONTENT "${INNOCENT_FRAME_BUILD_KEY}\n${ANDROID_ABI}\n" NEWLINE_STYLE UNIX)
    set_property(TARGET innocent_frame_interpolation APPEND PROPERTY LINK_DEPENDS "${frame_key_source}")
    add_custom_command(TARGET innocent_frame_interpolation POST_BUILD
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${INNOCENT_FRAME_PAYLOAD_DIR}/${ANDROID_ABI}"
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "$<TARGET_FILE:innocent_frame_interpolation>" "${frame_payload}"
        COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${frame_key_source}" "${frame_receipt}"
        BYPRODUCTS "${frame_payload}" "${frame_receipt}"
        VERBATIM)
endif()
