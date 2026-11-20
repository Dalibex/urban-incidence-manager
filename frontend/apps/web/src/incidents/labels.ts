import type { components } from '@urbanpulse/api-client';

type Priority = components['schemas']['Priority'];
type WeatherCondition = components['schemas']['WeatherCondition'];

/** Staff only: citizens never receive the priority, so it is not shared with the mobile app. */
export const PRIORITY_LABELS: Record<Priority, string> = {
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
};

/**
 * Staff only: the citizen's app does not show the weather (#304). `SUNNY` is any clear sky, by day
 * or by night, so it reads "Clear".
 */
export const WEATHER_LABELS: Record<WeatherCondition, string> = {
  SUNNY: 'Clear',
  PARTLY_CLOUDY: 'Partly cloudy',
  CLOUDY: 'Cloudy',
  FOGGY: 'Foggy',
  RAINY: 'Rainy',
  SNOWY: 'Snowy',
  STORMY: 'Stormy',
  WINDY: 'Windy',
};
