import { ScrollView, Text, View } from 'react-native';

import { Button } from '../components/Button';
import { useSession } from '../session/SessionContext';
import { NotificationSettingsSection } from './NotificationSettingsSection';
import { screenStyles } from './styles';

export function ProfileScreen() {
  const { user, signOut } = useSession();
  return (
    // Scrolls so the section stays reachable with large text and when it shows a message.
    <ScrollView style={screenStyles.flex} contentContainerStyle={screenStyles.screen}>
      <Text style={screenStyles.title}>{user?.name}</Text>
      <Text style={screenStyles.note}>{user?.email}</Text>
      <NotificationSettingsSection />
      <View style={screenStyles.actions}>
        <Button label="Sign out" variant="secondary" onPress={() => void signOut()} />
      </View>
    </ScrollView>
  );
}
