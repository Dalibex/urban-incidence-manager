import { describe, expect, it } from 'vitest';

import { apiPageOf } from './Pagination';

describe('apiPageOf', () => {
  it('asks for the page of the address, counted from 0', () => {
    expect(apiPageOf(1, 20)).toBe(0);
    expect(apiPageOf(3, 20)).toBe(2);
  });

  // #283 C: the API skips page × size rows, counted in an int32.
  it('asks for the last page the API reads when the page of the address is beyond it', () => {
    const lastReadable = Math.floor(2_147_483_647 / 20);

    expect(apiPageOf(lastReadable + 1, 20)).toBe(lastReadable);
    expect(apiPageOf(lastReadable + 2, 20)).toBe(lastReadable);
    expect(apiPageOf(9_999_999_999, 20)).toBe(lastReadable);
    expect(apiPageOf(9_999_999_999, 100)).toBe(Math.floor(2_147_483_647 / 100));
  });
});
