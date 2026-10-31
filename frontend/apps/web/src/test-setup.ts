import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Testing Library only unmounts automatically when the test runner exposes globals; ours does not.
afterEach(cleanup);
