import { downloadIncidentReport, getIncidentReport } from '@urbanpulse/api-client';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { ApiCallError, dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { csvFileName, toReportQuery, type ReportRequest } from './report-period';

/** The report of a valid request, for the table. */
export function useIncidentReport(request: ReportRequest) {
  const { api } = useSession();
  return useQuery({
    queryKey: ['incident-reports', request],
    queryFn: () => dataOf(getIncidentReport(api, toReportQuery(request))),
    // By default a query waits while the browser says it is offline, and the screen would stay on
    // "Loading the report…" with no way to retry. Asking anyway fails at once, so the screen says
    // that the server cannot be reached and offers *Try again*, as the download does (#65, T15).
    networkMode: 'always',
  });
}

export type DownloadState =
  { kind: 'idle' } | { kind: 'downloading' } | { kind: 'failed'; error: ApiCallError };

/**
 * Downloads the CSV of a request and hands it to the browser as a file. A failed download saves
 * nothing. The screen disables its button while `state` is `downloading`, so a double click starts
 * one download: React applies that state before it handles the next click.
 */
export function useReportDownload() {
  const { api } = useSession();
  const [state, setState] = useState<DownloadState>({ kind: 'idle' });

  const download = async (request: ReportRequest) => {
    setState({ kind: 'downloading' });
    const result = await downloadIncidentReport(api, toReportQuery(request));
    if (!result.ok) {
      setState({ kind: 'failed', error: new ApiCallError(result.failure) });
      return;
    }
    saveFile(result.data, csvFileName(request));
    setState({ kind: 'idle' });
  };

  /** Forgets a failed download, so its message does not stay under another report. */
  const reset = () => {
    setState((current) => (current.kind === 'failed' ? { kind: 'idle' } : current));
  };

  return { state, download, reset };
}

function saveFile(file: Blob, name: string) {
  const url = URL.createObjectURL(file);
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.append(link);
  link.click();
  link.remove();
  // The browser has started the download by now; releasing the URL frees the file's memory.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}
