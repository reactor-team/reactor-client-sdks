package inc.reactor.sdk;

/**
 * Told how a clip download is going.
 *
 * <p>Called on the download's own thread. Blocking it delays that download and nothing else.
 */
@FunctionalInterface
public interface ClipProgress {

    /**
     * @param done segments written so far
     * @param total segments the clip holds
     */
    void report(int done, int total);
}
