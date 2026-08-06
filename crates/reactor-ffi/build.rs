fn main() {
    // On Apple platforms, Abseil registers ObjC categories (e.g.
    // +[NSString stringForAbslStringView:]) in a static library.  Without
    // -ObjC the linker dead-strips those categories, causing an
    // NSInvalidArgumentException at runtime when the WebRTC VP9 encoder
    // queries scalability modes.
    let target_os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "macos" || target_os == "ios" {
        println!("cargo:rustc-link-arg=-ObjC");
    }
    let _ = target_os;
}
