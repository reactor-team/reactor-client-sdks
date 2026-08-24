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

  it('does not detect a FileRef nested inside another object', () => {
    const result = extractFileRefs({ wrapper: { image: fileRef } });

    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({ wrapper: { image: fileRef } });
  });

  it('does not detect a FileRef nested inside an array', () => {
    const result = extractFileRefs({ images: [fileRef] });

    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({ images: [fileRef] });
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
