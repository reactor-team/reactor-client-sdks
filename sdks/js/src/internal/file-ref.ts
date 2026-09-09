import { FileRef, isFileRef } from '../file-ref';
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
 * A top-level `FileRef` moves into `uploads`, keyed by its parameter name —
 * the one slot the wire has for a named upload. A `FileRef` nested inside an
 * array or an object has no such slot, so it stays in `data` and is rewritten
 * to the wire shape in place: the binding serializes `data` by reading each
 * object's own enumerable properties, so a `FileRef` left as-is would cross
 * the wire with its camelCase field names, which the model side does not
 * read. The walk follows the same rule the binding does — every array
 * element and every own enumerable property of every object, whatever its
 * prototype — so a `FileRef` is found wherever the binding would have
 * serialized it. A `Date` or a `Blob` has no enumerable properties and passes
 * through; a typed array is bytes and is skipped outright.
 *
 * Uses `isFileRef()`'s structural check rather than `instanceof FileRef`:
 * a duplicate copy of this package (e.g. two versions bundled into the same
 * page) produces a `FileRef` that fails `instanceof` against this module's
 * class but still type-checks and looks identical on the wire — routing it
 * through `data` instead of `uploads` would otherwise make the wire reject
 * an otherwise-valid command.
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
    if (isFileRef(value)) {
      uploads ??= {};
      scalars ??= { ...data };
      uploads[key] = toWireFileRef(value);
      delete scalars[key];
      continue;
    }
    const rewritten = rewriteNestedFileRefs(value);

    if (rewritten !== value) {
      scalars ??= { ...data };
      scalars[key] = rewritten;
    }
  }

  return { data: scalars ?? data, uploads };
}

/**
 * Returns `value` with every `FileRef` inside it replaced by its wire shape,
 * or `value` itself when it contains none, so an untouched payload keeps its
 * identity.
 */
function rewriteNestedFileRefs(value: unknown): unknown {
  if (isFileRef(value)) {
    return toWireFileRef(value);
  }
  if (Array.isArray(value)) {
    const elements: unknown[] = value;
    let copy: unknown[] | undefined;

    elements.forEach((element, index) => {
      const rewritten = rewriteNestedFileRefs(element);

      if (rewritten !== element) {
        copy ??= [...elements];
        copy[index] = rewritten;
      }
    });
    return copy ?? elements;
  }
  if (isSerializedAsObject(value)) {
    let copy: Record<string, unknown> | undefined;

    // A copy spreads the own enumerable properties, which is exactly the set
    // the binding would have serialized off the original, so a class instance
    // that held a `FileRef` reaches the wire the same as before, minus the
    // camelCase reference.
    for (const [key, element] of Object.entries(value)) {
      const rewritten = rewriteNestedFileRefs(element);

      if (rewritten !== element) {
        copy ??= { ...value };
        copy[key] = rewritten;
      }
    }
    return copy ?? value;
  }
  return value;
}

/** Whether the binding serializes `value` as an object of its own enumerable
 *  properties — anything object-like except a typed array, which it serializes
 *  as bytes and which could hold no `FileRef` anyway. */
function isSerializedAsObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !ArrayBuffer.isView(value);
}
