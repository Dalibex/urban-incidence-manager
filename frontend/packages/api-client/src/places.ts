import type { ApiClient } from './index';
import { callApi, type ApiResult } from './account';
import type { components } from './schema';

/** A place of Málaga found by the address search (`PlaceResponse`). */
export type Place = components['schemas']['PlaceResponse'];

/**
 * The places of Málaga that match `q`, the most relevant first, at most five (`listPlaces`, #179).
 * Call it when the user submits the search, never while they type: the backend answers from
 * Nominatim, whose usage policy forbids autocomplete. A `503` with `GEOCODING_UNAVAILABLE` means
 * the search is down, and the position is still placed by hand.
 */
export function listPlaces(client: ApiClient, q: string): Promise<ApiResult<Place[]>> {
  return callApi(() => client.GET('/places', { params: { query: { q } } }));
}
