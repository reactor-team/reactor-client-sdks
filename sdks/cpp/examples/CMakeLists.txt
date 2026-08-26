# The eight examples. One capability each, numbered as in sdks/python/examples.
#
# Built by default when this project is top-level, so `mise run build:cpp`
# compiles them — an example that would die on its first line cannot then reach a
# reader. Running them needs a real key and a published model; see the README.

# Optional on purpose: the examples build and run without a window, and SDL2 is a
# lot to ask of someone who only wants to read them. With it, REACTOR_SHOW=1 draws
# the frames — a count proves something arrived, not that it was the right thing.
find_package(SDL2 QUIET)

set(REACTOR_EXAMPLES
  01_connect_and_receive
  02_upload_image
  03_pause_and_resume
  04_publish_track
  05_multi_connection
  06_record_clip
  07_frame_metadata
  08_snapshot_and_rewind
)

foreach(example IN LISTS REACTOR_EXAMPLES)
  add_executable(${example} "${example}.cpp")
  target_link_libraries(${example} PRIVATE reactor::sdk)
  target_include_directories(${example} PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
  target_compile_options(
    ${example}
    PRIVATE
      $<$<COMPILE_LANG_AND_ID:CXX,GNU,Clang,AppleClang>:-Wall;-Wextra;-Werror>
      $<$<COMPILE_LANG_AND_ID:CXX,MSVC>:/W4;/WX>
  )
  if(SDL2_FOUND)
    target_compile_definitions(${example} PRIVATE REACTOR_EXAMPLES_HAVE_SDL2)
    target_link_libraries(${example} PRIVATE SDL2::SDL2)
  endif()
endforeach()
