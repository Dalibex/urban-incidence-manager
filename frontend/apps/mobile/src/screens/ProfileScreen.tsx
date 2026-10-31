import { Text, View } from 'react-native';

import { Button } from '../components/Button';
import { useSession } from '../session/SessionContext';
import { screenStyles } from './styles';

export function ProfileScreen() {
  const { user, signOut } = useSession();
  return (
    <View style={screenStyles.screen}>
      <Text style={screenStyles.title}>{user?.name}</Text>
      <Text style={screenStyles.note}>{user?.email}</Text>
      <View style={screenStyles.actions}>
        <Button label="Sign out" variant="secondary" onPress={() => void signOut()} />
      </View>
    </View>
  );
}
