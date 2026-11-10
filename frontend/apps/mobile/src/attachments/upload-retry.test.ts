import type { ApiResult } from '@urbanpulse/api-client';

import { failed, ok } from '../test-utils';
import { isTransient, withRetries } from './upload-retry';

const NO_WAIT = [0, 0];

function attempts(...results: ApiResult<string>[]) {
  const attempt = jest.fn<Promise<ApiResult<string>>, []>();
  for (const result of results) {
    attempt.mockResolvedValueOnce(result);
  }
  return attempt;
}

describe('withRetries', () => {
  it('returns the first success without retrying', async () => {
    const attempt = attempts(ok('stored'));

    await expect(withRetries(attempt, NO_WAIT)).resolves.toEqual(ok('stored'));
    expect(attempt).toHaveBeenCalledTimes(1);
  });

  it('retries a network failure or a 5xx until it succeeds', async () => {
    const attempt = attempts(
      failed({ kind: 'network' }),
      failed({ kind: 'problem', status: 503, code: undefined }),
      ok('stored'),
    );

    await expect(withRetries(attempt, NO_WAIT)).resolves.toEqual(ok('stored'));
    expect(attempt).toHaveBeenCalledTimes(3);
  });

  it('stops after the last retry and returns its failure (#67 T14)', async () => {
    const attempt = jest.fn(async () => failed<string>({ kind: 'network' }));

    await expect(withRetries(attempt, NO_WAIT)).resolves.toEqual(failed({ kind: 'network' }));
    expect(attempt).toHaveBeenCalledTimes(3);
  });

  it('does not retry a failure that gives the same answer every time', async () => {
    const tooLarge = failed<string>({ kind: 'problem', status: 413, code: 'FILE_TOO_LARGE' });
    const attempt = attempts(tooLarge);

    await expect(withRetries(attempt, NO_WAIT)).resolves.toEqual(tooLarge);
    expect(attempt).toHaveBeenCalledTimes(1);
  });

  it('waits the given delays between attempts', async () => {
    jest.useFakeTimers();
    try {
      const attempt = jest.fn(async () => failed<string>({ kind: 'network' }));
      const result = withRetries(attempt, [1_000, 3_000]);

      await jest.advanceTimersByTimeAsync(999);
      expect(attempt).toHaveBeenCalledTimes(1);
      await jest.advanceTimersByTimeAsync(1);
      expect(attempt).toHaveBeenCalledTimes(2);
      await jest.advanceTimersByTimeAsync(3_000);
      expect(attempt).toHaveBeenCalledTimes(3);
      await expect(result).resolves.toEqual(failed({ kind: 'network' }));
    } finally {
      jest.useRealTimers();
    }
  });
});

describe('isTransient', () => {
  it.each([
    [{ kind: 'network' } as const, true],
    [{ kind: 'problem', status: 500, code: undefined } as const, true],
    [{ kind: 'problem', status: 409, code: 'ATTACHMENT_NOT_ALLOWED' } as const, false],
    [{ kind: 'problem', status: 422, code: 'VALIDATION_FAILED' } as const, false],
  ])('%o is transient: %s', (failure, expected) => {
    expect(isTransient(failure)).toBe(expected);
  });
});
