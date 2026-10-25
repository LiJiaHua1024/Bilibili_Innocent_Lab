if(NOT TARGET ncnn)
    message(FATAL_ERROR "The parent must provide the pinned ncnn target before adding player_interpolation")
endif()

set(frame_shader_headers)
foreach(shader upstream/warp upstream/warp_pack4 frame_preproc frame_postproc frame_fused frame_fused_rgb8 frame_reduce)
    get_filename_component(shader_name "${shader}" NAME)
    set(source "${CMAKE_CURRENT_SOURCE_DIR}/${shader}.comp")
    set(header "${CMAKE_CURRENT_BINARY_DIR}/generated/${shader_name}.comp.hex.h")
    add_custom_command(OUTPUT "${header}"
        COMMAND "${CMAKE_COMMAND}" "-DSOURCE=${source}" "-DOUTPUT=${header}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_shader.cmake"
        DEPENDS "${source}" "${CMAKE_CURRENT_SOURCE_DIR}/embed_shader.cmake"
        VERBATIM)
    list(APPEND frame_shader_headers "${header}")
endforeach()

add_library(innocent_frame_engine STATIC frame_engine.cpp upstream/rife_warp.cpp ${frame_shader_headers})
target_compile_features(innocent_frame_engine PUBLIC cxx_std_17)
target_include_directories(innocent_frame_engine PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}"
    PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/generated")
target_link_libraries(innocent_frame_engine PRIVATE ncnn)
set_target_properties(innocent_frame_engine PROPERTIES POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
if(MSVC)
    target_compile_options(innocent_frame_engine PRIVATE /W4 /EHsc)
else()
    target_compile_options(innocent_frame_engine PRIVATE -Wall -Wextra -Wpedantic)
    # ncnn exports -fno-exceptions; per-source flags follow imported usage requirements.
    set_source_files_properties(frame_engine.cpp PROPERTIES COMPILE_OPTIONS -fexceptions)
endif()
