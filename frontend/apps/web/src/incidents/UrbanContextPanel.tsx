import type { components } from '@urbanpulse/api-client';
import { formatInstant, NOT_AVAILABLE } from '@urbanpulse/ui';
import { useEffect, useRef } from 'react';

import { ApiCallError } from '../query-client';
import { useIncidentUrbanContext } from './incident-queries';
import styles from './Incidents.module.css';
import { WEATHER_LABELS } from './labels';
import { LoadFailure } from './LoadFailure';

type UrbanContext = components['schemas']['UrbanContextResponse'];
type WeatherContext = components['schemas']['WeatherContext'];

/**
 * The context of an incident: the weather at its position when it was reported, from Open-Meteo
 * (#308, ADR-0018). Each status says what it is, so an unknown weather never looks like a value.
 *
 * The state lives in one status region that stays mounted, so a screen reader hears when the
 * weather arrives or the wait ends; a region inserted with its text already in it is often not read.
 */
export function UrbanContextPanel({ incidentId }: { incidentId: string }) {
  const { context, stoppedWaiting } = useIncidentUrbanContext(incidentId);
  const heading = useRef<HTMLHeadingElement>(null);
  const checkedByUser = useRef(false);
  const failed = context.isError && !isRefused(context.error);

  // *Check again* disappears when the weather arrives: keep the keyboard on the section.
  useEffect(() => {
    if (!checkedByUser.current || context.isFetching) {
      return;
    }
    checkedByUser.current = false;
    if (!stoppedWaiting) {
      heading.current?.focus();
    }
  }, [stoppedWaiting, context.isFetching]);

  return (
    <section className={styles.panel} aria-labelledby="context-title">
      <h2 id="context-title" ref={heading} tabIndex={-1} className={styles.panelTitle}>
        Context
      </h2>
      <div role="status">
        {context.isPending ? (
          <p className={styles.muted}>Loading the weather…</p>
        ) : context.isError ? (
          // A 403 or 404: asking again gives the same answer, so there is nothing to retry.
          !failed && <p className={styles.muted}>The weather is not available for this incident.</p>
        ) : (
          <ContextState
            context={context.data}
            stoppedWaiting={stoppedWaiting}
            checking={context.isFetching}
            onCheck={() => {
              checkedByUser.current = true;
              void context.refetch();
            }}
          />
        )}
      </div>
      {failed && (
        <LoadFailure
          what="The weather"
          error={context.error}
          retrying={context.isFetching}
          onRetry={() => void context.refetch()}
        />
      )}
    </section>
  );
}

interface ContextStateProps {
  context: UrbanContext;
  stoppedWaiting: boolean;
  checking: boolean;
  onCheck: () => void;
}

function ContextState({ context, stoppedWaiting, checking, onCheck }: ContextStateProps) {
  if (context.weather) {
    return <Weather weather={context.weather} capturedAt={context.capturedAt} />;
  }
  switch (context.status) {
    case 'PENDING':
      return stoppedWaiting ? (
        <div className={styles.state}>
          <p>The weather has not arrived yet.</p>
          <p>
            <button type="button" className={styles.button} disabled={checking} onClick={onCheck}>
              {checking ? 'Checking…' : 'Check again'}
            </button>
          </p>
        </div>
      ) : (
        <p className={styles.muted}>Getting the weather…</p>
      );
    case 'ERROR':
      return (
        <p className={styles.muted}>
          Weather not available: the weather service could not answer for this incident.
        </p>
      );
    default:
      // UNAVAILABLE, or PARTIAL without the weather: there is no weather for this report.
      return (
        <p className={styles.muted}>
          Weather not available: there is no weather for the time this incident was reported.
        </p>
      );
  }
}

function Weather({ weather, capturedAt }: { weather: WeatherContext; capturedAt: string | null }) {
  return (
    <>
      <dl className={styles.facts}>
        <dt>Weather</dt>
        {/* A condition added to the API before this app knows it is not left blank. */}
        <dd>{WEATHER_LABELS[weather.condition] ?? NOT_AVAILABLE}</dd>
        {capturedAt && (
          <>
            <dt>Recorded</dt>
            <dd>
              <time dateTime={capturedAt}>{formatInstant(capturedAt)}</time>
            </dd>
          </>
        )}
      </dl>
      <p className={styles.attribution}>
        <a className={styles.link} href={weather.source.url} target="_blank" rel="noreferrer">
          {weather.source.attribution}
          <span className={styles.visuallyHidden}> (opens in a new tab)</span>
        </a>
      </p>
    </>
  );
}

function isRefused(error: unknown): boolean {
  return (
    error instanceof ApiCallError &&
    error.failure.kind === 'problem' &&
    (error.failure.status === 403 || error.failure.status === 404)
  );
}
