// The session module refuses to load without the API URL (apps/mobile/.env in development). Tests
// never reach it: they mock the API client.
process.env.EXPO_PUBLIC_API_BASE_URL = 'http://api.test';
// Quick sign-in off, whatever the local .env says; a test turns it on by setting the variables.
process.env.EXPO_PUBLIC_QUICK_SIGN_IN = 'false';
delete process.env.EXPO_PUBLIC_DEMO_PASSWORD;
