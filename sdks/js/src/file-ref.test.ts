import { describe, expect, it } from 'vitest';
import { FileRef, isFileRef } from './file-ref';

describe('FileRef', () => {
  it('is a real class — instances pass instanceof', () => {
    const fileRef = new FileRef('up_1', 'photo.jpg', 'image/jpeg', 1024);

    expect(fileRef).toBeInstanceOf(FileRef);
    expect(fileRef).toEqual({ uploadId: 'up_1', name: 'photo.jpg', mimeType: 'image/jpeg', size: 1024 });
  });
});

describe('isFileRef', () => {
  it('accepts a real FileRef instance', () => {
    expect(isFileRef(new FileRef('up_1', 'photo.jpg', 'image/jpeg', 1024))).toBe(true);
  });

  it('accepts a plain object with the right shape (duck-typing)', () => {
    expect(isFileRef({ uploadId: 'up_1', name: 'photo.jpg', mimeType: 'image/jpeg', size: 1024 })).toBe(true);
  });

  it('rejects a partial/shape-mismatched object', () => {
    expect(isFileRef({ uploadId: 'up_1', name: 'photo.jpg' })).toBe(false);
  });

  it('rejects null and non-objects', () => {
    expect(isFileRef(null)).toBe(false);
    expect(isFileRef('up_1')).toBe(false);
    expect(isFileRef(undefined)).toBe(false);
  });
});
