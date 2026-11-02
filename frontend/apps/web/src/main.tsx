import { QueryClientProvider } from '@tanstack/react-query';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';

import './global.css';
import { createQueryClient } from './query-client';
import { routes } from './routing/routes';
import { SessionProvider } from './session/SessionContext';
import { applyDesignTokens } from './theme';

const root = document.getElementById('root');
if (!root) {
  throw new Error('The #root element is missing from index.html');
}

applyDesignTokens(document.documentElement);
const router = createBrowserRouter(routes);
const queryClient = createQueryClient();

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <RouterProvider router={router} />
      </SessionProvider>
    </QueryClientProvider>
  </StrictMode>,
);
