import { describe, expect, it } from 'vitest';
import { extractFileRefs } from './file-ref';
import type { FileRef } from './reactor-wasm.types';

const fileRef: FileRef = {
  upload_id: 'up_1',
  name: 'photo.jpg',
  mime_type: 'image/jpeg',
  size: 1024,
};

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

  it('extracts a single top-level FileRef into uploads, leaving scalars in data', () => {
    const result = extractFileRefs({ image: fileRef, caption: 'a cat' });
    expect(result.uploads).toEqual({ image: fileRef });
    expect(result.data).toEqual({ caption: 'a cat' });
  });

  it('extracts multiple top-level FileRefs from a mixed payload', () => {
    const other: FileRef = { ...fileRef, upload_id: 'up_2', name: 'b.png' };
    const result = extractFileRefs({ front: fileRef, back: other, label: 'id card' });
    expect(result.uploads).toEqual({ front: fileRef, back: other });
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
    const almost = { upload_id: 'up_1', name: 'photo.jpg', mime_type: 'image/jpeg' };
    const result = extractFileRefs({ image: almost });
    expect(result.uploads).toBeUndefined();
    expect(result.data).toEqual({ image: almost });
  });

  it('does not mutate the original data object', () => {
    const data = { image: fileRef, caption: 'a cat' };
    extractFileRefs(data);
    expect(data).toEqual({ image: fileRef, caption: 'a cat' });
  });
});
