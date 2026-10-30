import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';

import './global.css';
import { routes } from './routing/routes';
import { SessionProvider } from './session/SessionContext';
import { applyDesignTokens } from './theme';

const root = document.getElementById('root');
if (!root) {
  throw new Error('The #root element is missing from index.html');
}

applyDesignTokens(document.documentElement);
const router = createBrowserRouter(routes);

createRoot(root).render(
  <StrictMode>
    <SessionProvider>
      <RouterProvider router={router} />
    </SessionProvider>
  </StrictMode>,
);
