import { useFocusEffect } from '@react-navigation/native';
import { useCallback, useRef } from 'react';

/**
 * Refetches when the screen comes back into focus, for example on returning from a detail that
 * loaded a newer status. React Query cannot see navigation focus in React Native, so this is the
 * pattern its documentation gives. The first focus is skipped: mounting already fetched.
 */
export function useRefreshOnFocus(refetch: () => unknown): void {
  const firstFocus = useRef(true);
  useFocusEffect(
    useCallback(() => {
      if (firstFocus.current) {
        firstFocus.current = false;
        return;
      }
      void refetch();
    }, [refetch]),
  );
}
