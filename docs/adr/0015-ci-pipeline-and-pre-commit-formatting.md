# ADR-0015: CI pipeline with check-only jobs and pre-commit formatting

- **Status:** Accepted
- **Decision Makers:** Project Team
- **Date:** 2026-10-10

## Context

The project follows GitHub Flow: `main` must always be stable, every change goes through a branch
and a Pull Request, automatic tests run on the PR, and commits use Conventional Commits. The
specification also asks that every relevant change keeps its tests, C4 model and ADRs up to date,
and the application follows the twelve-factor methodology ([ADR-0011](0011-deployment-12factor.md)).
The build already declares the quality tools (Spotless with google-java-format AOSP, JaCoCo with
an 80 % line coverage threshold, SpotBugs), but no pipeline runs them.

Several problems appeared while preparing the CI:

- `spotless:check` is bound to the `validate` phase, so any local build fails on a trailing space,
  and the developer has to remember to run `spotless:apply`. Formatting by hand is a recurring
  source of red pipelines for reasons that are not real defects.
- Letting the CI run `spotless:apply` and push a "fix formatting" commit was considered, but it
  needs write permissions, does not work on protected branches or fork PRs, does not re-trigger
  the workflows when the commit is made with `GITHUB_TOKEN`, leaves local clones out of date and
  adds noise commits to the history.
- The only test started the application with `spring.docker.compose.skip.in-tests=false`, so it
  depended on `docker compose` and a local `.env` file. That cannot work in CI and couples the
  tests to the developer's machine.

How should the CI be organised, and where should code formatting happen, so that `main` stays
stable and its history clean without the CI failing for trivial reasons?

## Decision

We will use **GitHub Actions with check-only jobs**, **formatting in a versioned `pre-commit`
hook**, and **squash merging** into `main`.

### Workflows and jobs

| Workflow | Job | Checks | Local equivalent |
|---|---|---|---|
| `ci.yml` | `lint` | No versioned `.env`; same PostgreSQL image in Compose, Testcontainers and CI; Compose file is valid; formatting (`spotless:check`) | `./mvnw spotless:check` |
| `ci.yml` | `test` | Unit tests (`*Test`, Surefire), integration tests (`*IT`, Failsafe + Testcontainers), merged coverage ≥ `jacoco.minimum.coverage`, SpotBugs | `./mvnw verify` |
| `ci.yml` | `build` | Exactly one jar per commit; the jar starts with configuration from environment variables only, `/actuator/health` is `UP` on a non-default port and it stops on SIGTERM; the jar is published as an artifact | `./mvnw -DskipTests package` + `java -jar` |
| `pr-title.yml` | `pr-title` | PR title in Conventional Commits (error); description links an issue (warning) | — |

- `lint`, `test` and `build` run in parallel and each one fails for a single class of reason:
  `test` and `build` skip the formatting check, so a whitespace problem only turns `lint` red.
- The job names are the required status checks of the `main` ruleset and are not renamed without
  updating it. Workflows have no `paths:` filters, so a required check is always reported.
- Runs on a PR are cancelled by a newer push to the same PR; every job has a timeout; Maven
  dependencies are cached by `actions/setup-java`.

### Local build equals CI

Spotless (`validate`), Failsafe, the JaCoCo merge/report/check and SpotBugs (`verify`) are bound
to Maven phases in `pom.xml`. `./mvnw verify` therefore runs the same checks as `lint` and `test`;
the workflow only adds flags to separate the gates into steps. Integration tests start their own
PostGIS container through Testcontainers (`TestcontainersConfiguration`, `@ServiceConnection`) and
never use `docker-compose.yaml`, which stays a local development tool. `ModularityTest` verifies
the module boundaries of [ADR-0010](0010-modular-monolith.md) as a plain unit test.

### Formatting flow

- `.githooks/pre-commit` runs `spotless:apply` **only on the staged Java files** and re-stages
  them, so the commit is created already formatted. Commits without Java files do not start Maven.
  If a staged file also has unstaged changes, the hook stops instead of mixing them into the
  commit. It can be skipped once with `SKIP_FORMAT=1` or `--no-verify`.
