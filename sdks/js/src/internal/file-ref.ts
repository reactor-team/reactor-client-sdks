import type { FileRef } from '../types';
import type { FileRef as WireFileRef } from './reactor-wasm.types';

/** Structural check — `FileRef` is a plain object on the public boundary,
 *  not a class, so there is no `instanceof` to lean on the way the Python
 *  SDK does with its dataclass. */
function isFileRef(value: unknown): value is FileRef {
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

/** camelCase → the wasm binding's own snake_case wire shape. */
function toWireFileRef(fileRef: FileRef): WireFileRef {
  return {
    upload_id: fileRef.uploadId,
    name: fileRef.name,
    mime_type: fileRef.mimeType,
    size: fileRef.size,
  };
}

/** The wasm binding's snake_case wire shape → the public, camelCase one —
 *  what `Reactor.uploadFile()` actually hands back to a caller. */
export function toPublicFileRef(fileRef: WireFileRef): FileRef {
  return {
    uploadId: fileRef.upload_id,
    name: fileRef.name,
    mimeType: fileRef.mime_type,
    size: fileRef.size,
  };
}

/**
 * Splits `data` into scalar command args and `FileRef` uploads: callers pass
 * a `FileRef` (from `uploadFile`) inline as a normal field and never build
 * the `uploads` map by hand. Each matched `FileRef` is translated to the
 * wasm binding's wire shape here, since that's what `sendCommand`'s
 * `uploads` argument actually expects.
 *
 * Only top-level values are inspected — a `FileRef` nested inside a list or
 * another object is left in place for the wire to reject, same as the Python
 * SDK's `send_command`.
 */
export function extractFileRefs(data: Record<string, unknown> | undefined): {
  data: Record<string, unknown> | undefined;
  uploads: Record<string, WireFileRef> | undefined;
} {
  if (!data) {
    return { data, uploads: undefined };
  }

  let uploads: Record<string, WireFileRef> | undefined;
  let scalars: Record<string, unknown> | undefined;

  for (const [key, value] of Object.entries(data)) {
    if (!isFileRef(value)) {
      continue;
    }
    uploads ??= {};
    scalars ??= { ...data };
    uploads[key] = toWireFileRef(value);
    delete scalars[key];
  }

  return { data: scalars ?? data, uploads };
}
