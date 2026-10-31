import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView } from 'react-native';

import { Button } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { TextField } from '../components/TextField';
import type { AuthStackParamList } from '../navigation/AuthStack';
import { UNEXPECTED_ERROR, signInMessage } from '../session/messages';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

type Props = NativeStackScreenProps<AuthStackParamList, 'SignIn'>;

export function SignInScreen({ navigation, route }: Props) {
  const { expired, signIn } = useSession();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      const result = await signIn({ email: email.trim(), password });
      // On success the session changes and the navigator swaps to the tabs.
      if (!result.ok) {
        setError(signInMessage(result.failure));
      }
    } catch (unexpected) {
      // A bug or a storage failure, not a user mistake: log it and let the user retry.
      console.error(unexpected);
      setError(UNEXPECTED_ERROR);
    } finally {
      setSubmitting(false);
    }
  }

  const notice =
    route.params?.notice ?? (expired ? 'Your session has expired. Sign in again.' : null);

  return (
    <KeyboardAvoidingView
      style={screenStyles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView contentContainerStyle={screenStyles.form} keyboardShouldPersistTaps="handled">
        <FormMessage message={error ?? notice} tone={error ? 'error' : 'info'} />
        <TextField
          label="Email"
          value={email}
          onChangeText={setEmail}
          autoCapitalize="none"
          autoComplete="email"
          keyboardType="email-address"
          textContentType="username"
        />
        <TextField
          label="Password"
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoComplete="current-password"
          textContentType="password"
          onSubmitEditing={() => void submit()}
        />
        <Button
          label={submitting ? 'Signing in…' : 'Sign in'}
          disabled={submitting}
          onPress={() => void submit()}
        />
        <Button
          label="Create an account"
          variant="link"
          onPress={() => navigation.navigate('SignUp')}
        />
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
