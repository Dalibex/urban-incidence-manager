import { Text, View } from 'react-native';

import { screenStyles } from './styles';

/** Placeholder for the list of the citizen's own reports. */
export function MyReportsScreen() {
  return (
    <View style={screenStyles.screen}>
      <Text style={screenStyles.body}>Follow the status of the incidents you reported.</Text>
      <Text style={screenStyles.note}>This screen is not built yet.</Text>
    </View>
  );
}
