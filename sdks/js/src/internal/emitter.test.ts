import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Emitter } from './emitter';

type Events = {
  ping: (value: number) => void;
};

describe('Emitter', () => {
  let emitter: Emitter<Events>;

  beforeEach(() => {
    emitter = new Emitter<Events>();
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('delivers to every listener even when an earlier one throws', () => {
    const later = vi.fn();

    emitter.on('ping', () => {
      throw new Error('boom');
    });
    emitter.on('ping', later);

    emitter.emit('ping', 1);

    expect(later).toHaveBeenCalledWith(1);
  });

  it('logs a throwing handler instead of propagating it', () => {
    emitter.on('ping', () => {
      throw new Error('boom');
    });

    expect(() => emitter.emit('ping', 1)).not.toThrow();
    expect(console.error).toHaveBeenCalledWith(
      expect.stringContaining('"ping"'),
      expect.any(Error),
    );
  });
});