- The hook is versioned and enabled with `git config core.hooksPath .githooks`. A Maven profile
  (`install-git-hooks`, active when `CI` is not set) runs that command on any local build, so no
  manual step is needed per clone.
- The CI never modifies code: `lint` runs `spotless:check` as a safety net for commits that bypass
  the hook (GitHub web editor, `--no-verify`, a clone without the hook).
- `.editorconfig` and `.gitattributes` (LF line endings) align editors with the formatter, and the
  google-java-format version is pinned in `pom.xml`.

### Guaranteeing history of `main`

Only squash merging is allowed. Each PR becomes one commit on `main` whose message is the PR title,
which `pr-title` validates. Intermediate commits of a branch never reach `main`. The `main`
ruleset requires a PR, the checks `lint`, `test`, `build` and `pr-title`, linear history, and
blocks force pushes and deletion.


## Considered Options

- **Pre-commit hook formats, CI only checks, squash merge** — Commits are formatted before they
  exist, no extra commits or history rewriting, the CI needs only read permissions, and failures in
  `lint` only happen when the hook was bypassed.
- **CI formats and pushes a commit, squash merge removes it** — Fully automatic for the developer,
  but the CI needs write permissions (and a PAT or GitHub App so the new commit triggers the checks
  again), it does not work on fork PRs or protected branches, and local branches diverge after
  every push.
- **Post-commit hook that amends or squashes the formatting commit** — Rewrites commits after they
  are created, loops unless guarded, and cannot touch commits made by the CI on the remote without
  force-pushing shared history.
- **Check only, format by hand** — The simplest setup, but it relies on every developer
  remembering `spotless:apply`, which is exactly what produced red pipelines over whitespace.


The hook plus check-only CI was selected because it keeps formatting automatic for developers and
the CI simple and read-only. Squash merging keeps `main` clean whatever happens on the branches.
Three parallel jobs give a clear signal per type of failure without the cost of the previous
pipeline.

## Consequences

- **Positives:**
  - `main` only receives code that is formatted, compiles, passes unit and integration tests,
    meets the coverage threshold, has no SpotBugs findings and produces a jar that starts.
  - One readable commit per PR on `main`, with a Conventional Commits message, ready for a
    changelog.
  - `./mvnw verify` reproduces the CI locally; tests need only Docker, not a `.env` file or a local
    database.
  - The failing job (and step) names the cause, so formatting never hides a real test failure.
  - The jar tested in `build` is the one published, one artifact per commit, configured only
    through the environment; local, test and CI databases use the same
    image.
  - The CI token only needs `contents: read`.
- **Negatives:**
  - Each commit with Java files waits a few seconds for Maven to start.
  - The hook can be bypassed (web editor, `--no-verify`, IDE with hooks disabled); `lint` catches
    it, but the developer still has to run `spotless:apply` by hand in that case.
  - A file with staged and unstaged changes stops the commit until it is fully staged or stashed.
  - Integration tests and the `test` job need Docker; `./mvnw verify` does not run without it
    (`-DskipITs` is the workaround).
  - The PostgreSQL image is declared in three places (Compose, Testcontainers, `ci.yml`); `lint`
    keeps them in sync but changing it touches three files.
  - Squash merging loses the individual commits of a branch on `main` (they remain in the closed
    PR).
  - Renaming a job requires updating the ruleset, or every PR stays blocked.
  - Adds Testcontainers and the exec plugin (hook installation) to the build.

## Links Related

- [ADR-0008: Use Spring Boot for the backend](0008-backend.md)
- [ADR-0010: Start with a modular monolith](0010-modular-monolith.md)
- [ADR-0011: Deployment and 12-factor configuration](0011-deployment-12factor.md)
- CI reference and how to scale it: `docs/ci.md`
- One-time setup (hook, squash merge, ruleset): `docs/ci-setup.md`
- Workflows: `.github/workflows/ci.yml`, `.github/workflows/pr-title.yml`
- Hook: `.githooks/pre-commit`
- [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)
- [Spotless Maven plugin](https://github.com/diffplug/spotless/tree/main/plugin-maven)
- [Spring Boot Testcontainers support](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)
