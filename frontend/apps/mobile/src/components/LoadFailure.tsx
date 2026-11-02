import { View } from 'react-native';

import { ApiCallError } from '../query-client';
import { screenStyles } from '../screens/styles';
import { Button } from './Button';
import { FormMessage } from './FormMessage';

interface LoadFailureProps {
  /** What could not be loaded, as in "Your reports could not be loaded." */
  what: string;
  error: unknown;
  retrying: boolean;
  onRetry: () => void;
}

/** A query that failed, with the reason the user can act on and a way to try again. */
export function LoadFailure({ what, error, retrying, onRetry }: LoadFailureProps) {
  const unreachable = error instanceof ApiCallError && error.failure.kind === 'network';
  return (
    <View style={screenStyles.actions}>
      {/* Announced to screen readers: an alert role alone says nothing in React Native. */}
      <FormMessage
        message={
          unreachable
            ? 'UrbanPulse cannot be reached. Check your connection and try again.'
            : `${what} could not be loaded. Try again in a moment.`
        }
      />
      <Button
        label={retrying ? 'Trying again…' : 'Try again'}
        onPress={onRetry}
        variant="secondary"
        disabled={retrying}
      />
    </View>
  );
}
