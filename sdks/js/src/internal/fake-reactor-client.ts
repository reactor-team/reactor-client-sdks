import type {
  Capabilities as WireCapabilities,
  Clip as WireClip,
  ConnectOptions,
  FileRef,
  ReactorMessage,
  ReactorStatus,
  TrackCapability,
  TrackMappingEntry,
} from './reactor-wasm.types';

/** A fake `ReactorClient` binding for tests — mock `./internal/wasm`'s
 *  `loadReactorWasm` to resolve `{ ReactorClient: FakeReactorClient }`, then
 *  drive it through its `emit*`/`*Calls`/`*Impl` surface instead of a real
 *  wasm client. Shared by `reactor.test.ts` and the `src/react/` test suite. */
export class FakeReactorClient {
  static instances: FakeReactorClient[] = [];
  // Set by a test right before triggering the construction it's meant for
  // (a Reactor's first connect()), then consumed once — see connectImpl's
  // own doc below for why this is a static, not an instance field.
  static nextConnectImpl: (() => Promise<void>) | undefined;

  sendCommandCalls: Array<{
    command: string;
    data: Record<string, unknown> | undefined;
    uploads: Record<string, unknown> | undefined;
  }> = [];
  requestSchemaCalls = 0;
  schemaResult: unknown = { commands: ['set_image'] };
  schemaError: Error | undefined;
  // Overridable so a test can hold requestSchema() open, to simulate a
  // stale reply landing after the client was replaced or superseded by a
  // newer refresh.
  requestSchemaImpl: (() => Promise<unknown>) | undefined;
  // The real binding's serializer hands back `null`, not `undefined`, for
  // a bodyless ack — despite its own typed signature saying otherwise. See
  // `Reactor.sendCommand()`'s normalization.
  sendCommandReply: ReactorMessage | null = { type: 'ack', data: null };

  private statusListener: ((status: ReactorStatus) => void) | undefined;
  private sessionIdListener: ((sessionId: string | undefined) => void) | undefined;
  private messageListener: ((message: ReactorMessage) => void) | undefined;
  private runtimeMessageListener: ((message: ReactorMessage) => void) | undefined;
  private errorListener: ((error: unknown) => void) | undefined;

  constructor(
    readonly options: unknown,
    readonly jwt: unknown,
  ) {
    FakeReactorClient.instances.push(this);
    // Reactor.connect() now guards against a second call while not
    // "disconnected", so a test can no longer get a handle on an
    // already-constructed client and *then* make its (second) connect()
    // hang — it has to arrange that before the first, only, connect() call
    // reaches this constructor.
    this.connectImpl = FakeReactorClient.nextConnectImpl;
    FakeReactorClient.nextConnectImpl = undefined;
  }

  // Overridable (directly, or via the static above) so a test can hold
  // connect() open — e.g. `() => someDeferred.promise` — to simulate
  // disconnect() racing an in-flight connect().
  connectImpl: (() => Promise<void>) | undefined;
  connectCalls: Array<ConnectOptions | undefined> = [];
  disconnectCalls = 0;
  reconnectCalls: unknown[] = [];
  freeCalls = 0;
  private _status: ReactorStatus = 'disconnected';

  publishTrackCalls: Array<{ name: string; track: MediaStreamTrack }> = [];
  unpublishTrackCalls: string[] = [];
  pauseTrackCalls: string[] = [];
  resumeTrackCalls: string[] = [];
  tracksResult: TrackCapability[] = [];
  trackMappingResult: TrackMappingEntry[] = [];
  pausedTracksResult: string[] = [];
  peerConnectionResult: RTCPeerConnection | undefined;
  trackByMidResult: MediaStreamTrack | undefined;
  streamByMidResult: MediaStream | undefined;
  trackByNameResult: MediaStreamTrack | undefined;
  streamByNameResult: MediaStream | undefined;

  private trackReceivedListener: ((name: string, mid: string | undefined) => void) | undefined;
  private capabilitiesReceivedListener: ((capabilities: WireCapabilities) => void) | undefined;
  capabilitiesResult: WireCapabilities | undefined;

  emitError(error: unknown): void {
    this.errorListener?.(error);
  }

  setJwtCalls: unknown[] = [];
  setJwt(jwt?: unknown): void {
    this.setJwtCalls.push(jwt);
  }
  async connect(options?: ConnectOptions): Promise<void> {
    this.connectCalls.push(options);
    if (this.connectImpl) {
      await this.connectImpl();
    }
    this._status = 'ready';
  }
  disconnect(): Promise<void> {
    this.disconnectCalls += 1;
    this._status = 'disconnected';
    return Promise.resolve();
  }
  reconnect(options?: unknown): Promise<void> {
    this.reconnectCalls.push(options);
    this._status = 'ready';
    return Promise.resolve();
  }
  free(): void {
    this.freeCalls += 1;
  }
  status(): ReactorStatus {
    return this._status;
  }
  sessionId(): string | undefined {
    return undefined;
  }

