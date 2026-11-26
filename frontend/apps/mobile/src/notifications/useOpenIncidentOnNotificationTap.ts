import { useNavigation, type NavigationProp } from '@react-navigation/native';
import * as Notifications from 'expo-notifications';
import { useEffect } from 'react';

import type { MainTabsParamList } from '../navigation/MainTabs';

/** The tap that started the app is read once per process: the last response outlives a sign-out. */
let coldStartRead = false;

/**
 * Opens the incident a notification names when the citizen taps it, with the app open or in the
 * background, and also when the tap started the app. The data is the `incidentId` the backend puts
 * in every push (#373). The cold start has no test: the tests mock only the listener.
 */
export function useOpenIncidentOnNotificationTap(): void {
  const navigation = useNavigation<NavigationProp<MainTabsParamList>>();

  useEffect(() => {
    const open = (response: Notifications.NotificationResponse | null) => {
      const incidentId = response?.notification.request.content.data?.incidentId;
      if (typeof incidentId === 'string') {
        navigation.navigate('MyReports', { screen: 'ReportDetail', params: { incidentId } });
      }
    };
    const subscription = Notifications.addNotificationResponseReceivedListener(open);
    if (!coldStartRead) {
      coldStartRead = true;
      open(Notifications.getLastNotificationResponse?.() ?? null);
    }
    return () => subscription.remove();
  }, [navigation]);
}
