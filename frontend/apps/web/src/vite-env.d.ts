/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the API. Defaults to `/api/v1`, served by the Vite proxy in development. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
