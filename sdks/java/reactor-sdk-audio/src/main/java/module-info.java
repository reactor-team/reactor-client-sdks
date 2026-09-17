/**
 * Optional microphone and speaker helpers for the Reactor SDK.
 *
 * <p>A separate module because it needs {@code java.desktop}, and nothing that can open audio
 * hardware belongs on the core's mandatory import path.
 */
module inc.reactor.sdk.audio {
    requires transitive inc.reactor.sdk;
    requires java.desktop;

    exports inc.reactor.sdk.audio;
}
