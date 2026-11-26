import { callApi, type ApiResult } from './account';
import type { ApiClient } from './index';
import type { components } from './schema';

/** The channels the signed-in citizen is notified through (RF14). */
export function getNotificationSettings(
  client: ApiClient,
): Promise<ApiResult<components['schemas']['NotificationSettings']>> {
  return callApi(() => client.GET('/users/me/notification-settings'));
}

/** Changes only the channels it is given; the others keep their value. */
export function updateNotificationSettings(
  client: ApiClient,
  change: components['schemas']['UpdateNotificationSettingsRequest'],
): Promise<ApiResult<components['schemas']['NotificationSettings']>> {
  return callApi(() => client.PATCH('/users/me/notification-settings', { body: change }));
}

/** Registers the Expo push token of this device for the signed-in citizen. */
export function registerPushToken(client: ApiClient, token: string): Promise<ApiResult<void>> {
  return callApi(() => client.POST('/push-tokens', { body: { token } }));
}

/**
 * Deletes a push token by value; `204` even if the API does not know it. After a sign-out the
 * token store is empty, so the caller passes the access token it read before: the request carries
 * it instead of one from the store. If the store holds tokens again (someone signed in before the
 * deletion goes out), the session fetch sends those instead of the explicit one.
 */
export function deletePushToken(
  client: ApiClient,
  token: string,
  accessToken?: string,
): Promise<ApiResult<void>> {
  return callApi(() =>
    client.POST('/push-token-deletions', {
      body: { token },
      headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
    }),
  );
}
