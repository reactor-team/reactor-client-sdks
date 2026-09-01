# A consumer of the *installed* package, not of this build tree.
#
# Configured against an install tree that has been moved since it was created,
# which is the one thing the SDK's own tests cannot check: they link
# `reactor::sdk` from the build tree, where every path the export could have
# baked in still happens to exist. A release archive is unpacked somewhere else
# on a machine that never built it, and this is that machine.
#
# Driven by `mise run test:cpp:package`.

cmake_minimum_required(VERSION 3.24)

project(reactor-package-consumer LANGUAGES CXX)

find_package(reactor-sdk REQUIRED)

add_executable(consumer main.cpp)

# The audio devices when the package ships them: a second library with a second
# exported link interface, and a private dependency of a *static* library reaches
# consumers anyway — one the installed config never looks up is invisible until
# something links it from outside the build tree, which is what this fixture is.
# It brings reactor::sdk with it, so linking it exercises both.
if(TARGET reactor::sdk_audio)
  target_link_libraries(consumer PRIVATE reactor::sdk_audio)
else()
  target_link_libraries(consumer PRIVATE reactor::sdk)
endif()

# Windows has no rpath: the loader looks beside the executable, so the DLL has to
# be there before this can run. That is the same copy a consumer makes when they
# ship — the README's install rule — done here at build time, and it is the step
# whose absence let an archive with no DLL in it look fine.
if(WIN32)
  add_custom_command(
    TARGET consumer POST_BUILD
    COMMAND "${CMAKE_COMMAND}" -E copy_if_different
            "$<TARGET_FILE:reactor::ffi>" "$<TARGET_FILE_DIR:consumer>"
    VERBATIM
  )
endif()
