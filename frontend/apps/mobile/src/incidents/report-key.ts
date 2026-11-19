import { uuid } from 'expo';
import { useCallback, useRef } from 'react';

/**
 * The `Idempotency-Key` of the report being written (#289). A retry of the same report gets the same
 * key, so if the API saved the first attempt and its answer was lost, the retry returns that
 * incident instead of creating a second one. A report with other data gets a new key: the citizen
 * changed it, so it is not a retry, and the API would refuse the old key with
 * `IDEMPOTENCY_KEY_REUSED`.
 *
 * The key lives as long as the screen: if the app closes before the answer, the next report gets a
 * new one.
 */
export function useReportKey() {
  const current = useRef<{ key: string; report: string } | null>(null);

  /** The key for this report: the one of the last attempt if the data is the same, or a new one. */
  const keyFor = useCallback((report: object): string => {
    const data = JSON.stringify(report);
    if (current.current?.report !== data) {
      current.current = { key: uuid.v4(), report: data };
    }
    return current.current.key;
  }, []);

  /** The report was saved: the next one, even with the same data, is a new report. */
  const forget = useCallback(() => {
    current.current = null;
  }, []);

  return { keyFor, forget };
}
