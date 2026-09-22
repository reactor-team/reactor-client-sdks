package inc.reactor.sdk;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a client needs to be created.
 *
 * <p>There is no option for the audio device module, and that is the point. The FFI's plain
 * {@code reactor_create} takes its mode from an environment variable, and under the platform module
 * a model declaring a sendonly audio track is enough to put a live microphone on the wire. This SDK
 * always asks for the synthetic module, so nothing in the environment can open a device. Real
 * devices are an explicit, separate dependency.
 */
public final class ReactorOptions {

    private final String apiUrl;
    private final String modelName;
    private final @Nullable String jwt;
    private final @Nullable String apiKey;
    private final boolean local;
    private final @Nullable Dispatcher dispatcher;

    private ReactorOptions(Builder builder) {
        this.apiUrl = builder.apiUrl;
        this.modelName = builder.modelName;
        this.jwt = builder.jwt;
        this.apiKey = builder.apiKey;
        this.local = builder.local;
        this.dispatcher = builder.dispatcher;
    }

    /**
     * @param apiUrl the coordinator's base URL, e.g. {@code https://api.reactor.inc}
     * @param modelName the model, owner-qualified: {@code owner/name}. A bare name resolves under
     *     {@code reactor/}, which works by luck of ownership and answers 403 for anyone else's model
     * @return a builder
     */
    public static Builder builder(String apiUrl, String modelName) {
        return new Builder(apiUrl, modelName);
    }

    /** @return the coordinator's base URL */
    public String apiUrl() {
        return apiUrl;
    }

    /** @return the model to connect to */
    public String modelName() {
        return modelName;
    }

    /** @return the JWT, or {@code null} for an unauthenticated local-dev connection */
    public @Nullable String jwt() {
        return jwt;
    }

    /** @return the API key to exchange for a token at connect time, or {@code null} */
    public @Nullable String apiKey() {
        return apiKey;
    }

    /** @return whether to accept a local dev coordinator's self-signed certificate */
    public boolean local() {
        return local;
    }

    /** @return where control events are delivered, or {@code null} for the client's own thread */
    public @Nullable Dispatcher dispatcher() {
        return dispatcher;
    }

    /** Builds {@link ReactorOptions}. */
    public static final class Builder {

        private final String apiUrl;
        private final String modelName;
        private @Nullable String jwt;
        private @Nullable String apiKey;
        private boolean local;
        private @Nullable Dispatcher dispatcher;

        private Builder(String apiUrl, String modelName) {
            this.apiUrl = Objects.requireNonNull(apiUrl, "apiUrl");
            this.modelName = Objects.requireNonNull(modelName, "modelName");
        }

        /**
         * @param jwt the token, from {@code Reactor.fetchJwt} or from your own backend
         * @return this builder
         */
        public Builder jwt(@Nullable String jwt) {
            this.jwt = jwt;
            return this;
        }

        /**
         * An API key the client exchanges for a token itself, on connect.
         *
         * <p>Use this or {@link #jwt}, not both — a token you supplied is yours, and wins. The
         * token this mints is scoped to {@code modelName}, so a leak is worth sessions on that
         * model rather than everything the key can reach. Adopting a session someone else created
         * needs a broader token, and {@code connect(sessionId, ...)} mints one, because a scoped
         * token cannot reach a session it did not create.
         *
         * <p>A key belongs on a machine you control. Handing one to a client you do not is what
         * {@link Reactor#fetchJwt} and a backend of your own are for.
         *
         * @param apiKey the key, or {@code null}
         * @return this builder
         */
        public Builder apiKey(@Nullable String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * @param local whether to accept a local dev coordinator's self-signed certificate
         * @return this builder
         */
        public Builder local(boolean local) {
            this.local = local;
            return this;
        }

        /**
         * @param dispatcher where control events are delivered — {@code SwingUtilities::invokeLater},
         *     {@code Platform::runLater}, or your own. Defaults to a single thread the client owns
         * @return this builder
         */
        public Builder dispatcher(@Nullable Dispatcher dispatcher) {
            this.dispatcher = dispatcher;
            return this;
        }

        /** @return the options */
        public ReactorOptions build() {
            return new ReactorOptions(this);
        }
    }
}
