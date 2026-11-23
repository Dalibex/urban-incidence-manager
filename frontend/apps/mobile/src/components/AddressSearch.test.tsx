import type { ApiResult, Place } from '@urbanpulse/api-client';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AccessibilityInfo } from 'react-native';

import { ADDRESS_SEARCH_FAILED } from '../session/messages';
import { failed, ok } from '../test-utils';
import {
  ADDRESS_LABEL,
  AddressSearch,
  NO_PLACES,
  placesFound,
  QUERY_TOO_SHORT,
  SEARCH,
} from './AddressSearch';

const LARIOS: Place = {
  displayName: '5, Calle Larios, Centro, Málaga, 29005, España',
  latitude: 36.7196,
  longitude: -4.4214,
};
const ALAMEDA: Place = {
  displayName: 'Alameda Principal, Centro, Málaga, 29001, España',
  latitude: 36.7179,
  longitude: -4.4237,
};

const search = jest.fn<Promise<ApiResult<Place[]>>, [string]>();
const onSelect = jest.fn<void, [Place]>();

function renderSearch() {
  return render(<AddressSearch search={search} onSelect={onSelect} />);
}

async function typeAndSubmit(text: string) {
  await act(() => fireEvent.changeText(screen.getByLabelText(ADDRESS_LABEL), text));
  await act(() => fireEvent.press(screen.getByRole('button', { name: SEARCH })));
}

beforeEach(() => {
  search.mockReset();
  onSelect.mockReset();
});

describe('AddressSearch', () => {
  it('searches the trimmed text when the citizen submits, and lists the places', async () => {
    search.mockResolvedValue(ok([LARIOS, ALAMEDA]));
    await renderSearch();

    await typeAndSubmit('  Calle Larios 5  ');

    expect(search).toHaveBeenCalledTimes(1);
    expect(search).toHaveBeenCalledWith('Calle Larios 5');
    expect(screen.getByRole('button', { name: LARIOS.displayName })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: ALAMEDA.displayName })).toBeOnTheScreen();
  });

  it('never searches while the citizen types', async () => {
    await renderSearch();

    await act(() => fireEvent.changeText(screen.getByLabelText(ADDRESS_LABEL), 'Calle Larios'));

    expect(search).not.toHaveBeenCalled();
  });

  it('searches from the keyboard too', async () => {
    search.mockResolvedValue(ok([LARIOS]));
    await renderSearch();

    await act(() => fireEvent.changeText(screen.getByLabelText(ADDRESS_LABEL), 'Calle Larios'));
    await act(() => fireEvent(screen.getByLabelText(ADDRESS_LABEL), 'submitEditing'));

    expect(search).toHaveBeenCalledWith('Calle Larios');
  });

  it('asks for more text instead of sending an empty or too short search', async () => {
    await renderSearch();

    await typeAndSubmit('   ');
    expect(screen.getByText(QUERY_TOO_SHORT)).toBeOnTheScreen();

    await typeAndSubmit(' ab ');
    expect(screen.getByText(QUERY_TOO_SHORT)).toBeOnTheScreen();
    expect(search).not.toHaveBeenCalled();
  });

  it('moves the map to the place the citizen chooses and closes the list', async () => {
    search.mockResolvedValue(ok([LARIOS, ALAMEDA]));
    await renderSearch();
    await typeAndSubmit('Centro');

    await act(() => fireEvent.press(screen.getByRole('button', { name: ALAMEDA.displayName })));

    expect(onSelect).toHaveBeenCalledWith(ALAMEDA);
    expect(screen.queryByRole('button', { name: LARIOS.displayName })).toBeNull();
  });

  it('says when nothing of Málaga matches, and how to place the pin', async () => {
    search.mockResolvedValue(ok([]));
    await renderSearch();

    await typeAndSubmit('Calle Inventada 99');

    expect(screen.getByText(NO_PLACES)).toBeOnTheScreen();
  });

  it('says to place the pin by hand when the search is unavailable', async () => {
    search.mockResolvedValue(
      failed({ kind: 'problem', status: 503, code: 'GEOCODING_UNAVAILABLE' }),
    );
    await renderSearch();

    await typeAndSubmit('Calle Larios');

    expect(
      screen.getByText(
        'The address search is not available now. Move the map to put the pin on the incident.',
      ),
    ).toBeOnTheScreen();
  });

  it('asks to wait after too many searches', async () => {
    search.mockResolvedValue(failed({ kind: 'problem', status: 429, code: 'TOO_MANY_ATTEMPTS' }));
    await renderSearch();

    await typeAndSubmit('Calle Larios');

    expect(screen.getByText(/The address search is busy/)).toBeOnTheScreen();
  });

  it('clears the places of the last search once the citizen edits the text', async () => {
    search.mockResolvedValue(ok([LARIOS]));
    await renderSearch();
    await typeAndSubmit('Calle Larios');

    await act(() => fireEvent.changeText(screen.getByLabelText(ADDRESS_LABEL), 'Calle Granada'));

    expect(screen.queryByRole('button', { name: LARIOS.displayName })).toBeNull();
  });

  it('announces what the search found, for screen readers', async () => {
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
    search.mockResolvedValueOnce(ok([LARIOS, ALAMEDA])).mockResolvedValueOnce(ok([]));
    await renderSearch();

    await typeAndSubmit('Centro');
    expect(announce).toHaveBeenLastCalledWith(placesFound(2));

    await typeAndSubmit('Calle Inventada');
    expect(announce).toHaveBeenLastCalledWith(NO_PLACES);
    announce.mockRestore();
  });

  it('stops searching when the call breaks, so it can be tried again', async () => {
    search.mockRejectedValueOnce(new Error('bug')).mockResolvedValueOnce(ok([LARIOS]));
    await renderSearch();

    await typeAndSubmit('Calle Larios');
    expect(screen.getByText(ADDRESS_SEARCH_FAILED)).toBeOnTheScreen();

    await act(() => fireEvent.press(screen.getByRole('button', { name: SEARCH })));
    expect(screen.getByRole('button', { name: LARIOS.displayName })).toBeOnTheScreen();
  });

  it('sends one search when the citizen submits twice before the answer', async () => {
    let answer: (result: ApiResult<Place[]>) => void = () => {};
    search.mockReturnValue(
      new Promise((resolve) => {
        answer = resolve;
      }),
    );
    await renderSearch();
    await act(() => fireEvent.changeText(screen.getByLabelText(ADDRESS_LABEL), 'Calle Larios'));

    await act(() => {
      fireEvent(screen.getByLabelText(ADDRESS_LABEL), 'submitEditing');
      fireEvent(screen.getByLabelText(ADDRESS_LABEL), 'submitEditing');
    });
    expect(screen.getByLabelText('Searching the address')).toBeOnTheScreen();
    await act(async () => answer(ok([LARIOS])));

    expect(search).toHaveBeenCalledTimes(1);
  });
});
