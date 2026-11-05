package inc.reactor.sdk;

import inc.reactor.sdk.internal.ClientPeer;
import java.lang.ref.Cleaner;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A Reactor client.
 *
 * <p>Create one, connect, work, close:
 *
 * <pre>{@code
 * try (Reactor reactor = Reactor.open(ReactorOptions.builder("https://api.reactor.inc", "reactor/echo")
 *         .jwt(token)
 *         .build())) {
 *     reactor.onStatus(status -> System.out.println("status: " + status));
 *     reactor.connect().join();
 *     // ...
 * }
 * }</pre>
 *
 * <p><b>Close it.</b> A creator that goes away without disconnecting orphans the session, and the
 * next run cannot start until that clears. Try-with-resources is the reliable way; the {@link
 * Cleaner} registered here is a safety net for a client somebody forgot, not a plan.
 *
 * <p>Control events — status, error, message, track, capabilities, session id — are delivered
 * through the {@link Dispatcher}, so a Swing or JavaFX application can have them arrive on the
 * thread its toolkit requires. Media does not go through it: see {@link Dispatcher}.
 *
 * <p>Thread-safe. {@link #close()} is idempotent and may be called from an event handler.
 */
public final class Reactor implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final ClientPeer peer;
    private final ReactorOptions options;
    private final Cleaner.Cleanable cleanable;
    /** Held from a step's first push to {@code reactor_step} taking the buffer, so steps from two
     * threads cannot interleave their media. */
    private final Object stepLock = new Object();

    private Reactor(ClientPeer peer, ReactorOptions options) {
        this.peer = peer;
        this.options = options;
        // The action is the peer itself, and the peer holds no reference back to this object. A
        // cleaning action that captured its own referent would keep it reachable forever, so the
        // cleaner would never run and the native handle would never be destroyed — the leak this
        // registration exists to prevent.
        this.cleanable = CLEANER.register(this, peer);
    }

    /**
     * Creates a client. Nothing is connected until {@link #connect()}.
     *
     * @param options what to connect to
     * @return the client
     */
    public static Reactor open(ReactorOptions options) {
        return new Reactor(ClientPeer.create(options), options);
    }

    /**
     * The peer underneath, for this SDK's own tests.
     *
     * <p>Package-private, and read by the test fixtures rather than by anything a consumer can
     * reach.
     *
     * @return the peer
     */
    inc.reactor.sdk.internal.ClientPeer peer() {
        return peer;
    }

    /**
     * Creates a client over a given library, for this SDK's own tests.
     *
     * <p>Package-private, and the only thing it changes is where the ABI comes from: everything
     * below it is the same code the public factory runs, which is the point of having a seam rather
     * than a test double for the client itself.
     */
    static Reactor open(
            ReactorOptions options,
            java.util.function.Function<java.lang.foreign.Arena, inc.reactor.sdk.internal.Ffi> loader) {
        return new Reactor(ClientPeer.create(options, loader), options);
    }

    /**
     * Exchanges an API key for a token.
     *
     * <p>Everything else here wants a JWT, and a caller holding a key needs this first. It takes no
     * client: the exchange is one request, and it is not bounded by any session's lifetime.
     *
     * <p>A token minted this way carries everything the key's roles allow. That is fine server to
     * server and wrong to hand to a client you do not control — scope it there.
     *
     * @param apiUrl the coordinator's base URL
     * @param apiKey the key to exchange
     * @return the token
     */
    public static CompletableFuture<String> fetchJwt(String apiUrl, String apiKey) {
        return fetchJwt(apiUrl, apiKey, null, false);
    }

    /**
     * Exchanges an API key for a scoped token.
     *
     * @param apiUrl the coordinator's base URL
     * @param apiKey the key to exchange
     * @param options scoping options, or {@code null} for everything the key allows. An
     *     unrecognised key in this object is an error rather than ignored: dropping a misspelt
     *     {@code models} in silence would mint exactly the unscoped token the caller was avoiding
     * @param local whether to accept a dev coordinator's certificate
     * @return the token
     */
    public static CompletableFuture<String> fetchJwt(
            String apiUrl, String apiKey, @Nullable JsonValue options, boolean local) {
        return inc.reactor.sdk.internal.Authentication.fetchJwt(
                inc.reactor.sdk.internal.NativeLibrary.shared(),
                apiUrl,
                apiKey,
                options == null ? null : options.toJsonString(),
                local);
    }

    /**
     * Creates a session and establishes the transport.
     *
     * @return settles when the transport is up
     */
    public CompletableFuture<Void> connect() {
        return peer.connect(null, null);
    }

    /**
     * Adopts an existing session rather than creating one.
     *
     * @param sessionId the session to join
     * @param connectionId a connection id a backend already registered for it, or {@code null} to
     *     register a new one — most callers pass {@code null}
     * @return settles when the transport is up
     */
    public CompletableFuture<Void> connect(String sessionId, @Nullable Integer connectionId) {
        return peer.connect(sessionId, connectionId);
    }

    /**
     * Leaves the session. Unlike {@link #reconnect()}, this ends it.
     *
     * @return settles when the session has been left
     */
    public CompletableFuture<Void> disconnect() {
        return peer.disconnect();
    }

    /**
     * Re-establishes the transport on the same session.
     *
     * <p>A reconnect resumes recvonly tracks and nothing else — anything published before one is
     * not published after it.
     *
     * @return settles when the transport is up again
     */
    public CompletableFuture<Void> reconnect() {
        return peer.reconnect();
    }

    /** @return where this client is between disconnected and ready */
    public ConnectionStatus status() {
        return peer.status();
    }

    /** @return the session id, or empty when there is no session */
    public Optional<String> sessionId() {
        return peer.sessionId();
    }

    /**
     * The tracks this session declared, in the order it declared them.
     *
     * @return the tracks; empty before the session is accepted and after it is torn down, so "no
     *     tracks yet" is distinguishable from a name that does not exist
     */
    public TrackList tracks() {
        return new TrackList(peer.trackDeclarations().stream()
                .map(declaration -> new Track(
                        peer, declaration.name(), declaration.kind(), declaration.direction(), declaration.mid()))
                .toList());
    }

    /**
     * One track, by the name the session declared it under.
     *
     * <p>This is how an application that knows its model asks. Unknown names raise rather than
     * answering with nothing: a handler registered on a track that does not exist never fires, and
     * a session that looks healthy and produces nothing is the failure this SDK exists to refuse.
     *
     * @param name the declared name
     * @return the track
     * @throws ReactorException when the session declared no track by that name, listing the names
     *     it did declare
     */
    public Track track(String name) {
        TrackList declared = tracks();
        return declared.byName(name)
                .orElseThrow(() -> ReactorException.of(
                        ErrorCode.NOT_FOUND.code(),
                        "this session declares no track named \"" + name + "\". It declares: "
                                + declared.stream().map(Track::name).toList() + ".",
                        null,
                        "track",
                        null));
    }

    /**
     * @param handler called with every status change
     * @return a subscription that removes it
     */
    public Subscription onStatus(Consumer<ConnectionStatus> handler) {
        return peer.onStatus(handler);
    }

    /**
     * @param handler called with every error the session reports, carrying the same exception type
     *     a failed call would throw
     * @return a subscription that removes it
     */
    public Subscription onError(Consumer<ReactorException> handler) {
        return peer.onError(handler);
    }

    /**
     * @param handler called with every application message the model sends
     * @return a subscription that removes it
     */
    public Subscription onMessage(Consumer<JsonValue> handler) {
        return peer.onMessage(handler);
    }

    /**
     * @param handler called with every runtime (platform) message
     * @return a subscription that removes it
     */
    public Subscription onRuntimeMessage(Consumer<JsonValue> handler) {
        return peer.onRuntimeMessage(handler);
    }

    /**
     * @param handler called with the session's capabilities
     * @return a subscription that removes it
     */
    public Subscription onCapabilities(Consumer<JsonValue> handler) {
        return peer.onCapabilities(handler);
    }

    /**
     * @param handler called when the session id is assigned, and again with empty when it clears
     * @return a subscription that removes it
     */
    public Subscription onSessionId(Consumer<Optional<String>> handler) {
        return peer.onSessionId(handler);
    }

    /**
     * Sends a command and waits for the model's reply.
     *
     * @param name the command, as the model's schema declares it
     * @param args its arguments
     * @return the reply, empty when the model acknowledged without producing a message
     */
    public CompletableFuture<java.util.Optional<CommandReply>> sendCommand(String name, JsonValue args) {
        return peer.sendCommand(name, args.toJsonString(), null);
    }

    /**
     * Sends a command that takes no arguments.
     *
     * @param name the command
     * @return the reply, empty when the model acknowledged without producing a message
     */
    public CompletableFuture<java.util.Optional<CommandReply>> sendCommand(String name) {
        return peer.sendCommand(name, null, null);
    }

    /**
     * Sends a command with files the platform is already holding.
     *
     * @param name the command
     * @param args its arguments
     * @param uploads the files, keyed by the parameter each one fills
     * @return the reply, empty when the model acknowledged without producing a message
     */
    public CompletableFuture<java.util.Optional<CommandReply>> sendCommand(
            String name, JsonValue args, java.util.Map<String, FileRef> uploads) {
        if (uploads.isEmpty()) {
            return sendCommand(name, args);
        }
        JsonValue.ObjectBuilder named = JsonValue.object();
        uploads.forEach((parameter, ref) -> named.put(parameter, ref.toJsonValue()));
        return peer.sendCommand(name, args.toJsonString(), named.build().toJsonString());
    }

    // ── Stepped generation ──────────────────────────────────────────────────

    /** @return how this client's sessions generate, as {@link ReactorOptions.Builder#generation} set it */
    public Generation generation() {
        return peer.generation();
    }

    /**
     * Runs one step of a stepped session and answers with what it produced.
     *
     * <p>The runtime applies, in order: {@code state} (each key as the model's {@code set_<key>}
     * command), then {@code commands}, then every input frame pushed since the previous step — in
     * a stepped session {@link Track#pushFrame(byte[], int, int)} does not send, it buffers for the
     * next step — and then generates. The runtime checks the whole request before it applies any of
     * it, so a step it refuses changed nothing; past that check these are separate calls, so a call
     * whose handler fails leaves the calls before it applied. Every part is optional; {@link #step()} with nothing
     * runs on the model's current state. Commands sent with {@link #sendCommand} outside a step
     * still work in a stepped session and change the state the next step uses.
     *
     * <p>A file input is an upload reference, as for any command: {@link #uploadFile upload} it
     * first, then put the reference in a command's arguments or in the value of an upload-typed
     * state field.
     *
     * <pre>{@code
     * Reactor reactor = Reactor.open(ReactorOptions.builder(apiUrl, "owner/model")
     *         .apiKey(key)
     *         .generation(Generation.STEPPED)
     *         .build());
     * reactor.connect().join();
     * Track camera = reactor.track("camera");
     * camera.publish().join();
     * camera.pushFrame(bgra, width, height);          // buffered, not sent
     * StepResult result = reactor.step(
     *                 JsonValue.object().put("prompt", "a red fox").build(),
     *                 List.of(StepCommand.of("advance", JsonValue.object().put("frames", 4).build())))
     *         .join();
     * for (StepFrame frame : result.track("main_video").frames()) {
     *     show(frame.data(), frame.shape());          // (height, width, 3) uint8 RGB
     * }
     * }</pre>
     *
     * <p>A step that ran but that the model reported as failed still completes normally, with
     * {@link StepResult#error()} saying why. The future fails with {@link StepRefusedException}
     * when the runtime turns the step down (nothing it carried was applied), {@link
     * StepNotReadyException} when the session cannot take a step yet (the step did not run), {@link
     * StepCommandFailedException} when one of its state keys or commands failed (the step did not
     * run; the calls before it stay applied), {@link StepSessionEndedException} when the session
     * ended before its result came (whether it ran is unknown), {@link StepResultTooLargeException}
     * when the step ran but its output does not fit in one message, {@link MessageTooLargeException}
     * when the request does not (nothing was sent), {@link UnsupportedFrameEncodingException} for a
     * frame this SDK cannot decode, {@link RequestTimeoutException} after {@link
     * ReactorOptions#stepTimeoutSeconds()}, {@link BadRequestException} for a state that is not a
     * JSON object, and {@link InvalidStateException} outside a connected stepped session.
     *
     * <p>A step refused before it goes out ({@link InvalidStateException}, malformed arguments)
     * keeps the buffered input frames for the next step. Past that they belong to the step whatever
     * its outcome: one that could not be sent, {@link MessageTooLargeException} included, drops them
     * too, and nothing is resent. The buffer holds at most one data-channel message's
     * worth of frame bytes, so pushing faster than steps run drops the oldest frames, across tracks.
     * Once the session's tracks are known, a push to a track the session does not take as input is
     * dropped with a warning.
     *
     * @param state the model's state fields as a JSON object, or {@code null} to change none
     * @param commands what to run after the state, in order; empty or {@code null} for none
     * @return the step's result, copied onto the heap once and safe to keep
     * @see #step(JsonValue, java.util.List, java.util.Map) to give the step input frames of its own
     */
    public CompletableFuture<StepResult> step(
            @Nullable JsonValue state, java.util.@Nullable List<StepCommand> commands) {
        return step(state, commands, null);
    }

    /**
     * Runs one step with input frames of its own, by input track name.
     *
     * <p>Each track's frames go after the ones pushed to that track since the previous step, in
     * list order: an {@link EncodedFrame}, which goes as it is and is decoded by the runtime, or a
     * raw frame — {@link StepInput#video} or {@link StepInput#audio} — as {@link Track#pushFrame}
     * takes it. It is the same as pushing them just before the step, so each track must be
     * published first.
     *
     * <pre>{@code
     * List<EncodedFrame> frames = window.stream()     // keyframe first, in decode order
     *         .map(accessUnit -> EncodedFrame.h264(accessUnit, 1280, 720))
     *         .toList();
     * StepResult result = reactor.step(null, List.of(), Map.of("webcam", frames)).join();
     * }</pre>
     *
     * <p>The media goes all or nothing: every frame is checked before any is pushed, and none is
     * pushed until the state is known to be well formed and the session ready. So a step refused
     * for its state, a session not {@link ConnectionStatus#READY}, or a bad track or frame anywhere
     * in its media — an unknown or unpublished track, a raw frame the wrong size — fails before any
     * of the media is pushed, leaving the buffer as it was; a refused frame fails the future with
     * the push's own {@link ReactorException}, and no step is sent. A session that leaves READY
     * after the media went in is refused by the runtime's side, and the frames stay buffered for
     * the next step, as frames pushed with {@link Track#pushFrame} would.
     * Every other outcome is as for {@link #step(JsonValue, java.util.List)}, and a step whose
     * encoded frames do not decode, or do not start at a keyframe, fails with {@link
     * StepRefusedException}.
     *
     * @param state the model's state fields as a JSON object, or {@code null} to change none
     * @param commands what to run after the state, in order; empty or {@code null} for none
     * @param media input frames by track name, or {@code null} for none
     * @return the step's result, copied onto the heap once and safe to keep
     */
    public CompletableFuture<StepResult> step(
            @Nullable JsonValue state,
            java.util.@Nullable List<StepCommand> commands,
            java.util.@Nullable Map<String, ? extends java.util.List<? extends StepInput>> media) {
        if (commands == null) {
            commands = java.util.List.of();
        }
        if (state instanceof JsonValue.JsonNull) {
            state = null;
        }
        if (state != null && !(state instanceof JsonValue.JsonObject)) {
            return CompletableFuture.failedFuture(ReactorException.of(
                    ErrorCode.BAD_REQUEST.code(),
                    "a step's state must be a JSON object of state fields, or null; got " + state.toJsonString(),
                    null,
                    "step",
                    null));
        }
        String commandsJson = JsonValue.array(
                        commands.stream().map(StepCommand::toWire).toList())
                .toJsonString();
        // Written out here, before the media: a state JSON cannot carry (a NaN) fails the step
        // now, not after the frames are in the buffer to ride the next step.
        String stateJson;
        try {
            stateJson = state == null ? null : state.toJsonString();
        } catch (RuntimeException unwritable) {
            return CompletableFuture.failedFuture(unwritable);
        }
        // Until reactor_step has taken the buffer: later pushes are the next step's.
        synchronized (stepLock) {
            // Pushed only once the state and commands are known to be well formed, so a step
            // refused for those leaves the buffer as the caller left it.
            if (media != null && !media.isEmpty()) {
                try {
                    pushStepMedia(media);
                } catch (RuntimeException refused) {
                    return CompletableFuture.failedFuture(refused);
                }
            }
            return peer.step(stateJson, commandsJson);
        }
    }

    /** Pushes a step's media, track by track, as {@link #step(JsonValue, java.util.List, java.util.Map)} documents. */
    private void pushStepMedia(java.util.Map<String, ? extends java.util.List<? extends StepInput>> media) {
        if (peer.generation() != Generation.STEPPED) {
            // Checked here rather than left to peer.step: in a streamed session a raw push sends,
            // so the frames would go out live before the step was refused.
            throw ReactorException.of(
                    ErrorCode.INVALID_STATE.code(),
                    "step() needs a stepped session: create the client with"
                            + " ReactorOptions.builder(...).generation(Generation.STEPPED)",
                    null,
                    "step",
                    null);
        }
        ConnectionStatus status = peer.status();
        if (status != ConnectionStatus.READY) {
            // The core refuses a step outside READY and keeps what is buffered for the next one,
            // so the media must not go in first. A session leaving READY after this check still
            // can: then the frames ride the next step, as any push would.
            throw ReactorException.of(
                    ErrorCode.INVALID_STATE.code(),
                    "step() needs a ready session; it is " + status,
                    null,
                    "step",
                    null);
        }
        // Every track resolved and every frame checked before any is pushed, so a misspelt name or
        // a bad frame late in the media refuses the step with the buffer untouched. The native
        // buffer can only be cleared whole, so there is no taking back a push once made.
        java.util.List<Runnable> pushes = new java.util.ArrayList<>();
        media.forEach((name, frames) -> {
            Track track = track(name);
            for (StepInput frame : java.util.Objects.requireNonNull(frames, () -> "media[\"" + name + "\"]")) {
                pushes.add(track.prepare(java.util.Objects.requireNonNull(frame, "a step's media holds a null frame")));
            }
        });
        pushes.forEach(Runnable::run);
    }

    /**
     * Runs one step that sets state and runs no commands.
     *
     * @param state the model's state fields as a JSON object, or {@code null} to change none
     * @return the step's result
     * @see #step(JsonValue, java.util.List)
     */
    public CompletableFuture<StepResult> step(@Nullable JsonValue state) {
        return step(state, java.util.List.of());
    }

    /**
     * Runs one step with no state change and no commands — the buffered input frames, and whatever
     * the model does by itself.
     *
     * @return the step's result
     * @see #step(JsonValue, java.util.List)
     */
    public CompletableFuture<StepResult> step() {
        return step(null, java.util.List.of());
    }

    /**
     * Drops every input frame buffered for the next step, so that step carries only what is pushed
     * after this. A no-op on a streamed client.
     *
     * @throws ReactorException ({@code INVALID_STATE}) when the client is closed
     */
    public void clearStepInputs() {
        peer.clearStepInputs();
    }

    /**
     * Uploads a file for a later command.
     *
     * <p>The path crosses the boundary rather than the bytes, so the file's size is the platform's
     * business and not the heap's.
     *
     * @param path the file
     * @return the reference to pass into a command
     */
    public CompletableFuture<FileRef> uploadFile(java.nio.file.Path path) {
        return peer.uploadFile(path);
    }

    /**
     * Uploads bytes already in memory.
     *
     * @param data the bytes
     * @param name what to call it
     * @param mimeType what it is
     * @return the reference to pass into a command
     */
    public CompletableFuture<FileRef> uploadBytes(byte[] data, String name, String mimeType) {
        return peer.uploadBytes(data, name, mimeType);
    }

    /**
     * Asks the model what commands it accepts and what they take.
     *
     * @return the schema, as the model declares it
     */
    public CompletableFuture<JsonValue> requestSchema() {
        return peer.requestSchema();
    }

    /**
     * Reads the connection.
     *
     * @return what the platform reported
     */
    public CompletableFuture<Stats> getStats() {
        return peer.getStats();
    }

    /**
     * Asks for a clip of the last {@code durationSeconds} of this session.
     *
     * <p>The clip is not ready when this settles. See {@link Clip} for why waiting on a wall clock
     * is the wrong instinct.
     *
     * @param durationSeconds how far back the window reaches
     * @return the clip
     */
    public CompletableFuture<Clip> requestClip(double durationSeconds) {
        return peer.requestClip(durationSeconds);
    }

    /**
     * Starts recording the whole session.
     *
     * @return the recording
     */
    public CompletableFuture<Clip> requestRecording() {
        return peer.requestRecording();
    }

    /**
     * Downloads a clip into one playable file, waiting for it to become ready.
     *
     * <p><b>This outlives the client.</b> Closing the client settles this future with an error
     * saying so, but does not cancel the download — the file may still arrive.
     *
     * @param clip what to download
     * @param outPath the file to write
     * @param progress told how many segments have been written, or {@code null}
     * @return the assembled file
     */
    public CompletableFuture<DownloadedClip> downloadClip(
            Clip clip, java.nio.file.Path outPath, @Nullable ClipProgress progress) {
        // Negative: wait as long as the session lives. A model generating slower than real time
        // reaches the boundary chunk later than any fixed number would allow for, and once the
        // session is gone a "not ready" is a "not ready" forever.
        return downloadClip(clip, outPath, -1, progress);
    }

    /**
     * Downloads a clip, giving up if it is not ready within a bound.
     *
     * @param clip what to download
     * @param outPath the file to write
     * @param readyTimeoutSeconds how long to wait past the clip's own prediction — measured from
     *     there, not from now. Negative or infinite waits as long as the session lives
     * @param progress told how many segments have been written, or {@code null}
     * @return the assembled file
     */
    public CompletableFuture<DownloadedClip> downloadClip(
            Clip clip, java.nio.file.Path outPath, double readyTimeoutSeconds, @Nullable ClipProgress progress) {
        return peer.downloadClip(
                clip,
                options.jwt(),
                outPath,
                readyTimeoutSeconds,
                options.local(),
                progress == null ? null : progress::report);
    }

    /**
     * The engine's own clock.
     *
     * <p>Capture times handed to {@link Track#pushFrame(byte[], int, int, byte[], Long)} are
     * compared against this, not against {@link System#currentTimeMillis()}.
     *
     * @return microseconds on the engine clock
     */
    public long timeMicros() {
        return peer.timeMicros();
    }

    /**
     * Bounds the connection's bitrate.
     *
     * @param minBps lower bound, or a negative value for none
     * @param startBps where to start, or a negative value for none
     * @param maxBps upper bound, or a negative value for none
     * @return settles when the bounds are applied
     */
    public CompletableFuture<Void> setBitrate(int minBps, int startBps, int maxBps) {
        return peer.setBitrate(minBps, startBps, maxBps);
    }

    /**
     * Destroys the client.
     *
     * <p>Idempotent, safe from an event handler, and it settles every operation still waiting —
     * leaving one out would leave its caller waiting for the life of the process.
     */
    @Override
    public void close() {
        cleanable.clean();
    }

    /** @return whether this client has been closed */
    public boolean isClosed() {
        return peer.isClosed();
    }
}
