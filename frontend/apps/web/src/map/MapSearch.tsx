import { useId, useState, type FormEvent } from 'react';

import { searchTextProblem } from '../incidents/filters/incident-filters';
import incidentStyles from '../incidents/Incidents.module.css';
import styles from './Map.module.css';

interface MapSearchProps {
  /** The search that applies, from the address; `undefined` for none. */
  value: string | undefined;
  /** Receives the text to search, trimmed and within the limits, or `undefined` to stop searching. */
  onSearch: (text: string | undefined) => void;
}

/**
 * The text search of the map (#266), in its toolbar so it stays at hand in full screen and with the
 * filters folded. It searches on submit, not while typing: one request per search, and never one
 * with a text the API would reject.
 */
export function MapSearch({ value, onSearch }: MapSearchProps) {
  const inputId = useId();
  const problemId = useId();
  const applied = value ?? '';
  const [typed, setTyped] = useState(applied);
  const [lastApplied, setLastApplied] = useState(applied);
  const [problem, setProblem] = useState<string>();
  // A change from outside (Clear filters, the address) replaces what is typed.
  if (applied !== lastApplied) {
    setLastApplied(applied);
    setTyped(applied);
    setProblem(undefined);
  }

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const text = typed.trim();
    if (text === '') {
      setProblem(undefined);
      onSearch(undefined);
      return;
    }
    const found = searchTextProblem(text);
    setProblem(found);
    if (!found) {
      onSearch(text);
    }
  };

  return (
    <form role="search" className={styles.search} onSubmit={submit} noValidate>
      <label htmlFor={inputId} className={incidentStyles.visuallyHidden}>
        Search
      </label>
      <input
        id={inputId}
        type="search"
        className={styles.searchInput}
        placeholder="Title or description"
        value={typed}
        onChange={(event) => setTyped(event.target.value)}
        aria-invalid={problem ? true : undefined}
        aria-describedby={problem ? problemId : undefined}
      />
      <button type="submit" className={incidentStyles.button}>
        Search
      </button>
      {problem && (
        // An alert: after pressing Search the focus is on the button, where a description of the
        // box is not read.
        <p id={problemId} className={styles.searchProblem} role="alert">
          {problem}
        </p>
      )}
    </form>
  );
}
