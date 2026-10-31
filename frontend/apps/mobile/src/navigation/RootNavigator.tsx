import { ActivityIndicator, View } from 'react-native';

import { screenStyles } from '../screens/styles';
import { UseWebScreen } from '../screens/UseWebScreen';
import { useSession } from '../session/SessionContext';
import { AuthStack } from './AuthStack';
import { MainTabs } from './MainTabs';

/**
 * Chooses the tree by session, as React Navigation recommends for authentication: signing in or
 * out swaps the whole tree, so the back gesture cannot return to a screen of the other state.
 */
export function RootNavigator() {
  const { restoring, user } = useSession();
  if (restoring) {
    return (
      <View style={[screenStyles.screen, screenStyles.centered]}>
        <ActivityIndicator accessibilityLabel="Loading" size="large" />
      </View>
    );
  }
  if (!user) {
    return <AuthStack />;
  }
  // The mobile app is for citizens (ADR-0005); staff accounts work from the website.
  return user.role === 'CITIZEN' ? <MainTabs /> : <UseWebScreen />;
}
