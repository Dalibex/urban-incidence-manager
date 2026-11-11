/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the API. Defaults to `/api/v1`, served by the Vite proxy in development. */
  readonly VITE_API_BASE_URL?: string;
  /** `true` shows the quick sign-in buttons of the demo users (#167). Off by default. */
  readonly VITE_QUICK_SIGN_IN?: string;
  /** Password of the demo users, read only when `VITE_QUICK_SIGN_IN` is `true`. */
  readonly VITE_DEMO_PASSWORD?: string;
  /** Style of the base map (ADR-0016). Defaults to `/tiles/style.json`, on the web's own origin. */
  readonly VITE_MAP_STYLE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
