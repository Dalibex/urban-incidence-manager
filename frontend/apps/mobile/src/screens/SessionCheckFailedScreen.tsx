import type { ApiFailure } from '@urbanpulse/api-client';
import { View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Button } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { sessionCheckMessage } from '../session/messages';
import { screenStyles } from './styles';

interface SessionCheckFailedScreenProps {
  failure: ApiFailure;
  retrying: boolean;
  onRetry: () => void;
}

/**
 * The saved session could not be checked at start-up. It offers to try again instead of signing
 * in, because signing in again would leave the saved session alive on the server.
 */
export function SessionCheckFailedScreen({
  failure,
  retrying,
  onRetry,
}: SessionCheckFailedScreenProps) {
  return (
    <SafeAreaView style={[screenStyles.screen, screenStyles.centered]}>
      <View style={screenStyles.actions}>
        <FormMessage message={sessionCheckMessage(failure)} />
        <Button
          label={retrying ? 'Trying again…' : 'Try again'}
          onPress={onRetry}
          variant="secondary"
          disabled={retrying}
        />
      </View>
    </SafeAreaView>
  );
}
