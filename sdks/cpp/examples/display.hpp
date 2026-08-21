// The one place the examples draw a frame.
//
// A frame count proves something arrived, not that it was the right something —
// so every example can open a window, and none of them contains the code to do
// it. `REACTOR_SHOW=1` turns it on; without SDL2 at build time it is a no-op that
// still compiles, so the examples build anywhere.
//
// `show()` runs on the library's delivery thread and `pump()` on the thread that
// owns the window — so show() only copies, and every SDL call happens in pump().
// Not merely a data race if they are mixed: SDL's video backends require window
// creation and event handling on the main thread, and on macOS a window made
// anywhere else does not draw.
//
//   REACTOR_SHOW=1 ./01_connect_and_receive
#pragma once

#include <cstdlib>
#include <mutex>
#include <reactor/track.hpp>
#include <string>
#include <vector>

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

  /// Hand over a frame from the library's delivery thread. Copies and returns.
  ///
  /// The copy is not the cost it looks like: the frame's buffer is gone when this
  /// returns, so it has to be copied somewhere regardless — and doing it here
  /// rather than into a texture is what keeps SDL on one thread.
  void show(const reactor::VideoFrame& frame) {
    if (!enabled_ || frame.bgra == nullptr) {
      return;
    }
    const std::size_t bytes =
        static_cast<std::size_t>(frame.width) * static_cast<std::size_t>(frame.height) * 4U;
    const std::lock_guard<std::mutex> lock(mutex_);
    // Newest wins. A window one frame behind is better than a queue that grows for
    // as long as the renderer is slower than the sender — the same trade the FFI
    // makes on its own video thread.
    pending_.assign(frame.bgra, frame.bgra + bytes);
    pending_width_ = frame.width;
    pending_height_ = frame.height;
  }

  /// Service the window, drawing whatever `show()` last handed over.
  ///
  /// Returns false when the user closed it. Every SDL call in this class happens
  /// here, on whichever thread the example calls it from — its own.
  bool pump() {
#ifdef REACTOR_EXAMPLES_HAVE_SDL2
    if (!enabled_) {
      return true;
    }

    std::vector<std::uint8_t> frame;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      frame.swap(pending_);
      width = pending_width_;
      height = pending_height_;
    }

    if (!frame.empty() && ensure_window(width, height)) {
      // BGRA is what the FFI delivers and what SDL calls ARGB8888 little-endian,
      // so there is no conversion here.
      SDL_UpdateTexture(texture_, nullptr, frame.data(), static_cast<int>(width) * 4);
      SDL_RenderClear(renderer_);
      SDL_RenderCopy(renderer_, texture_, nullptr, nullptr);
      SDL_RenderPresent(renderer_);
    }

    if (window_ == nullptr) {
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

  /// The newest frame `show()` was given, waiting for `pump()` to draw it.
  std::mutex mutex_;
  std::vector<std::uint8_t> pending_;
  std::uint32_t pending_width_ = 0;
  std::uint32_t pending_height_ = 0;
};

}  // namespace examples
