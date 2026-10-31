import { Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Button } from '../components/Button';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

/** Shown to a staff account that signs in on the phone: their screens are on the website. */
export function UseWebScreen() {
  const { signOut } = useSession();
  return (
    <SafeAreaView style={screenStyles.screen}>
      <Text accessibilityRole="header" style={screenStyles.title}>
        Use the UrbanPulse website
      </Text>
      <Text style={screenStyles.body}>
        The mobile app is for citizens. Municipal staff work from the website.
      </Text>
      <View style={screenStyles.actions}>
        <Button label="Sign out" variant="secondary" onPress={() => void signOut()} />
      </View>
    </SafeAreaView>
  );
}
