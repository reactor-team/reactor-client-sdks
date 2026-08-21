// The one place the examples draw a frame.
//
// A frame count proves something arrived, not that it was the right something —
// so every example can open a window, and none of them contains the code to do
// it. `REACTOR_SHOW=1` turns it on; without SDL2 at build time it is a no-op that
// still compiles, so the examples build anywhere.
//
//   REACTOR_SHOW=1 ./01_connect_and_receive
#pragma once

#include <cstdlib>
#include <reactor/track.hpp>
#include <string>

#ifdef REACTOR_EXAMPLES_HAVE_SDL2
#include <SDL.h>
#endif

namespace examples {

class Display {
 public:
  explicit Display(std::string title) : title_(std::move(title)) {
    const char* show = std::getenv("REACTOR_SHOW");
    enabled_ = show != nullptr && *show != '\0' && std::string{show} != "0";
  }

  ~Display() { destroy(); }

  Display(const Display&) = delete;
  Display& operator=(const Display&) = delete;
  Display(Display&&) = delete;
  Display& operator=(Display&&) = delete;

  /// Draw a frame. Safe to call from the library's delivery thread.
  void show(const reactor::VideoFrame& frame) {
#ifdef REACTOR_EXAMPLES_HAVE_SDL2
    if (!enabled_ || frame.bgra == nullptr) {
      return;
    }
    if (!ensure_window(frame.width, frame.height)) {
      return;
    }
    // BGRA is what the FFI delivers and what SDL calls ARGB8888 little-endian, so
    // there is no conversion here — only a copy, because the frame is gone when
    // this returns.
    SDL_UpdateTexture(texture_, nullptr, frame.bgra, static_cast<int>(frame.width) * 4);
    SDL_RenderClear(renderer_);
    SDL_RenderCopy(renderer_, texture_, nullptr, nullptr);
    SDL_RenderPresent(renderer_);
#else
    (void)frame;
#endif
  }

  /// Service the window. Returns false when the user closed it.
  bool pump() {
#ifdef REACTOR_EXAMPLES_HAVE_SDL2
    if (!enabled_ || window_ == nullptr) {
      return true;
    }
    SDL_Event event;
    while (SDL_PollEvent(&event) != 0) {
      if (event.type == SDL_QUIT) {
        return false;
      }
    }
#endif
    return true;
  }

 private:
#ifdef REACTOR_EXAMPLES_HAVE_SDL2
  bool ensure_window(std::uint32_t width, std::uint32_t height) {
    if (window_ != nullptr) {
      return true;
    }
    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
      return false;
    }
    window_ = SDL_CreateWindow(title_.c_str(), SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                               static_cast<int>(width), static_cast<int>(height), SDL_WINDOW_SHOWN);
    if (window_ == nullptr) {
      return false;
    }
    renderer_ = SDL_CreateRenderer(window_, -1, SDL_RENDERER_ACCELERATED);
    texture_ = SDL_CreateTexture(renderer_, SDL_PIXELFORMAT_ARGB8888, SDL_TEXTUREACCESS_STREAMING,
                                 static_cast<int>(width), static_cast<int>(height));
    return renderer_ != nullptr && texture_ != nullptr;
  }

  void destroy() {
    if (texture_ != nullptr) {
      SDL_DestroyTexture(texture_);
    }
    if (renderer_ != nullptr) {
      SDL_DestroyRenderer(renderer_);
    }
    if (window_ != nullptr) {
      SDL_DestroyWindow(window_);
      SDL_Quit();
    }
  }

  SDL_Window* window_ = nullptr;
  SDL_Renderer* renderer_ = nullptr;
  SDL_Texture* texture_ = nullptr;
#else
  void destroy() {}
#endif

  std::string title_;
  bool enabled_ = false;
};

}  // namespace examples
