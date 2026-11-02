import { NavigationContainer } from '@react-navigation/native';
import { QueryClientProvider } from '@tanstack/react-query';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import { RootNavigator } from './src/navigation/RootNavigator';
import { createQueryClient, refetchOnAppForeground } from './src/query-client';
import { SessionProvider } from './src/session/SessionContext';

const queryClient = createQueryClient();
refetchOnAppForeground();

export default function App() {
  return (
    <SafeAreaProvider>
      {/* Outside the session, which clears the cache when the user signs out. */}
      <QueryClientProvider client={queryClient}>
        <SessionProvider>
          <NavigationContainer>
            <RootNavigator />
          </NavigationContainer>
        </SessionProvider>
      </QueryClientProvider>
      <StatusBar style="auto" />
    </SafeAreaProvider>
  );
}
