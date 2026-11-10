import type { components } from '@urbanpulse/api-client';
import { NOT_AVAILABLE } from '@urbanpulse/ui';
import { useId, useState, type FormEvent } from 'react';

import filterStyles from '../incidents/filters/IncidentFilterBar.module.css';
import styles from '../incidents/Incidents.module.css';
import { LoadFailure } from '../incidents/LoadFailure';
import { ApiCallError } from '../query-client';
import { LazyReportChart } from './LazyReportChart';
import { groupLabel } from './report-groups';
import {
  defaultRequest,
  GROUPING_LABELS,
  periodError,
  REPORT_GROUPINGS,
  type ReportGrouping,
  type ReportRequest,
} from './report-period';
import { useIncidentReport, useReportDownload } from './report-queries';
import reportStyles from './Reports.module.css';

type ReportRow = components['schemas']['IncidentReportRow'];

const HOURS = new Intl.NumberFormat('en-GB', { maximumFractionDigits: 2 });

/**
 * The operational report (RF19, #42): the incidents reported in a period, grouped, with their
 * resolution time, and its CSV. The table and the download always use the request that was shown,
 * not what the form holds while it is being edited, so the file has the rows of the screen.
 */
export function ReportsPage() {
  const [draft, setDraft] = useState<ReportRequest>(() => defaultRequest(new Date()));
  const [shown, setShown] = useState<ReportRequest>(draft);
  const [draftError, setDraftError] = useState<string>();
  const report = useIncidentReport(shown);
  const { state: download, download: startDownload, reset: resetDownload } = useReportDownload();
  const toErrorId = useId();

  const show = (event: FormEvent) => {
    event.preventDefault();
    const error = periodError(draft);
    setDraftError(error);
    if (error !== undefined) {
      return;
    }
    resetDownload();
    if (sameRequest(draft, shown)) {
      // The same query key would serve the cached rows: asking again means "bring them up to date".
      void report.refetch();
    } else {
      setShown(draft);
    }
  };

  // The screen checks the period first, so the API refusing it is unexpected, but it still shows
  // next to the field rather than as a failure to load.
  const refusedPeriod =
    report.isError && report.error instanceof ApiCallError && isStatus(report.error, 422);
  const toError =
    draftError ?? (refusedPeriod ? 'The period is not valid. Check both days.' : undefined);

  return (
    <section className={styles.page} aria-labelledby="page-title">
      <header className={styles.header}>
        <h1 id="page-title" className={styles.title}>
          Reports
        </h1>
        <p className={styles.lead}>
          Incidents reported in a period, with their current status. Download the same rows as a CSV
          file.
        </p>
      </header>

      <form className={filterStyles.bar} aria-label="Report period" onSubmit={show} noValidate>
        <label className={filterStyles.field}>
          <span className={filterStyles.label}>From</span>
          <input
            type="date"
            value={draft.fromDay}
            onChange={(event) => setDraft({ ...draft, fromDay: event.target.value })}
            required
          />
        </label>
        <label className={filterStyles.field}>
          <span className={filterStyles.label}>To (included)</span>
          <input
            type="date"
            value={draft.toDay}
            onChange={(event) => setDraft({ ...draft, toDay: event.target.value })}
            aria-invalid={toError !== undefined}
            aria-describedby={toError === undefined ? undefined : toErrorId}
            required
          />
        </label>
        <label className={filterStyles.field}>
          <span className={filterStyles.label}>Group by</span>
          <select
            value={draft.groupBy}
            onChange={(event) =>
              setDraft({ ...draft, groupBy: event.target.value as ReportGrouping })
            }
          >
            {REPORT_GROUPINGS.map((grouping) => (
              <option key={grouping} value={grouping}>
                {GROUPING_LABELS[grouping]}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" className={`${styles.button} ${reportStyles.submit}`}>
          Show report
        </button>
        {toError !== undefined && (
          <p id={toErrorId} className={reportStyles.fieldError} role="alert">
            {toError}
          </p>
        )}
      </form>

      {report.isPending ? (
        <p className={styles.state} role="status">
          Loading the report…
        </p>
      ) : refusedPeriod ? null : report.isError ? (
        <LoadFailure
          what="The report"
          error={report.error}
          retrying={report.isFetching}
          onRetry={() => void report.refetch()}
        />
      ) : report.data.rows.length === 0 ? (
        <div className={styles.state}>
          <p>No incidents were reported in this period.</p>
          <p className={styles.muted}>Choose other days to see their report.</p>
        </div>
      ) : (
        <>
          <div className={reportStyles.toolbar}>
            <p className={styles.muted}>
              {GROUPING_LABELS[shown.groupBy]}, from {shown.fromDay} to {shown.toDay}. Resolution
              time counts only the closed incidents.
            </p>
            <button
              type="button"
              className={styles.button}
              disabled={download.kind === 'downloading'}
              onClick={() => void startDownload(shown)}
            >
              {download.kind === 'downloading' ? 'Downloading…' : 'Download CSV'}
            </button>
          </div>
          {download.kind === 'failed' && (
            <p className={reportStyles.downloadError} role="alert">
              {download.error.failure.kind === 'network'
                ? 'The server cannot be reached, so the file was not downloaded. Check your connection and try again.'
                : 'The file could not be downloaded. Try again in a moment.'}
            </p>
          )}
          <LazyReportChart groupBy={shown.groupBy} rows={report.data.rows} />
          <ReportTable
            groupBy={shown.groupBy}
            rows={report.data.rows}
            loading={report.isFetching}
          />
        </>
      )}
    </section>
  );
}

function ReportTable({
  groupBy,
  rows,
  loading,
}: {
  groupBy: ReportGrouping;
  rows: ReportRow[];
  loading: boolean;
}) {
  return (
    <div className={styles.tableWrapper}>
      <table className={`${styles.table} ${reportStyles.table}`} aria-busy={loading}>
        <caption className={styles.visuallyHidden}>
          Incidents by {GROUPING_LABELS[groupBy].toLowerCase()}
        </caption>
        <thead>
          <tr>
            <th scope="col">{GROUPING_LABELS[groupBy]}</th>
            <th scope="col" className={reportStyles.number}>
              Total
            </th>
            <th scope="col" className={reportStyles.number}>
              Open
            </th>
            <th scope="col" className={reportStyles.number}>
              Closed
            </th>
            <th scope="col" className={reportStyles.number}>
              Rejected
            </th>
            <th scope="col" className={reportStyles.number}>
              Median hours to close
            </th>
            <th scope="col" className={reportStyles.number}>
              95th percentile hours
            </th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const label = groupLabel(groupBy, row.group);
            return (
              <tr key={row.group ?? NOT_AVAILABLE}>
                <th scope="row" className={reportStyles.group} title={label}>
                  {row.group === null ? <span className={styles.muted}>{label}</span> : label}
                </th>
                <td className={reportStyles.number}>{row.total}</td>
                <td className={reportStyles.number}>{row.open}</td>
                <td className={reportStyles.number}>{row.closed}</td>
                <td className={reportStyles.number}>{row.rejected}</td>
                <td className={reportStyles.number}>{hours(row.resolutionHoursP50)}</td>
                <td className={reportStyles.number}>{hours(row.resolutionHoursP95)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

// A group with no closed incident has no resolution time: a dash, never 0.
function hours(value: number | null): string {
  return value === null ? '—' : HOURS.format(value);
}

function sameRequest(a: ReportRequest, b: ReportRequest): boolean {
  return a.fromDay === b.fromDay && a.toDay === b.toDay && a.groupBy === b.groupBy;
}

function isStatus(error: ApiCallError, status: number): boolean {
  return error.failure.kind === 'problem' && error.failure.status === status;
}
