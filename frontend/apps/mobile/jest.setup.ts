// The session module refuses to load without the API URL (apps/mobile/.env in development). Tests
// never reach it: they mock the API client.
process.env.EXPO_PUBLIC_API_BASE_URL = 'http://api.test';
