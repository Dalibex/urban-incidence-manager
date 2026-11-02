import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { IncidentDetailScreen } from '../screens/IncidentDetailScreen';
import { MyReportsScreen } from '../screens/MyReportsScreen';

export type MyReportsStackParamList = {
  ReportList: undefined;
  ReportDetail: { incidentId: string };
};

const Stack = createNativeStackNavigator<MyReportsStackParamList>();

/**
 * List and detail of the citizen's reports in one stack inside the tab, so the back gesture
 * returns to the list where it was left and the tab bar stays visible.
 */
export function MyReportsStack() {
  return (
    <Stack.Navigator>
      <Stack.Screen
        name="ReportList"
        component={MyReportsScreen}
        options={{ title: 'My reports' }}
      />
      <Stack.Screen
        name="ReportDetail"
        component={IncidentDetailScreen}
        options={{ title: 'Report' }}
      />
    </Stack.Navigator>
  );
}
