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
target_link_libraries(consumer PRIVATE reactor::sdk)
