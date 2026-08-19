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

  async disconnect(): Promise<void> {
    this.assertNotDisposed();
    if (!this.client) return;
    await this.client.disconnect();
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
   * Release the wasm resource graph: cancels the pump/dispatcher/heartbeat
   * tasks and closes the peer connection. Distinct from `disconnect()`,
   * which ends the session server-side — see the class docs.
   *
   * A no-op if `connect()`/`reconnect()` was never called: there is no wasm
   * client to free.
   */
  dispose(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.client?.free();
    this.client = undefined;
    this.clientPromise = undefined;
    this.emitter.clear();
  }

  [Symbol.dispose](): void {
    this.dispose();
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
    // dispose() may have run while the wasm module was loading above; bail
    // out before constructing a client that would otherwise outlive it and
    // never get freed.
    if (this.disposed) {
      throw new Error("Reactor.dispose() was called while connecting.");
    }
    const client = new WasmReactorClient(this.clientOptions, this.pendingJwt);
    client.onStatusChanged((status) => this.emitter.emit("statusChanged", status));
    client.onSessionIdChanged((sessionId) => this.emitter.emit("sessionIdChanged", sessionId));
    client.onError((error) => this.emitter.emit("error", error));
    this.client = client;
    return client;
  }

  private assertNotDisposed(): void {
    if (this.disposed) {
      throw new Error("Reactor.dispose() was already called on this instance.");
    }
  }
}
