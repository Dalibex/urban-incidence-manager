import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView } from 'react-native';

import { Button } from '../components/Button';
import { FormMessage } from '../components/FormMessage';
import { TextField } from '../components/TextField';
import type { AuthStackParamList } from '../navigation/AuthStack';
import { UNEXPECTED_ERROR, signUpMessage } from '../session/messages';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

type Props = NativeStackScreenProps<AuthStackParamList, 'SignUp'>;

/** Citizens sign up here; staff accounts are created by an administrator. */
export function SignUpScreen({ navigation }: Props) {
  const { signUp, signIn } = useSession();
  const [name, setName] = useState('');
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
      const credentials = { email: email.trim(), password };
      const account = await signUp({ name: name.trim(), ...credentials });
      if (!account.ok) {
        setError(signUpMessage(account.failure));
        return;
      }
      // The account exists now. If signing in fails, retrying the sign-up would only answer
      // EMAIL_ALREADY_EXISTS, so send the user to sign in instead.
      const session = await signIn(credentials);
      if (!session.ok) {
        navigation.replace('SignIn', { notice: 'Your account is ready. Sign in to continue.' });
      }
    } catch (unexpected) {
      console.error(unexpected);
      setError(UNEXPECTED_ERROR);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <KeyboardAvoidingView
      style={screenStyles.flex}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView contentContainerStyle={screenStyles.form} keyboardShouldPersistTaps="handled">
        <FormMessage message={error} />
        <TextField
          label="Full name"
          hint="Staff see it on the incidents you report."
          value={name}
          onChangeText={setName}
          autoComplete="name"
          textContentType="name"
        />
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
          hint="At least 8 characters."
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoComplete="new-password"
          textContentType="newPassword"
          onSubmitEditing={() => void submit()}
        />
        <Button
          label={submitting ? 'Creating account…' : 'Create account'}
          disabled={submitting}
          onPress={() => void submit()}
        />
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
