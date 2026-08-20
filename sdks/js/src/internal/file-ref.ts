import type { FileRef } from './reactor-wasm.types';

/** Structural check — `FileRef` is a plain object on the wasm boundary (see
 *  `reactor-wasm.types.ts`'s header comment), not a class, so there is no
 *  `instanceof` to lean on the way the Python SDK does with its dataclass. */
function isFileRef(value: unknown): value is FileRef {
  if (typeof value !== 'object' || value === null) return false;
  const candidate = value as Partial<FileRef>;
  return (
    typeof candidate.upload_id === 'string' &&
    typeof candidate.name === 'string' &&
    typeof candidate.mime_type === 'string' &&
    typeof candidate.size === 'number'
  );
}

/**
 * Splits `data` into scalar command args and `FileRef` uploads, matching v2's
 * ergonomics: callers pass a `FileRef` (from `uploadFile`) inline as a normal
 * field and never build the `uploads` map by hand.
 *
 * Only top-level values are inspected — a `FileRef` nested inside a list or
 * another object is left in place for the wire to reject, same as the Python
 * SDK's `send_command`.
 */
export function extractFileRefs(data: Record<string, unknown> | undefined): {
  data: Record<string, unknown> | undefined;
  uploads: Record<string, FileRef> | undefined;
} {
  if (!data) return { data, uploads: undefined };

  let uploads: Record<string, FileRef> | undefined;
  let scalars: Record<string, unknown> | undefined;

  for (const [key, value] of Object.entries(data)) {
    if (!isFileRef(value)) continue;
    uploads ??= {};
    scalars ??= { ...data };
    uploads[key] = value;
    delete scalars[key];
  }

  return { data: scalars ?? data, uploads };
}