  sendCommand(
    command: string,
    data: Record<string, unknown> | undefined,
    uploads: Record<string, unknown> | undefined,
  ): Promise<ReactorMessage | undefined> {
    this.sendCommandCalls.push({ command, data, uploads });
    return Promise.resolve(this.sendCommandReply as ReactorMessage | undefined);
  }

  requestSchema(): Promise<unknown> {
    this.requestSchemaCalls += 1;
    if (this.requestSchemaImpl) {
      return this.requestSchemaImpl();
    }
    if (this.schemaError) {
      return Promise.reject(this.schemaError);
    }
    return Promise.resolve(this.schemaResult);
  }

  requestClipCalls: number[] = [];
  requestClipResult: WireClip = {
    session_id: 'sess_1',
    kind: 'snap',
    start_marker: 0,
    end_marker: 10,
    now_marker: 10,
    predicted_ready_at_ms: 0,
    playlist_url: 'https://api.reactor.test/clips?session_id=sess_1',
  };
  requestClip(durationSeconds: number): Promise<WireClip> {
    this.requestClipCalls.push(durationSeconds);
    return Promise.resolve(this.requestClipResult);
  }

  requestRecordingCalls = 0;
  requestRecordingResult: WireClip = {
    session_id: 'sess_1',
    kind: 'recording',
    start_marker: 0,
    end_marker: 10,
    now_marker: 10,
    predicted_ready_at_ms: 0,
    playlist_url: 'https://api.reactor.test/clips?session_id=sess_1',
  };
  requestRecording(): Promise<WireClip> {
    this.requestRecordingCalls += 1;
    return Promise.resolve(this.requestRecordingResult);
  }

  onStatusChanged(listener: (status: ReactorStatus) => void): void {
    this.statusListener = listener;
  }
  onSessionIdChanged(listener: (sessionId: string | undefined) => void): void {
    this.sessionIdListener = listener;
  }
  onError(listener: (error: unknown) => void): void {
    this.errorListener = listener;
  }
  onMessage(listener: (message: ReactorMessage) => void): void {
    this.messageListener = listener;
  }
  onRuntimeMessage(listener: (message: ReactorMessage) => void): void {
    this.runtimeMessageListener = listener;
  }
  onTrackReceived(listener: (name: string, mid: string | undefined) => void): void {
    this.trackReceivedListener = listener;
  }
  onCapabilitiesReceived(listener: (capabilities: WireCapabilities) => void): void {
    this.capabilitiesReceivedListener = listener;
  }

  capabilities(): WireCapabilities | undefined {
    return this.capabilitiesResult;
  }

  emitReady(): void {
    this.statusListener?.('ready');
  }
  emitConnecting(): void {
    this.statusListener?.('connecting');
  }
  emitWaiting(): void {
    this.statusListener?.('waiting');
  }
  emitDisconnected(): void {
    this.statusListener?.('disconnected');
  }
  emitSessionIdChanged(sessionId: string | undefined): void {
    this.sessionIdListener?.(sessionId);
  }
  emitMessage(message: ReactorMessage): void {
    this.messageListener?.(message);
  }
  emitRuntimeMessage(message: ReactorMessage): void {
    this.runtimeMessageListener?.(message);
  }
  emitTrackReceived(name: string, mid: string | undefined): void {
    this.trackReceivedListener?.(name, mid);
  }
  emitCapabilitiesReceived(capabilities: WireCapabilities): void {
    this.capabilitiesReceivedListener?.(capabilities);
  }

  publishTrack(name: string, track: MediaStreamTrack): Promise<void> {
    this.publishTrackCalls.push({ name, track });
    return Promise.resolve();
  }
  unpublishTrack(name: string): Promise<void> {
    this.unpublishTrackCalls.push(name);
    return Promise.resolve();
  }
  pauseTrack(name: string): Promise<void> {
    this.pauseTrackCalls.push(name);
    return Promise.resolve();
  }
  resumeTrack(name: string): Promise<void> {
    this.resumeTrackCalls.push(name);
    return Promise.resolve();
  }
  tracks(): TrackCapability[] {
    return this.tracksResult;
  }
  trackMapping(): TrackMappingEntry[] {
    return this.trackMappingResult;
  }
  pausedTracks(): string[] {
    return this.pausedTracksResult;
  }
  getPeerConnection(): RTCPeerConnection | undefined {
    return this.peerConnectionResult;
  }
  getTrackByMid(): MediaStreamTrack | undefined {
    return this.trackByMidResult;
  }
  getStreamByMid(): MediaStream | undefined {
    return this.streamByMidResult;
  }
  getTrackByName(): MediaStreamTrack | undefined {
    return this.trackByNameResult;
  }
  getStreamByName(): MediaStream | undefined {
    return this.streamByNameResult;
  }

  uploadFileCalls: Array<{ file: Blob; name: string | undefined }> = [];
  uploadFileResult: FileRef = {
    upload_id: 'up_1',
    name: 'upload',
    mime_type: 'application/octet-stream',
    size: 0,
  };
  uploadFile(file: Blob, name: string | undefined): Promise<FileRef> {
    this.uploadFileCalls.push({ file, name });
    return Promise.resolve(this.uploadFileResult);
  }
}
