import type { ApiResult, Place } from '@urbanpulse/api-client';
import { colors, fontSizes, spacing } from '@urbanpulse/ui';
import { useRef, useState } from 'react';
import {
  AccessibilityInfo,
  ActivityIndicator,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';

import { ADDRESS_SEARCH_FAILED, placeSearchMessage } from '../session/messages';
import { Button, MIN_TOUCH_SIZE } from './Button';
import { TextField } from './TextField';

export const ADDRESS_LABEL = 'Search an address in Málaga';
export const SEARCH = 'Search';
export const QUERY_TOO_SHORT = 'Write at least 3 characters to search.';
export const NO_PLACES = 'No address of Málaga matches. Check it, or move the map to the incident.';

/** What a screen reader says when the places arrive: the list itself does not announce them. */
export function placesFound(count: number): string {
  return count === 1
    ? '1 address found. Choose it to move the map there.'
    : `${count} addresses found. Choose one to move the map there.`;
}

/** The limits of `q` in the contract, in characters once trimmed. */
const QUERY_MIN_LENGTH = 3;
const QUERY_MAX_LENGTH = 200;

type SearchState =
  | { kind: 'idle' }
  | { kind: 'searching' }
  | { kind: 'found'; places: Place[] }
  | { kind: 'message'; text: string };

export interface AddressSearchProps {
  /** Searches the places of Málaga that match the text (`listPlaces`). */
  search: (query: string) => Promise<ApiResult<Place[]>>;
  /** The citizen chose a place: the map moves there. */
  onSelect: (place: Place) => void;
}

/**
 * Address search of the report form (#179): the citizen writes a street and the map jumps there
 * instead of being panned from the city centre. It searches when the citizen submits, never while
 * they type, as the Nominatim usage policy asks. A failed search never blocks the report: the
 * message says to move the map by hand (RF26).
 */
export function AddressSearch({ search, onSelect }: AddressSearchProps) {
  const [query, setQuery] = useState('');
  const [state, setState] = useState<SearchState>({ kind: 'idle' });
  // Guards a second submit before the `searching` state renders, which would send it twice.
  const searchingRef = useRef(false);

  // A live region only works on Android, so the outcome is also announced, for VoiceOver.
  function show(next: Exclude<SearchState, { kind: 'idle' | 'searching' }>) {
    setState(next);
    AccessibilityInfo.announceForAccessibility(
      next.kind === 'found' ? placesFound(next.places.length) : next.text,
    );
  }

  function edit(text: string) {
    setQuery(text);
    // The places or the message of the last search no longer match what the field says.
    setState((current) => (current.kind === 'searching' ? current : { kind: 'idle' }));
  }

  async function submit() {
    if (searchingRef.current) {
      return;
    }
    const text = query.trim();
    if ([...text].length < QUERY_MIN_LENGTH) {
      show({ kind: 'message', text: QUERY_TOO_SHORT });
      return;
    }
    searchingRef.current = true;
    setState({ kind: 'searching' });
    try {
      const result = await search(text);
      if (!result.ok) {
        show({ kind: 'message', text: placeSearchMessage(result.failure) });
      } else if (result.data.length === 0) {
        show({ kind: 'message', text: NO_PLACES });
      } else {
        show({ kind: 'found', places: result.data });
      }
    } catch {
      // A bug, not a failure the API answered: the search must not stay spinning.
      show({ kind: 'message', text: ADDRESS_SEARCH_FAILED });
    } finally {
      searchingRef.current = false;
    }
  }

  function choose(place: Place) {
    setState({ kind: 'idle' });
    onSelect(place);
  }

  const searching = state.kind === 'searching';
  return (
    <View style={styles.container}>
      <View style={styles.row}>
        <View style={styles.input}>
          <TextField
            label={ADDRESS_LABEL}
            value={query}
            onChangeText={edit}
            placeholder="Calle Larios 5"
            maxLength={QUERY_MAX_LENGTH}
            returnKeyType="search"
            autoCorrect={false}
            onSubmitEditing={() => void submit()}
          />
        </View>
        <Button
          label={SEARCH}
          variant="secondary"
          disabled={searching}
          onPress={() => void submit()}
        />
      </View>
      {searching ? (
        <ActivityIndicator accessibilityLabel="Searching the address" color={colors.primary} />
      ) : null}
      {state.kind === 'message' ? (
        <Text accessibilityLiveRegion="polite" style={styles.message}>
          {state.text}
        </Text>
      ) : null}
      {state.kind === 'found' ? (
        <View accessibilityRole="list" accessibilityLabel="Addresses found">
          {state.places.map((place) => (
            <Pressable
              key={`${place.latitude},${place.longitude},${place.displayName}`}
              accessibilityRole="button"
              accessibilityHint="Moves the map to this address"
              onPress={() => choose(place)}
              style={({ pressed }) => [styles.place, pressed && styles.pressed]}
            >
              <Text style={styles.placeText}>{place.displayName}</Text>
            </Pressable>
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.xs,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'flex-end',
    gap: spacing.sm,
  },
  input: {
    flex: 1,
  },
  message: {
    color: colors.text,
    fontSize: 14,
  },
  place: {
    minHeight: MIN_TOUCH_SIZE,
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
    borderBottomWidth: 1,
    borderBottomColor: colors.surface,
  },
  pressed: {
    opacity: 0.6,
  },
  placeText: {
    color: colors.text,
    fontSize: fontSizes.body,
  },
});
