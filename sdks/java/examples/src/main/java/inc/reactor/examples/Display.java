package inc.reactor.examples;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * An optional window, so an example can be watched rather than counted.
 *
 * <p>A frame count proves something arrived, not that it was the right something. Every example
 * here draws into this when {@code REACTOR_SHOW=1} is set, and counts frames otherwise — so the
 * default run needs no display and the check that matters is one environment variable away.
 *
 * <p>This is the only file the examples share. Everything else each one does is spelled out in its
 * own file: a reader who has to open two files to understand one example is reading one file too
 * many.
 */
public final class Display implements AutoCloseable {

    private final @org.jspecify.annotations.Nullable JFrame frame;
    private final AtomicReference<BufferedImage> latest = new AtomicReference<>();

    private Display(@org.jspecify.annotations.Nullable JFrame frame) {
        this.frame = frame;
    }

    /**
     * Opens a window, or a stub that draws nothing.
     *
     * @param title what to call it
     * @param enabled whether to show anything; {@code REACTOR_SHOW=1} is how the examples decide
     * @return the window
     */
    public static Display window(String title, boolean enabled) {
        if (!enabled) {
            return new Display(null);
        }
        Display display = new Display(new JFrame(title));
        SwingUtilities.invokeLater(() -> {
            JFrame window = display.frame;
            if (window == null) {
                return;
            }
            window.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            window.setContentPane(new JPanel() {
                private static final long serialVersionUID = 1L;

                @Override
                protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics);
                    BufferedImage image = display.latest.get();
                    if (image != null) {
                        graphics.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                    }
                }
            });
            window.setSize(960, 540);
            window.setVisible(true);
        });
        return display;
    }

    /**
     * Takes a frame to draw.
     *
     * <p>Called from the FFI's delivery thread, so it converts and returns rather than painting:
     * blocking there is backpressure on the model, and a repaint is not worth any of it.
     *
     * @param bgra the pixels, B, G, R, A per pixel
     * @param width frame width
     * @param height frame height
     */
    public void submit(byte[] bgra, int width, int height) {
        if (frame == null) {
            return;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int index = 0; index < pixels.length; index++) {
            int at = index * 4;
            // BGRA on the wire, ARGB in the int. Reading these in the order they arrive would
            // swap red and blue, which looks like a model producing strange colours.
            int blue = bgra[at] & 0xFF;
            int green = bgra[at + 1] & 0xFF;
            int red = bgra[at + 2] & 0xFF;
            int alpha = bgra[at + 3] & 0xFF;
            pixels[index] = alpha << 24 | red << 16 | green << 8 | blue;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        latest.set(image);
        frame.repaint();
    }

    /**
     * Waits, drawing while it waits when there is a window.
     *
     * @param duration how long to hold
     * @throws InterruptedException if the wait is interrupted
     */
    public void hold(Duration duration) throws InterruptedException {
        long deadline = System.nanoTime() + duration.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.sleep(50);
            if (frame != null) {
                frame.repaint();
            }
        }
    }

    @Override
    public void close() {
        if (frame != null) {
            SwingUtilities.invokeLater(frame::dispose);
        }
    }
}
