import { Text, View } from 'react-native';

import { screenStyles } from './styles';

/** Placeholder for the report form. */
export function NewReportScreen() {
  return (
    <View style={screenStyles.screen}>
      <Text style={screenStyles.body}>
        Report a problem in the street with its location and photos.
      </Text>
      <Text style={screenStyles.note}>This screen is not built yet.</Text>
    </View>
  );
}
