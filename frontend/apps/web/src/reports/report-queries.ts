import { downloadIncidentReport, getIncidentReport } from '@urbanpulse/api-client';
import { useQuery } from '@tanstack/react-query';
import { useRef, useState } from 'react';

import { ApiCallError, dataOf } from '../query-client';
import { useSession } from '../session/SessionContext';
import { csvFileName, toReportQuery, type ReportRequest } from './report-period';

/** The report of a valid request, for the table. */
export function useIncidentReport(request: ReportRequest) {
  const { api } = useSession();
  return useQuery({
    queryKey: ['incident-reports', request],
    queryFn: () => dataOf(getIncidentReport(api, toReportQuery(request))),
  });
}

export type DownloadState =
  { kind: 'idle' } | { kind: 'downloading' } | { kind: 'failed'; error: ApiCallError };

/**
 * Downloads the CSV of a request and hands it to the browser as a file. While one download runs,
 * another one is ignored, so a double click does not save the file twice. A failed download saves
 * nothing.
 */
export function useReportDownload() {
  const { api } = useSession();
  const [state, setState] = useState<DownloadState>({ kind: 'idle' });
  // A ref, not the state: two clicks in the same render would both read the old state.
  const running = useRef(false);

  const download = async (request: ReportRequest) => {
    if (running.current) {
      return;
    }
    running.current = true;
    setState({ kind: 'downloading' });
    try {
      const result = await downloadIncidentReport(api, toReportQuery(request));
      if (!result.ok) {
        setState({ kind: 'failed', error: new ApiCallError(result.failure) });
        return;
      }
      saveFile(result.data, csvFileName(request));
      setState({ kind: 'idle' });
    } finally {
      running.current = false;
    }
  };

  /** Forgets a failed download, so its message does not stay under another report. */
  const reset = () => {
    if (!running.current) {
      setState({ kind: 'idle' });
    }
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
