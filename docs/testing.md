# 🧪 Testing Guide

How the backend and client tests are organised and how to run them. What each CI job checks is in
the [CI guide](ci.md); what to test for each kind of change is in `AGENTS.md`, section 9.

## ☕ Backend

From `backend/`, with Docker running:

Linux or macOS:

```bash
./mvnw verify
```

Windows PowerShell:

```powershell
./mvnw.cmd verify
```

`verify` also checks formatting, coverage and SpotBugs, like the `test` CI job.

### Kinds of tests

Test classes are picked up by name:

| Kind | Class name | Plugin | Needs | Run with |
| --- | --- | --- | --- | --- |
| Unit | `*Test`, `*Tests` | Surefire | Nothing (no Docker, no database) | `./mvnw test` |
| Integration | `*IT` | Failsafe | Docker (Testcontainers) | `./mvnw verify` |

- Skip the integration tests with `./mvnw verify -DskipITs`.
- Integration tests never use `docker-compose.yaml`. An `*IT` imports
  `TestcontainersConfiguration`, which starts a throwaway PostGIS container, and Spring connects to
  it with `@ServiceConnection`. They never touch your local data and need no `.env`.
- The datasets have their own integration tests (`DevelopmentDatasetIT`, `DemoDatasetIT`), each on
  its own PostGIS container ([database guide](database.md)).
- Coverage: `jacoco.minimum.coverage` in `pom.xml`, 80 % of lines combining unit and integration
  tests. `*Application` is excluded.

### HTTP tests against the contract

HTTP integration tests that call `/api/v1` with MockMvc import `ApiContract.EveryResponse`: every
response is then validated against its operation and status in
[`docs/api/openapi.yml`](api/openapi.yml), error bodies included, so a DTO that drifts from the
contract fails the test. Only the response is checked, so a test may send an invalid request on
purpose.

## 📱 Clients

From `frontend/`:

```bash
pnpm test
```

It runs the `test` script of every app and package. Test files sit next to the code they test, as
`*.test.ts` or `*.test.tsx`.

- `apps/web` and `packages/*` use Vitest, with Testing Library (`@testing-library/react`) for the
  web components. Import `describe`, `it`, `expect` and `vi` from `vitest`.
- `apps/mobile` uses Jest with the `jest-expo` preset (set in the `jest` field of its
  `package.json`) and `@testing-library/react-native`. Vitest cannot load React Native, which ships
  untranspiled Flow code; `jest-expo` transforms it and mocks the native modules. `describe`, `it`,
  `expect` and `jest` are globals, typed by `@types/jest`. `render` and `rerender` return a promise:
  `await` them. `jest.setup.ts` sets the variables the app reads when it loads, such as
  `EXPO_PUBLIC_API_BASE_URL`. The timeout per test is 20 s, not Jest's 5 s: with an empty Jest cache,
  as in CI, the first test of each file also pays for transforming React Native.

To run only the tests of one app or package, run `pnpm test` from its folder, for example
`frontend/apps/mobile`. Pass a file or a pattern to run fewer tests, for example
`pnpm test FormMessage`, or `pnpm test --watch` to run them again on every change.

Upgrade `jest-expo` with the Expo SDK (`pnpm exec expo install --fix` in `apps/mobile`), so it matches
the `expo` and `react-native` versions.
