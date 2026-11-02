import { Ionicons } from '@expo/vector-icons';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import type { NavigatorScreenParams } from '@react-navigation/native';
import { colors } from '@urbanpulse/ui';
import type { ComponentProps } from 'react';

import { NewIncidentScreen } from '../screens/NewIncidentScreen';
import { ProfileScreen } from '../screens/ProfileScreen';
import { MyReportsStack, type MyReportsStackParamList } from './MyReportsStack';

export type MainTabsParamList = {
  NewReport: undefined;
  MyReports: NavigatorScreenParams<MyReportsStackParamList> | undefined;
  Profile: undefined;
};

type IconName = ComponentProps<typeof Ionicons>['name'];

const Tab = createBottomTabNavigator<MainTabsParamList>();

function tabIcon(name: IconName) {
  return ({ color, size }: { color: string; size: number }) => (
    <Ionicons name={name} color={color} size={size} />
  );
}

export function MainTabs() {
  return (
    <Tab.Navigator
      screenOptions={{
        tabBarActiveTintColor: colors.primary,
        tabBarInactiveTintColor: colors.textMuted,
      }}
    >
      <Tab.Screen
        name="NewReport"
        component={NewIncidentScreen}
        options={{ title: 'New', headerTitle: 'New report', tabBarIcon: tabIcon('add-circle') }}
      />
      <Tab.Screen
        name="MyReports"
        component={MyReportsStack}
        // The stack shows its own header, with the back button of the detail.
        options={{ title: 'My reports', headerShown: false, tabBarIcon: tabIcon('list') }}
      />
      <Tab.Screen
        name="Profile"
        component={ProfileScreen}
        options={{ title: 'Profile', tabBarIcon: tabIcon('person-circle') }}
      />
    </Tab.Navigator>
  );
}
