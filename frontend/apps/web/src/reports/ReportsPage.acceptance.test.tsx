import { onlineManager } from '@tanstack/react-query';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { PATHS } from '../routing/access';
import { fakeApi, json, renderApp } from '../test-utils';

/**
 * Acceptance tests of #65 for the *Reports* screen of #42: T10 (empty state) and T15 (failures of
 * the screen), plus the table and the download of T1 that can be automated. Written from the table
 * of #65 and the contract; the API is a fake that answers as `getIncidentReport` does.
 */

const CSV = '\uFEFFgroup,total,open,closed,rejected,resolutionHoursP50,resolutionHoursP95\r\n';

const ROWS = {
  rows: [
    {
      group: 'IN_PROGRESS',
      total: 2,
      open: 2,
      closed: 0,
      rejected: 0,
      resolutionHoursP50: null,
      resolutionHoursP95: null,
    },
    {
      group: 'CLOSED',
      total: 3,
      open: 0,
      closed: 3,
      rejected: 0,
      resolutionHoursP50: 20,
      resolutionHoursP95: 38.25,
    },
  ],
};

type Answer = (request: Request) => Response | Promise<Response>;

/** A fake API for the report: `report` answers the JSON, `file` the CSV; both record the calls. */
function reportsApi({ report, file }: { report: Answer; file?: Answer }) {
  const requests: Request[] = [];
  const fetch = fakeApi({
    'GET /incident-reports': (request) => {
      requests.push(request);
      const wantsCsv = request.headers.get('Accept') === 'text/csv';
      if (wantsCsv) {
        return (file ?? (() => csvFile(CSV)))(request);
      }
      return report(request);
    },
  });
  const reportCalls = () => requests.filter((r) => r.headers.get('Accept') !== 'text/csv');
  const fileCalls = () => requests.filter((r) => r.headers.get('Accept') === 'text/csv');
  return { fetch, reportCalls, fileCalls };
}

function csvFile(body: string): Response {
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/csv;charset=UTF-8' },
  });
}

const serverError = () =>
  json(500, { type: 'about:blank', title: 'Internal Server Error', status: 500 });

// jsdom has no downloads: the file is caught where the screen hands it to the browser.
let savedFiles: { name: string; blob: Blob }[];
const blobs = new Map<string, Blob>();

beforeEach(() => {
  savedFiles = [];
  blobs.clear();
  let next = 0;
  vi.stubGlobal(
    'URL',
    Object.assign(URL, {
      createObjectURL: (blob: Blob) => {
        const url = `blob:test/${next++}`;
        blobs.set(url, blob);
        return url;
      },
      revokeObjectURL: () => {},
    }),
  );
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    savedFiles.push({ name: this.download, blob: blobs.get(this.href)! });
  });
});

