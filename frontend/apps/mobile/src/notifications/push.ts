import Constants from 'expo-constants';
import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

export type PushPermission = 'granted' | 'denied';

const ANDROID_CHANNEL_ID = 'default';

// Without a handler, a push that arrives while the app is open is dropped silently.
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: true,
    shouldSetBadge: false,
  }),
});

/** Whether the citizen allowed notifications; it never shows the system prompt. */
export async function readPushPermission(): Promise<PushPermission> {
  const { granted } = await Notifications.getPermissionsAsync();
  return granted ? 'granted' : 'denied';
}

/** Shows the system prompt if the citizen has not answered yet. */
export async function askPushPermission(): Promise<PushPermission> {
  if (Platform.OS === 'android') {
    // Android 13 and later show the prompt only once the app has a notification channel.
    await Notifications.setNotificationChannelAsync(ANDROID_CHANNEL_ID, {
      name: 'Incident updates',
      importance: Notifications.AndroidImportance.DEFAULT,
    });
  }
  const { granted } = await Notifications.requestPermissionsAsync();
  return granted ? 'granted' : 'denied';
}

/**
 * The Expo push token of this device, or `null` when it cannot be read: Expo Go, no permission or
 * no network. Callers treat `null` as "no push on this device", never as a failure of the session.
 */
export async function readPushToken(): Promise<string | null> {
  try {
    const projectId: unknown = Constants.expoConfig?.extra?.eas?.projectId;
    if (typeof projectId !== 'string') {
      return null;
    }
    const { data } = await Notifications.getExpoPushTokenAsync({ projectId });
    return data;
  } catch {
    return null;
  }
}
