import { Text, View } from 'react-native';

import { Button } from '../components/Button';
import { QUICK_SIGN_IN_CITIZEN_EMAIL, quickSignInConfig } from './quick-sign-in';
import { screenStyles } from './styles';

interface QuickSignInProps {
  onSignIn: (credentials: { email: string; password: string }) => void;
  disabled: boolean;
}

/** Signs in as the demo citizen with one tap, for the demo. Nothing renders with the flag off. */
export function QuickSignIn({ onSignIn, disabled }: QuickSignInProps) {
  const config = quickSignInConfig();
  if (config.state === 'off') {
    return null;
  }
  return (
    <View style={screenStyles.actions}>
      {config.state === 'missing-password' ? (
        <Text style={screenStyles.note}>
          Set EXPO_PUBLIC_DEMO_PASSWORD in apps/mobile/.env to use quick sign-in.
        </Text>
      ) : (
        <Button
          label="Quick sign-in as the demo citizen"
          variant="secondary"
          disabled={disabled}
          onPress={() =>
            onSignIn({ email: QUICK_SIGN_IN_CITIZEN_EMAIL, password: config.password })
          }
        />
      )}
    </View>
  );
}
