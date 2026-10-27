package inc.reactor.sdk;

/** How a pushed video frame's pixels are laid out. */
public enum PixelFormat {

    /** Four bytes a pixel: B, G, R, A. What the native library takes, as it is. */
    BGRA(4),

    /**
     * Three bytes a pixel: R, G, B, as decoders and image libraries tend to hand frames over.
     * Converted to BGRA, opaque, before the push.
     */
    RGB(3);

    private final int bytesPerPixel;

    PixelFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }

    /** @return how many bytes one pixel takes in this layout */
    public int bytesPerPixel() {
        return bytesPerPixel;
    }
}
