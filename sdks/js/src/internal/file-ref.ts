import { FileRef } from '../file-ref';
import type { FileRef as WireFileRef } from './reactor-wasm.types';

/** camelCase → the wasm binding's own snake_case wire shape. */
function toWireFileRef(fileRef: FileRef): WireFileRef {
  return {
    upload_id: fileRef.uploadId,
    name: fileRef.name,
    mime_type: fileRef.mimeType,
    size: fileRef.size,
  };
}

/** The wasm binding's snake_case wire shape → the public `FileRef` — what
 *  `Reactor.uploadFile()` actually hands back to a caller. */
export function toPublicFileRef(fileRef: WireFileRef): FileRef {
  return new FileRef(fileRef.upload_id, fileRef.name, fileRef.mime_type, fileRef.size);
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
    if (!(value instanceof FileRef)) {
      continue;
    }
    uploads ??= {};
    scalars ??= { ...data };
    uploads[key] = toWireFileRef(value);
    delete scalars[key];
  }

  return { data: scalars ?? data, uploads };
}
