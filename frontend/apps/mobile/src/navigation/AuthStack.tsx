import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { SignInScreen } from '../screens/SignInScreen';
import { SignUpScreen } from '../screens/SignUpScreen';

export type AuthStackParamList = {
  /** `notice` explains why the user lands here, for example after signing up. */
  SignIn: { notice?: string } | undefined;
  SignUp: undefined;
};

const Stack = createNativeStackNavigator<AuthStackParamList>();

export function AuthStack() {
  return (
    <Stack.Navigator>
      <Stack.Screen name="SignIn" component={SignInScreen} options={{ title: 'UrbanPulse' }} />
      <Stack.Screen name="SignUp" component={SignUpScreen} options={{ title: 'Create account' }} />
    </Stack.Navigator>
  );
}
