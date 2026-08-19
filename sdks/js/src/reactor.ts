import { Emitter } from "./internal/emitter";
import type { ReactorClient } from "./internal/reactor-wasm.types";
import { loadReactorWasm } from "./internal/wasm";
import type {
  ConnectOptions,
  JwtSource,
  ReactorEventMap,
  ReactorOptions,
  ReactorStatus,
} from "./types";

/**
 * A live connection to a Reactor model.
 */
export class Reactor implements Disposable {
  private readonly clientOptions: Omit<ReactorOptions, "jwt">;
  private pendingJwt: JwtSource | null | undefined;
  private client: ReactorClient | undefined;
  private clientPromise: Promise<ReactorClient> | undefined;
  private disposed = false;

  private readonly emitter = new Emitter<ReactorEventMap>();

  constructor(options: ReactorOptions) {
    const { jwt, ...clientOptions } = options;
    this.clientOptions = clientOptions;
    this.pendingJwt = jwt ?? null;
  }

  // ── Lifecycle ───────────────────────────────────────────────────────────

  async connect(options?: ConnectOptions): Promise<void> {
    this.assertNotDisposed();
    const client = await this.getOrCreateClient();
    await client.connect(options);
  }

  /**
   * Ends the connection. Matches v2's `disconnect(recoverable)`: by default
   * (`recoverable = false`) this also frees the wasm resource graph — the
   * pump/dispatcher/heartbeat tasks and the peer connection — same as the
   * old standalone `dispose()`. Pass `recoverable: true` to keep the wasm
   * client alive so a later `connect()`/`reconnect()` doesn't have to
   * reload wasm and reconstruct it from scratch.
   *
   * Note this only governs the *local* resource graph — the binding's own
   * `disconnect()` always ends the session server-side regardless of this
   * flag, so `recoverable` isn't a way to keep the session itself alive.
   */
  async disconnect(recoverable = false): Promise<void> {
    this.assertNotDisposed();
    if (this.client) {
      await this.client.disconnect();
    }
    if (!recoverable) {
      this.freeClient();
    }
  }

  async reconnect(): Promise<void> {
    this.assertNotDisposed();
    const client = await this.getOrCreateClient();
    await client.reconnect();
  }

  setJwt(jwt?: JwtSource | null): Promise<void> {
    this.assertNotDisposed();
    this.pendingJwt = jwt ?? null;
    this.client?.setJwt(this.pendingJwt);
    // The wasm binding's setJwt is synchronous; this stays `Promise<void>`
    // to match connect/disconnect/reconnect and leave room for a future
    // binding change without breaking callers who already `await` it.
    return Promise.resolve();
  }

  /**
   * Supports `using reactor = new Reactor(...)`: releases the wasm resource
   * graph and drops every registered event handler for good. Unlike a plain
   * `disconnect()`, this instance is unusable afterward — construct a new
   * `Reactor` instead of trying to `connect()` again.
   */
  [Symbol.dispose](): void {
    if (this.disposed) return;
    this.disposed = true;
    this.freeClient();
    this.emitter.clear();
  }

  // ── Introspection ───────────────────────────────────────────────────────

  /** Matches v2's `getStatus()`. The wasm binding's own terser `status()`
   *  name can be added alongside this later if it turns out to be worth it. */
  getStatus(): ReactorStatus {
    return this.client?.status() ?? "disconnected";
  }

  /** Matches v2's `getSessionId()`. See `getStatus()`. */
  getSessionId(): string | undefined {
    return this.client?.sessionId();
  }

  // ── Events ──────────────────────────────────────────────────────────────

  on<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.on(event, handler);
  }

  off<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.off(event, handler);
  }

  once<Name extends keyof ReactorEventMap>(event: Name, handler: ReactorEventMap[Name]): void {
    this.emitter.once(event, handler);
  }

  // ── Internal ────────────────────────────────────────────────────────────

  private getOrCreateClient(): Promise<ReactorClient> {
    if (!this.clientPromise) {
      this.clientPromise = this.createClient().catch((cause) => {
        // A failed first connect shouldn't wedge every later attempt behind
        // a client that never came up.
        this.clientPromise = undefined;
        throw cause;
      });
    }
    return this.clientPromise;
  }

  private async createClient(): Promise<ReactorClient> {
    const { ReactorClient: WasmReactorClient } = await loadReactorWasm();
    // [Symbol.dispose] may have run while the wasm module was loading above;
    // bail out before constructing a client that would otherwise outlive it
    // and never get freed.
    if (this.disposed) {
      throw new Error("Reactor was disposed while connecting.");
    }
    const client = new WasmReactorClient(this.clientOptions, this.pendingJwt);
    client.onStatusChanged((status) => this.emitter.emit("statusChanged", status));
    client.onSessionIdChanged((sessionId) => this.emitter.emit("sessionIdChanged", sessionId));
    client.onError((error) => this.emitter.emit("error", error));
    this.client = client;
    return client;
  }

  /** Frees the wasm resource graph, if one exists. Reusable — unlike
   *  `[Symbol.dispose]`, this doesn't set the permanent `disposed` flag, so
   *  a later `connect()`/`reconnect()` lazily builds a fresh client. */
  private freeClient(): void {
    this.client?.free();
    this.client = undefined;
    this.clientPromise = undefined;
  }

  private assertNotDisposed(): void {
    if (this.disposed) {
      throw new Error("This Reactor was disposed and can't be used again — construct a new one.");
    }
  }
}
