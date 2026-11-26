import { blobToDataUri } from './blob-uri';

/** Answers `readAsDataURL` as the platform does, or fails, so the test needs no native module. */
class FakeFileReader {
  static outcome: { result: unknown } | { error: Error } = {
    result: 'data:image/jpeg;base64,AAAA',
  };

  result: unknown = null;
  error: Error | null = null;
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;

  readAsDataURL() {
    const { outcome } = FakeFileReader;
    queueMicrotask(() => {
      if ('error' in outcome) {
        this.error = outcome.error;
        this.onerror?.();
      } else {
        this.result = outcome.result;
        this.onload?.();
      }
    });
  }
}

const original = globalThis.FileReader;

beforeEach(() => {
  globalThis.FileReader = FakeFileReader as unknown as typeof FileReader;
  FakeFileReader.outcome = { result: 'data:image/jpeg;base64,AAAA' };
});

afterEach(() => {
  globalThis.FileReader = original;
});

describe('blobToDataUri', () => {
  it('resolves with the data URI the reader produces', async () => {
    await expect(blobToDataUri(new Blob(['x']))).resolves.toBe('data:image/jpeg;base64,AAAA');
  });

  it('rejects with the reader error when the file cannot be read', async () => {
    FakeFileReader.outcome = { error: new Error('unreadable') };

    await expect(blobToDataUri(new Blob(['x']))).rejects.toThrow('unreadable');
  });

  it('rejects when the reader does not produce text', async () => {
    FakeFileReader.outcome = { result: new ArrayBuffer(1) };

    await expect(blobToDataUri(new Blob(['x']))).rejects.toThrow('data URI');
  });
});
