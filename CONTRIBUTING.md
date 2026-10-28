# Contributing to UrbanPulse

UrbanPulse is built by a university team. This guide has two parts:

- **[For everyone](#for-everyone):** how anyone can report a bug, request a feature, report a
  vulnerability or propose a change.
- **[Team workflow](#team-workflow):** how the team members, who have write access to the repository,
  plan, build, review and merge every change, including documentation.

Write issues, pull requests and commits in English. To set up the project locally, follow the
[development guide](docs/development.md). This document does not repeat it.

## For everyone

### Report a bug or request a feature

Anyone with a GitHub account can open an issue. Search the existing issues first, and add a comment
there instead of opening a duplicate. Then choose the template that fits:

| Template | Use it for |
| --- | --- |
| Bug report | Something that does not work as expected |
| Feature request | New functionality |
| Documentation | A mistake or a gap in the documentation |

Blank issues are disabled: every issue starts from a template, which adds its type label. The team
triages new issues: it sets the priority and the milestone, and decides whether and when the work is
done. Do not include real personal data, passwords or tokens in an issue.

### Report a vulnerability

Never open a public issue for a vulnerability. Follow the [security policy](SECURITY.md), which uses
GitHub private vulnerability reporting.

### Propose a change

If you are not part of the team and want to fix something, comment on its issue first and wait until a
team member agrees, so the work is not done twice. Then work on a fork and open a pull request that
follows the [branch](#branches), [commit](#commits) and [pull request](#pull-requests) conventions of
the team. The `pr-title` job and the CI checks run on it like on any other pull request (for a
first-time contributor, after a maintainer approves the run), and a team member reviews it and merges
it.

Only team members are assigned issues or move cards on the project board.

## Team workflow

The rest of this guide applies to the team members.

### Team and roles

The roles below organize the work on this repository. The Members column shows how our team assigns
them, as a reference; update it whenever the assignment changes.

| Role | Responsibility | Members |
| --- | --- | --- |
| Maintainer | Manages the repository settings, branch rules, releases and long-lived branches | Dalibex |
| Developer | Implements issues, opens pull requests and answers review comments | Everyone |
| Reviewer | Reviews pull requests, approves them and performs the merge | Pablit023, AlejandroCuetoDiaz |
| Tester | Writes the acceptance tests of each feature and validates that it works as described in its issue | Lewas88 |
| Product owner | Prioritizes issues and plans the milestones | Fjjorda |

A person can hold more than one role, but nobody approves their own pull request. When a reviewer
opens a pull request, the other reviewer approves it and merges it.

### Workflow at a glance

1. Pick an issue from the **Ready** column of the project board and assign it to yourself.
2. Create a branch from the latest `main`, push the first commit and open a **draft** pull request
   that closes the issue and its `[Test]` sub-issue (`Closes #N`), so the tester can add the
   acceptance tests to it (see [Testing lifecycle](#testing-lifecycle)). Both cards move to
   **In progress** by themselves.
3. Commit small changes with Conventional Commits.
4. Mark the pull request **Ready for review** when its tests pass. The cards move to **In review** by
   themselves.
5. Fix what the CI or the reviewer reports, pushing to the same branch.
6. The reviewer approves and merges with **Squash and merge**. The issues close and move to **Done**
   by themselves.

The only card you move by hand is **Backlog → Ready**, when the issue is planned for the sprint.

`main` is the only long-lived branch. The application skeleton was built on a separate
`boilerplate` branch and merged into `main` as version `0.0.1`.

### Issues

Every change starts from an issue. Besides the templates in [For everyone](#for-everyone), the team
uses the ones for internal work:

| Template | Title prefix | Use it for |
| --- | --- | --- |
| Feature request | `[Feature]: ` | New functionality |
| Bug report | `[Bug]: ` | Something that does not work as expected |
| Task | `[Task]: ` | Technical work with no new behavior |
| 12-Factor | `[12F-]: ` | Applying a Twelve-Factor principle |
| Test | `[Test]: ` | Adding or improving tests |
| ADR | `[ADR]: ADR-XXXX ` | Proposing an architectural decision |
| Documentation | `[Docs]: ` | Documentation changes |
| CI/CD | `[CI]: ` | Pipeline and automation changes |

Give each issue its labels and the milestone it belongs to. Use `priority: high`, `priority: medium`
or `priority: low` to order the work. The issue joins the project board in **Backlog** by itself. Triage
the issues opened by people outside the team in the same way, or close them with a reason.

Record dependencies in the issue's **Relationships**, with **Blocked by**, so the board shows which
issues can start. A `[Test]` sub-issue is blocked by the same issues as its parent, not by the parent
itself: its tests are written while the feature is built, not after it.

#### Project board

| Column | The card gets there when… |
| --- | --- |
| Backlog | The issue is created (automatic) |
| Ready | The team plans it for the sprint (manual) |
| In progress | A **draft** pull request that closes it is opened (automatic) |
| In review | That pull request is marked **Ready for review** (automatic) |
| Done | The issue is closed or the pull request is merged (automatic) |

The `project-status` workflow moves the cards to **In progress** and **In review** from the pull
request status, so it only works when the description says `Closes #N`. It skips pull requests from
forks: for those, the team moves the cards by hand. The [CI guide](docs/ci.md) explains it.

### Testing lifecycle

Each feature or task that changes behavior has a `[Test]` sub-issue whose table of test cases is the
specification of the feature. We follow acceptance test-driven development (ATDD): the acceptance tests
are written first, from the issue and the [OpenAPI contract](docs/api/openapi.yml), and the feature is
implemented until they pass.

| Step | Who | What |
| --- | --- | --- |
| 1. Start | Developer | Creates the branch and opens a **draft** pull request that closes both issues. |
| 2. Red | Tester | Adds the automated acceptance tests (HTTP, integration, contract) to the same branch. They fail until the feature exists. |
| 3. Green | Developer | Implements the feature until they pass, with their own unit tests for the internal logic. |
| 4. Complete | Tester | Adds edge cases, runs the manual cases on the pull request's commit and leaves the evidence in a comment. |
| 5. Review | Developer | Marks the pull request as ready for review. |

- A feature is merged only with its tests, so the feature and its `[Test]` issue close together.
- The tester and the developer push to the same branch: pull before pushing and avoid editing the same
  files at the same time.
- Failing tests never reach `main`: the required checks block the merge until they pass.
- In the web and mobile clients, component tests may be written alongside the screen instead of
  before it, because the interface changes while it is built.

### Branches

Never commit directly to `main`. Create one branch per issue from an up-to-date `main`:

```bash
git switch main
git pull
git switch -c feature/42-incident-registration
```

Name the branch `<type>/<issue>-<short-slug>`, in lowercase with hyphens:

| Prefix | Use |
| --- | --- |
| `feature/` | New functionality |
| `fix/` | Bug fix |
| `docs/` | Documentation |
| `refactor/` | Code changes with no new behavior |
| `test/` | Tests only |
| `ci/` | CI/CD changes |
| `chore/` | Maintenance (dependencies, configuration) |

Keep the branch up to date with `main` while you work, and delete it after the merge.

### Commits

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): `type(optional scope): description`.

| Type | Use |
| --- | --- |
| `feat` | New functionality |
| `fix` | Bug fix |
| `docs` | Documentation |
| `test` | Tests |
| `refactor` | Code change with no new behavior |
| `build` | Build system or dependencies |
| `ci` | CI configuration |
| `chore` | Other maintenance |

Make small, focused commits with a clear description, for example `feat(incidents): add incident
registration endpoint` instead of `changes`.

Java files are formatted automatically by the pre-commit hook, which any local `./mvnw` build installs.
See the [CI setup guide](docs/ci-setup.md) if it does not run.

### Pull requests

- One issue per pull request, plus its `[Test]` sub-issue. Keep it small enough to review in one sitting.
- Open it as a **draft** as soon as you push the first commit, and mark it **Ready for review** when
  its tests pass. The cards on the board follow the pull request status.
- Fill in the [pull request template](.github/pull_request_template.md).
- **Title:** a Conventional Commit, such as `feat(incidents): add incident registration endpoint`. The
  `pr-title` job rejects anything else, and with squash merge the title becomes the commit on `main`.
- **Linked issue:** write `Closes #N` as plain text in the description, never inside a code block.
  GitHub closes the issue automatically only when the pull request is merged into `main`; for other
  target branches, close it by hand after the merge.
- Set the same labels and milestone as the issue, and add the `agent-assisted` label when an AI agent
  created or significantly assisted the change.
- Before asking for review, run `./mvnw verify` and update the documentation affected by the change.
- Never include secrets, `.env` files or personal data.

#### Required checks

A pull request can only be merged when these CI jobs pass:

| Job | Checks |
| --- | --- |
| `lint` | No versioned `.env`, same database image everywhere, valid Compose file, formatting |
| `test` | Unit and integration tests, coverage and static analysis |
| `build` | Packaging and a smoke test of the application |
| `pr-title` | The pull request title follows Conventional Commits |

The [CI guide](docs/ci.md) explains each job and what to do when one fails.

#### Database changes

If the pull request changes the database schema, follow the migration rules in the
[development guide](docs/development.md): a new Flyway migration, never an edited one, and the schema
diagram updated in the same pull request.

### Review and merge

1. The author requests a review from a reviewer and fixes the comments with new commits on the same
   branch.
2. The pull request needs **one approval** and all the required checks in green. Authors never approve
   their own pull requests.
3. **The reviewer merges** with **Squash and merge**, keeping the pull request title as the commit
   message. Merge commits and rebase merges are not used.
4. The branch is deleted after the merge. GitHub does it automatically; if it does not, the reviewer
   deletes it.
5. The issue closes and moves to **Done** by itself. If the pull request did not target `main`, the
   author closes the issue by hand.

### Protection of `main`

`main` is protected by a branch ruleset that matches this guide:

- Pull request required, with one approval and squash merge only.
- Required status checks: `lint`, `test`, `build` and `pr-title`.
- Linear history, no force pushes and no deletion of the branch.

The maintainer configures it as described in the [CI setup guide](docs/ci-setup.md#4-protect-main-with-a-ruleset).

### Versioning

UrbanPulse has a single version, following [Semantic Versioning](https://semver.org/), shared by the
backend, both clients, the shared client packages and the API contract. The release in
[`CHANGELOG.md`](CHANGELOG.md) is the reference; every other file repeats it:

| File | Field |
| --- | --- |
| `backend/pom.xml` | `<version>` |
| `docs/api/openapi.yml` | `info.version` |
| `frontend/apps/*/package.json`, `frontend/packages/*/package.json` | `version` |
| `frontend/apps/mobile/app.json` | `expo.version` |

When the maintainer cuts a release, the same pull request moves the `[Unreleased]` entries under the new
version in the changelog and updates every file in the table. Never change the version of a single part.

### Changelog

From the first release on `main`, every pull request that adds a feature, fixes a bug or changes
behavior adds a short entry to [`CHANGELOG.md`](CHANGELOG.md), under `[Unreleased]`, in the same pull
request. Use the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) sections (`Added`, `Changed`,
`Fixed`, `Removed`) and link the issue or pull request.

### Architecture decisions

A significant technical decision (technology, architecture, persistence, security, infrastructure) is
recorded as an ADR in [`docs/adr/`](docs/adr/), using the [ADR template](.github/adr-template.md) and the
ADR issue template. A new ADR starts as **Proposed** and becomes **Accepted** only when the team agrees.
