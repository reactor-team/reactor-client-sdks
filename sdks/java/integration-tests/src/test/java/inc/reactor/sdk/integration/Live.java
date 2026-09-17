package inc.reactor.sdk.integration;

import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.ReactorOptions;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * A real client, against a real model, over a real WebRTC connection.
 *
 * <p>Nothing here is faked. The unit suite proves the binding behaves against a library it can make
 * misbehave on purpose; this proves it behaves against the platform, which is the only place the
 * whole path exists — the coordinator, the codecs the fleet negotiates, the model contracts as
 * deployed rather than as declared.
 *
 * <p>{@code reactor/echo} because it declares all four combinations of kind and direction:
 * {@code webcam} and {@code mic} sendonly, {@code main_video} and {@code main_audio} recvonly. No
 * other published model exercises the send path at all.
 */
final class Live {

    static final String API_URL = System.getenv().getOrDefault("REACTOR_API_URL", "https://api.reactor.inc");
    static final String MODEL = System.getenv().getOrDefault("INTEGRATION_TESTS_REACTOR_MODEL", "reactor/echo");

    /** The tracks echo declares, named here so a test reads as what it is checking. */
    static final String VIDEO_IN = "webcam";

    static final String AUDIO_IN = "mic";
    static final String VIDEO_OUT = "main_video";
    static final String AUDIO_OUT = "main_audio";

    /**
     * Sessions this suite may be creating at once.
     *
     * <p>The model's session quota is shared with every other binding's suite and with anyone
     * running an example. Creating them one at a time costs a little wall clock and keeps this
     * suite from being the reason another one sees a 429.
     */
    private static final Semaphore CONNECTING = new Semaphore(1);

    /**
     * The shortest gap between two session creations, anywhere in this process.
     *
     * <p>Two quotas sit behind this and they are not the same thing: a rate limit on creating
     * sessions, and a ceiling on how many may exist at once. Pacing answers the first. The second
     * is why these tests share one client per class rather than opening one each — a suite that
     * needs a dozen sessions to check a dozen things will meet that ceiling on a busy afternoon
     * however politely it asks for them.
     */
    private static final Duration SESSION_INTERVAL = Duration.ofMillis(700);

    private static long lastCreatedAt;

    private Live() {}

    /**
     * A token, or a skipped test.
     *
     * <p>Locally, no key means these are skipped: a contributor should not need production
     * credentials to run the unit suite. In CI it fails instead — a required check that goes green
     * because a secret was missing is worse than one that fails.
     *
     * @return the token
     */
    static String jwt() {
        String key = System.getenv("INTEGRATION_TESTS_REACTOR_API_KEY");
        boolean inCi = "true".equals(System.getenv("CI"));
        if (key == null || key.isBlank()) {
            if (inCi) {
                throw new IllegalStateException(
                        "INTEGRATION_TESTS_REACTOR_API_KEY is not set. This suite gates CI and the"
                                + " release, and it cannot pass without reaching the platform — so a"
                                + " missing secret fails here rather than passing quietly.");
            }
            Assumptions.abort("INTEGRATION_TESTS_REACTOR_API_KEY is not set — skipping the live suite");
        }
        return Reactor.fetchJwt(API_URL, key).join();
    }

    /**
     * Opens a client and connects it, one session at a time.
     *
     * @param jwt the token
     * @return a connected client the caller closes
     */
    static Reactor connected(String jwt) {
        try {
            if (!CONNECTING.tryAcquire(2, TimeUnit.MINUTES)) {
                throw new IllegalStateException("waited two minutes for a turn to create a session");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting to create a session", interrupted);
        }
        pace();
        Reactor reactor =
                Reactor.open(ReactorOptions.builder(API_URL, MODEL).jwt(jwt).build());
        try {
            reactor.connect().orTimeout(90, TimeUnit.SECONDS).join();
            return reactor;
        } catch (RuntimeException failed) {
            reactor.close();
            throw failed;
        } finally {
            CONNECTING.release();
        }
    }

    private static synchronized void pace() {
        long earliest = lastCreatedAt + SESSION_INTERVAL.toNanos();
        long wait = earliest - System.nanoTime();
        if (wait > 0) {
            try {
                Thread.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        lastCreatedAt = System.nanoTime();
    }

    /**
     * Waits for something that a live session produces when it is ready to.
     *
     * @param what names the thing, for the failure message
     * @param timeout how long to wait
     * @param condition what to keep checking
     */
    static void await(String what, Duration timeout, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted waiting for " + what, interrupted);
            }
        }
        throw new AssertionError("waited " + timeout.toSeconds() + "s for " + what + " and it never happened");
    }
}
