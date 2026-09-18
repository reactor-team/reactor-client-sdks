package inc.reactor.examples;

/**
 * The environment every example reads.
 *
 * <p>Deliberately thin: it holds the three or four variables all eight share and nothing else. What
 * each example does is spelled out in its own file, because that is what makes an example readable.
 */
final class Examples {

    private Examples() {}

    /**
     * @return the coordinator, from {@code REACTOR_API_URL} or production
     */
    static String apiUrl() {
        return System.getenv().getOrDefault("REACTOR_API_URL", "https://api.reactor.inc");
    }

    /**
     * @return the key from {@code REACTOR_API_KEY}
     * @throws IllegalStateException when it is not set, naming where to get one
     */
    static String apiKey() {
        String key = System.getenv("REACTOR_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("set REACTOR_API_KEY — https://www.reactor.inc/account/api-keys");
        }
        return key;
    }

    /**
     * @param fallback the model this example was written for
     * @return that model, or whatever {@code REACTOR_MODEL} names
     */
    static String model(String fallback) {
        // Owner-qualified, always. A bare name resolves under reactor/, which works by luck of
        // ownership and answers 403 for anyone else's model.
        return System.getenv().getOrDefault("REACTOR_MODEL", fallback);
    }

    /**
     * @return whether to open a window, from {@code REACTOR_SHOW}
     */
    static boolean show() {
        return "1".equals(System.getenv("REACTOR_SHOW"));
    }

    /**
     * @param fallback how long this example runs for by default
     * @return that, or whatever {@code REACTOR_SECONDS} says
     */
    static long seconds(long fallback) {
        String configured = System.getenv("REACTOR_SECONDS");
        return configured == null || configured.isBlank() ? fallback : Long.parseLong(configured);
    }
}
