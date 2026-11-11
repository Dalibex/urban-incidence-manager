import type { components } from '@urbanpulse/api-client';
import { formatInstant, NOT_AVAILABLE } from '@urbanpulse/ui';
import { Fragment, type ReactNode } from 'react';
import { Link, useLocation, useParams } from 'react-router';

import { incidentListOf, listReturnPath, type IncidentList } from '../routing/access';
import { useSession } from '../session/SessionContext';
import { useIncident } from './incident-queries';
import { ACTIONS_TITLE, IncidentActions } from './IncidentActions';
import { IncidentTimeline } from './IncidentTimeline';
import styles from './Incidents.module.css';
import { LoadFailure } from './LoadFailure';
import { isNotFound } from './not-found';
import { PRIORITY_LABELS } from './labels';
import { PhotoGallery } from './PhotoGallery';
import { LoadingSkeleton, SkeletonLine, SkeletonText } from './Skeleton';
import { StatusBadge } from './StatusBadge';

type Incident = components['schemas']['IncidentResponse'];
type IncidentLocation = components['schemas']['IncidentLocation'];

const BACK_LABELS: Record<IncidentList, string> = {
  inbox: 'Back to the inbox',
  workQueue: 'Back to the work queue',
};

/** The titles of the panels that come with the incident, shared by the skeleton. */
const PANEL_TITLES = { report: 'Report', location: 'Location' } as const;

/**
 * The facts every incident has in each panel, drawn while it loads: never a count the user reads.
 * Report: category, priority, reported and last change. Location: the five of {@link LocationPanel}.
 */
const SKELETON_FACTS = { report: 4, location: 5 } as const;

/**
 * Everything about one incident, and the place where the lifecycle actions are added (#46). An
 * operator opens it from the inbox and a technician from the work queue (#187), and it leads back
 * to that list.
 */
export function IncidentDetailPage() {
  const { incidentId = '' } = useParams();
  const incident = useIncident(incidentId);
  const { state } = useLocation();
  const list = incidentListOf(useSession().user?.role);

  return (
    <article className={styles.page} aria-labelledby="page-title">
      <Link className={styles.backLink} to={listReturnPath(state, list)}>
        {BACK_LABELS[list]}
      </Link>
      {incident.isPending ? (
        <LoadingSkeleton what="the incident">
          <IncidentDetailSkeleton />
        </LoadingSkeleton>
      ) : incident.isError ? (
        isNotFound(incident.error) ? (
          <div className={styles.state}>
            <h1 id="page-title" className={styles.title}>
              Incident not found
            </h1>
            <p>It does not exist, or you cannot see it.</p>
          </div>
        ) : (
          <LoadFailure
            what="The incident"
            error={incident.error}
            retrying={incident.isFetching}
            onRetry={() => void incident.refetch()}
          />
        )
      ) : (
        <IncidentDetail incident={incident.data} />
      )}
    </article>
  );
}

function IncidentDetail({ incident }: { incident: Incident }) {
  return (
    <>
      <header className={styles.detailHeader}>
        <h1 id="page-title" className={styles.title}>
          {incident.title}
        </h1>
        <StatusBadge status={incident.status} />
      </header>
      <div className={styles.detail}>
        <IncidentActions incident={incident} />
        <div>
          <ReportPanel incident={incident} />
          <PhotoGallery incidentId={incident.id} />
          <LocationPanel location={incident.location} />
          <IncidentTimeline incidentId={incident.id} />
        </div>
      </div>
    </>
  );
}

/**
 * The parts of {@link IncidentDetail} that come with the incident, in the same places: the title
 * and status, the actions, the report and the location. The photos and the history load on their
 * own once the incident is there, each with its own loading state.
 */
function IncidentDetailSkeleton() {
  return (
    <>
      <div className={styles.detailHeader}>
        <SkeletonLine width="60%" height="var(--font-size-title)" />
        <SkeletonLine width="6em" />
      </div>
      <div className={styles.detail}>
        <div className={`${styles.panel} ${styles.actions}`}>
          <h2 className={styles.panelTitle}>{ACTIONS_TITLE}</h2>
          <SkeletonText widths={['80%', '60%']} />
        </div>
        <div>
          <div className={styles.panel}>
            <h2 className={styles.panelTitle}>{PANEL_TITLES.report}</h2>
            <div className={styles.description}>
              <SkeletonText widths={['100%', '95%', '70%']} />
            </div>
            <FactsSkeleton rows={SKELETON_FACTS.report} />
          </div>
          <div className={styles.panel}>
            <h2 className={styles.panelTitle}>{PANEL_TITLES.location}</h2>
            <FactsSkeleton rows={SKELETON_FACTS.location} />
          </div>
        </div>
      </div>
    </>
  );
}

function FactsSkeleton({ rows }: { rows: number }) {
  return (
    <dl className={styles.facts}>
      {Array.from({ length: rows }, (_, row) => (
        <Fragment key={row}>
          <dt>
            <SkeletonLine width="7em" />
          </dt>
          <dd>
            <SkeletonLine width="60%" />
          </dd>
        </Fragment>
      ))}
    </dl>
  );
}

function ReportPanel({ incident }: { incident: Incident }) {
  return (
    <section className={styles.panel} aria-labelledby="report-title">
      <h2 id="report-title" className={styles.panelTitle}>
        {PANEL_TITLES.report}
      </h2>
      <p className={styles.description}>{incident.description}</p>
      <dl className={styles.facts}>
        <Fact label="Category">
          {incident.category?.name ?? <span className={styles.muted}>Not classified</span>}
        </Fact>
        <Fact label="Priority">
          {incident.priority ? (
            PRIORITY_LABELS[incident.priority]
          ) : (
            <span className={styles.muted}>Not set</span>
          )}
        </Fact>
        {incident.priorityReason && (
          <Fact label="Why this priority">{incident.priorityReason}</Fact>
        )}
        <Fact label="Reported">
          <Instant value={incident.reportedAt} />
        </Fact>
        <Fact label="Last change">
          <Instant value={incident.updatedAt} />
        </Fact>
        {incident.closedAt && (
          <Fact label="Closed">
            <Instant value={incident.closedAt} />
          </Fact>
        )}
      </dl>
    </section>
  );
}

const COORDINATE = new Intl.NumberFormat('en-GB', {
  minimumFractionDigits: 5,
  maximumFractionDigits: 5,
});

const METRES = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 1 });

function LocationPanel({ location }: { location: IncidentLocation }) {
  return (
    <section className={styles.panel} aria-labelledby="location-title">
      <h2 id="location-title" className={styles.panelTitle}>
        {PANEL_TITLES.location}
      </h2>
      <dl className={styles.facts}>
        <Fact label="Address">
          <Known value={location.address} />
        </Fact>
        <Fact label="Neighbourhood">
          <Known value={location.neighborhood} />
        </Fact>
        <Fact label="District">
          <Known value={location.district} />
        </Fact>
        <Fact label="Latitude, longitude">
          {COORDINATE.format(location.latitude)}, {COORDINATE.format(location.longitude)}
        </Fact>
        <Fact label="Accuracy">
          <Known
            value={
              location.accuracy === null ? null : `within ${METRES.format(location.accuracy)} m`
            }
          />
        </Fact>
      </dl>
    </section>
  );
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </>
  );
}

function Known({ value }: { value: string | null }) {
  return value === null ? <span className={styles.muted}>{NOT_AVAILABLE}</span> : <>{value}</>;
}

function Instant({ value }: { value: string }) {
  return <time dateTime={value}>{formatInstant(value)}</time>;
}
