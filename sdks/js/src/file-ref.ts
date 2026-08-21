/**
 * Reference to a file uploaded via `Reactor.uploadFile()`. Pass it back as a
 * top-level value in `sendCommand()`'s `data` and it's extracted and sent as
 * a separate upload reference rather than embedded in the JSON payload —
 * see `extractFileRefs()`.
 */
export class FileRef {
  constructor(
    public readonly uploadId: string,
    public readonly name: string,
    public readonly mimeType: string,
    public readonly size: number,
  ) {}
}

/**
 * Structural check for anything shaped like a `FileRef`, for callers who'd
 * rather duck-type than rely on `instanceof` (e.g. across two copies of this
 * package bundled into the same page). `extractFileRefs()` itself checks
 * `instanceof FileRef` directly, since it only ever sees values this
 * package produced.
 */
export function isFileRef(value: unknown): value is FileRef {
  if (value instanceof FileRef) {
    return true;
  }
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const candidate = value as Partial<FileRef>;

  return (
    typeof candidate.uploadId === 'string' &&
    typeof candidate.name === 'string' &&
    typeof candidate.mimeType === 'string' &&
    typeof candidate.size === 'number'
  );
}