afterEach(() => {
  onlineManager.setOnline(true);
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

function openReports(api: ReturnType<typeof reportsApi>, role: 'ANALYST' | 'ADMINISTRATOR') {
  return renderApp(PATHS.reports, { role, fetch: api.fetch });
}

describe('reports screen', () => {
  it('shows each group with its figures, labels and a dash for no resolution time', async () => {
    const api = reportsApi({ report: () => json(200, ROWS) });
    openReports(api, 'ANALYST');

    const table = await screen.findByRole('table');
    const rows = within(table).getAllByRole('row');
    expect(rows.map((row) => row.textContent)).toEqual([
      'StatusTotalOpenClosedRejectedMedian hours to close95th percentile hours',
      'In progress2200——',
      'Closed30302038.25',
    ]);
    expect(api.reportCalls()[0]!.url).toContain('groupBy=status');
  });

  it('opens for an administrator too', async () => {
    const api = reportsApi({ report: () => json(200, ROWS) });
    openReports(api, 'ADMINISTRATOR');

    expect(await screen.findByRole('heading', { name: 'Reports' })).toBeDefined();
  });

  // T10
  it('says that the period has no incidents instead of an empty table', async () => {
    const api = reportsApi({ report: () => json(200, { rows: [] }) });
    openReports(api, 'ANALYST');

    expect(await screen.findByText('No incidents were reported in this period.')).toBeDefined();
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Download CSV' })).toBeNull();
  });

  // T11 on the screen: a group that is not available says so, never an empty cell.
  it('names the group that is not available', async () => {
    const api = reportsApi({
      report: () =>
        json(200, {
          rows: [
            {
              group: null,
              total: 1,
              open: 1,
              closed: 0,
              rejected: 0,
              resolutionHoursP50: null,
              resolutionHoursP95: null,
            },
          ],
        }),
    });
    openReports(api, 'ANALYST');

    expect(await screen.findByRole('rowheader', { name: 'Not available' })).toBeDefined();
  });

  // T15: a 5xx, retried by the app, then shown with a retry that works.
  it('offers to try again when the server fails, and loads the report when it does', async () => {
    let failures = 3;
    const api = reportsApi({
      report: () => (failures-- > 0 ? serverError() : json(200, ROWS)),
    });
    openReports(api, 'ANALYST');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('The report could not be loaded.');
    expect(screen.queryByRole('button', { name: 'Download CSV' })).toBeNull();

    await userEvent.click(within(alert).getByRole('button', { name: 'Try again' }));

    expect(await screen.findByRole('table')).toBeDefined();
  });

  // T15: no network.
  it('says that the server cannot be reached when there is no network', async () => {
    const api = reportsApi({
      report: () => {
        throw new TypeError('Failed to fetch');
      },
    });
    openReports(api, 'ANALYST');

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
  });

  // T15: the browser knows it is offline. Without a request there is no error to show, so the
  // screen would wait on "Loading the report…" until the network came back.
  it('says that the server cannot be reached when the browser is offline', async () => {
    onlineManager.setOnline(false);
    const api = reportsApi({
      report: () => {
        throw new TypeError('Failed to fetch');
      },
    });
    openReports(api, 'ANALYST');

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain(
      'The server cannot be reached. Check your connection and try again.',
    );
    expect(within(alert).getByRole('button', { name: 'Try again' })).toBeDefined();
  });

  // T15: a period that is not valid shows the field error and asks nothing.
  it('shows why the period is not valid under the dates without asking the API', async () => {
    const api = reportsApi({ report: () => json(200, ROWS) });
    openReports(api, 'ANALYST');
    await screen.findByRole('table');
    const asked = api.reportCalls().length;

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-11-08' } });
    fireEvent.change(screen.getByLabelText('To (included)'), { target: { value: '2026-11-01' } });
    await userEvent.click(screen.getByRole('button', { name: 'Show report' }));

    const error = screen.getByText('The last day must be on or after the first day.');
    // The error is the description of the field, so a screen reader reads it with the date.
    expect(screen.getByLabelText('To (included)').getAttribute('aria-describedby')).toBe(error.id);
    expect(api.reportCalls()).toHaveLength(asked);
  });

  it('refuses a period over 366 days in the same way', async () => {
    const api = reportsApi({ report: () => json(200, ROWS) });
    openReports(api, 'ANALYST');
    await screen.findByRole('table');

    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2025-01-01' } });
    fireEvent.change(screen.getByLabelText('To (included)'), { target: { value: '2026-01-02' } });
    await userEvent.click(screen.getByRole('button', { name: 'Show report' }));

    expect(screen.getByText('The period may not be longer than 366 days.')).toBeDefined();
  });

  // T15: a period the API refuses (422) still shows next to the dates, not as a failure to load.
  it('shows a period the API refuses next to the dates', async () => {
    const api = reportsApi({
      report: () =>
        json(422, {
          type: 'about:blank',
          title: 'Unprocessable Content',
          status: 422,
          code: 'VALIDATION_FAILED',
          errors: [{ field: 'to', message: 'must be later than from' }],
        }),
    });
    openReports(api, 'ANALYST');

    expect(await screen.findByText('The period is not valid. Check both days.')).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  });

  // T1, the part that can be automated: the file has the rows of the table on screen.
  it('downloads the CSV of the report on screen, not of the form being edited', async () => {
    const api = reportsApi({ report: () => json(200, ROWS) });
    openReports(api, 'ANALYST');
    const download = await screen.findByRole('button', { name: 'Download CSV' });

    // Every field of the form is edited, and none is shown.
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-01-01' } });
    fireEvent.change(screen.getByLabelText('To (included)'), { target: { value: '2026-01-31' } });
    await userEvent.selectOptions(screen.getByLabelText('Group by'), 'district');
    await userEvent.click(download);

    await waitFor(() => expect(savedFiles).toHaveLength(1));
    // The file asks for exactly the report on screen: the same days and grouping.
    const shown = new URL(api.reportCalls().at(-1)!.url).search;
    expect(new URL(api.fileCalls()[0]!.url).search).toBe(shown);
    expect(shown).toContain('groupBy=status');
    expect(savedFiles[0]!.name).toMatch(/^incident-report-status-\d{4}-\d\d-\d\d-to-.*\.csv$/);
    // The bytes, not the text: decoding as text would drop the BOM that spreadsheets need.
    const bytes = new Uint8Array(await savedFiles[0]!.blob.arrayBuffer());
    expect([...bytes.slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
    expect(new TextDecoder().decode(bytes)).toBe(CSV.slice(1));
  });

  // Double submission (CONTRIBUTING): while a download runs, another click starts none.
  it('ignores a second click while the file is still downloading', async () => {
    let answer: (response: Response) => void = () => {};
    const api = reportsApi({
      report: () => json(200, ROWS),
      file: () => new Promise<Response>((resolve) => (answer = resolve)),
    });
    openReports(api, 'ANALYST');
    const download = await screen.findByRole('button', { name: 'Download CSV' });

    await userEvent.dblClick(download);
    expect(download.textContent).toBe('Downloading…');
    answer(csvFile(CSV));

    await waitFor(() => expect(savedFiles).toHaveLength(1));
    expect(api.fileCalls()).toHaveLength(1);
  });

  // T15: a download that fails saves nothing and says why.
  it('saves no file when the download fails, and lets the user try again', async () => {
    let fail = true;
    const api = reportsApi({
      report: () => json(200, ROWS),
      file: () => (fail ? serverError() : csvFile(CSV)),
    });
    openReports(api, 'ANALYST');
    const download = await screen.findByRole('button', { name: 'Download CSV' });

    await userEvent.click(download);

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The file could not be downloaded. Try again in a moment.',
    );
    expect(savedFiles).toHaveLength(0);

    fail = false;
    await userEvent.click(download);
    await waitFor(() => expect(savedFiles).toHaveLength(1));
  });

  // T15: no network while downloading.
  it('says that the server cannot be reached when the download has no network', async () => {
    const api = reportsApi({
      report: () => json(200, ROWS),
      file: () => {
        throw new TypeError('Failed to fetch');
      },
    });
    openReports(api, 'ANALYST');

    await userEvent.click(await screen.findByRole('button', { name: 'Download CSV' }));

    expect((await screen.findByRole('alert')).textContent).toContain(
      'The server cannot be reached, so the file was not downloaded.',
    );
    expect(savedFiles).toHaveLength(0);
  });
});
