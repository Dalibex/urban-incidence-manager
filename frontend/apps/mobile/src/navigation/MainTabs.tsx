import { Ionicons } from '@expo/vector-icons';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { colors } from '@urbanpulse/ui';
import type { ComponentProps } from 'react';

import { MyReportsScreen } from '../screens/MyReportsScreen';
import { NewReportScreen } from '../screens/NewReportScreen';
import { ProfileScreen } from '../screens/ProfileScreen';

export type MainTabsParamList = {
  NewReport: undefined;
  MyReports: undefined;
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
        component={NewReportScreen}
        options={{ title: 'New', headerTitle: 'New report', tabBarIcon: tabIcon('add-circle') }}
      />
      <Tab.Screen
        name="MyReports"
        component={MyReportsScreen}
        options={{ title: 'My reports', tabBarIcon: tabIcon('list') }}
      />
      <Tab.Screen
        name="Profile"
        component={ProfileScreen}
        options={{ title: 'Profile', tabBarIcon: tabIcon('person-circle') }}
      />
    </Tab.Navigator>
  );
}
