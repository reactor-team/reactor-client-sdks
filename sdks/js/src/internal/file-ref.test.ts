import { describe, expect, it } from 'vitest';
import { extractFileRefs, toPublicFileRef } from './file-ref';
import type { FileRef as WireFileRef } from './reactor-wasm.types';
import { FileRef } from '../file-ref';

const wireFileRef: WireFileRef = {
  upload_id: 'up_1',
  name: 'photo.jpg',
  mime_type: 'image/jpeg',
  size: 1024,
};

const fileRef: FileRef = toPublicFileRef(wireFileRef);

describe('toPublicFileRef', () => {
  it('translates the wasm binding\'s snake_case wire shape to camelCase', () => {
    expect(toPublicFileRef(wireFileRef)).toEqual({
      uploadId: 'up_1',
      name: 'photo.jpg',
      mimeType: 'image/jpeg',
      size: 1024,
    });
  });
});

describe('extractFileRefs', () => {
  it('passes through data with no FileRef values untouched', () => {
    const data = { prompt: 'hello', count: 3 };
    const result = extractFileRefs(data);

    expect(result.data).toBe(data);
    expect(result.uploads).toBeUndefined();
  });

  it('returns undefined data/uploads for undefined input', () => {
    expect(extractFileRefs(undefined)).toEqual({ data: undefined, uploads: undefined });
  });

  it('extracts a single top-level FileRef into uploads, translated to the wire shape', () => {
    const result = extractFileRefs({ image: fileRef, caption: 'a cat' });

    expect(result.uploads).toEqual({ image: wireFileRef });
    expect(result.data).toEqual({ caption: 'a cat' });
  });

  it('extracts multiple top-level FileRefs from a mixed payload', () => {
    const other = new FileRef('up_2', 'b.png', fileRef.mimeType, fileRef.size);
    const result = extractFileRefs({ front: fileRef, back: other, label: 'id card' });

    expect(result.uploads).toEqual({
      front: wireFileRef,
      back: { ...wireFileRef, upload_id: 'up_2', name: 'b.png' },
    });
    expect(result.data).toEqual({ label: 'id card' });
  });

  it('rewrites a FileRef nested inside an array to the wire shape, in place and in order', () => {
    const second = new FileRef('up_2', 'b.png', fileRef.mimeType, fileRef.size);
    const result = extractFileRefs({ images: [second, fileRef], caption: 'two cats' });

    // No named slot exists for an entry of a list, so it stays in `data`
    // rather than moving to `uploads`.
    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({
      images: [{ ...wireFileRef, upload_id: 'up_2', name: 'b.png' }, wireFileRef],
      caption: 'two cats',
    });
  });

  it('rewrites a FileRef nested inside a plain object to the wire shape', () => {
    const result = extractFileRefs({ cover: { image: fileRef, caption: 'front' } });

    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({ cover: { image: wireFileRef, caption: 'front' } });
  });

  it('rewrites FileRefs however deeply arrays and objects nest', () => {
    const result = extractFileRefs({ albums: [{ title: 'one', pages: [fileRef] }, { title: 'two' }] });

    expect(result.data).toEqual({
      albums: [{ title: 'one', pages: [wireFileRef] }, { title: 'two' }],
    });
  });

  it('extracts a top-level FileRef and rewrites a nested one in the same payload', () => {
    const page = new FileRef('up_2', 'page.png', fileRef.mimeType, fileRef.size);
    const result = extractFileRefs({ cover: fileRef, pages: [page] });

    expect(result.uploads).toEqual({ cover: wireFileRef });
    expect(result.data).toEqual({ pages: [{ ...wireFileRef, upload_id: 'up_2', name: 'page.png' }] });
  });

  it('keeps the identity of nested values that hold no FileRef', () => {
    const images = ['https://example.com/a.png'];
    const meta = { tags: ['cat'], count: 1 };
    const data = { images, meta, other: [fileRef] };

    const result = extractFileRefs(data);

    expect(result.data).not.toBe(data);
    expect((result.data as Record<string, unknown>).images).toBe(images);
    expect((result.data as Record<string, unknown>).meta).toBe(meta);
  });

  it('rewrites a FileRef held by a class instance, as the binding serializes its own fields', () => {
    class Holder {
      constructor(
        public readonly image: FileRef,
        public readonly caption: string,
      ) {}
      describe(): string {
        return this.caption;
      }
    }
    const holder = new Holder(fileRef, 'front');

    const result = extractFileRefs({ options: holder });

    // The binding would have serialized `holder` as `{ image, caption }`; the
    // rewrite hands it the same two fields with the reference in wire shape.
    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({ options: { image: wireFileRef, caption: 'front' } });
    expect(holder.image).toBe(fileRef);
  });

  it('passes through values with no enumerable properties and skips typed arrays', () => {
    const blob = new Blob(['x']);
    const when = new Date(0);
    const bytes = new Uint8Array([1, 2, 3]);
    const data = { blob, when, bytes, images: ['not a ref'] };

    const result = extractFileRefs(data);

    expect(result.uploads).toBeUndefined();
    expect(result.data).toBe(data);
  });

  it('does not treat a partial/shape-mismatched object as a FileRef', () => {
    const almost = { uploadId: 'up_1', name: 'photo.jpg', mimeType: 'image/jpeg' };
    const result = extractFileRefs({ image: almost });

    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({ image: almost });
  });

  it('extracts a full-shape plain object too, not only a real FileRef instance (e.g. a duplicate-package copy)', () => {
    const lookalike = { uploadId: 'up_1', name: 'photo.jpg', mimeType: 'image/jpeg', size: 1024 };
    const result = extractFileRefs({ image: lookalike });

    expect(result.uploads).toEqual({ image: wireFileRef });
    expect(result.data).toEqual({});
  });

  it('does not treat the wasm binding\'s own snake_case shape as a public FileRef', () => {
    const result = extractFileRefs({ image: wireFileRef });

    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({ image: wireFileRef });
  });

  it('does not mutate the original data object', () => {
    const data = { image: fileRef, caption: 'a cat' };

    extractFileRefs(data);
    expect(data).toEqual({ image: fileRef, caption: 'a cat' });
  });
});
