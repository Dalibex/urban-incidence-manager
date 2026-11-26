# Contributing to UrbanPulse

UrbanPulse is built by a university team. This guide says how to take part in it, in three parts:

| Part | For | Sections |
| --- | --- | --- |
| [🌍 For everyone](#-for-everyone) | Anyone | [Report a bug or request a feature](#report-a-bug-or-request-a-feature) · [Report a vulnerability](#report-a-vulnerability) · [Propose a change](#propose-a-change) |
| [📏 Conventions for every change](#-conventions-for-every-change) | Every change, whoever makes it | [Failure and limit cases](#failure-and-limit-cases) · [Branches](#branches) · [Commits](#commits) · [Pull requests](#pull-requests) · [Changelog](#changelog) · [Architecture decisions](#architecture-decisions) |
| [👥 Team workflow](#-team-workflow) | The team members, who have write access | [Team and roles](#team-and-roles) · [Workflow at a glance](#workflow-at-a-glance) · [Issues](#issues) · [Testing lifecycle](#testing-lifecycle) · [Dependabot pull requests](#dependabot-pull-requests) · [Review and merge](#review-and-merge) · [Release review](#release-review) · [Protection of `main`](#protection-of-main) · [Versioning](#versioning) |

Everyone who takes part in the project agrees to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

Write issues, pull requests and commits in English. To set up the project locally, follow the
[development guide](docs/development.md). This document does not repeat it.

## 🌍 For everyone

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

> [!CAUTION]
> Never open a public issue for a vulnerability. Follow the [security policy](SECURITY.md), which
> uses GitHub private vulnerability reporting.

### Propose a change

If you are not part of the team and want to fix something, comment on its issue first and wait until a
team member agrees, so the work is not done twice. Then work on a fork and open a pull request that
follows the [Conventions for every change](#-conventions-for-every-change), as any team member does.
The `pr-title` job and the CI checks run on it like on any other pull request (for a
first-time contributor, after a maintainer approves the run), and a team member reviews it and merges
it.

Only team members are assigned issues or move cards on the project board.

## 📏 Conventions for every change

These conventions apply to every change, whoever makes it: a team member or anyone who proposes
one from a fork.

### Failure and limit cases

Acceptance criteria that only describe the case where everything goes well leave the rest untested.
Every feature, bug fix or task that changes behavior, and its `[Test]`, cover the cases of this list
that apply; a case that does not apply says why:

- no network or a timeout, and a `5xx`;
- `401`, `403`, `404`, `409`, `422` and `429`;
- a double submission;
- the app closes halfway;
- start-up without network;
- two people change the same thing at once.

`401` with a session refresh is tested once, in `api-client`. Each endpoint only tests `401`
without a token, so the same thing is not tested many times.

### Branches

> [!IMPORTANT]
> Never commit directly to `main`.

Create one branch per issue from an up-to-date `main`:

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

Update the branch with `main` while you work. Before the merge it must be up to date: the ruleset
blocks the merge until it is (**Update branch** on the pull request, or merge `main` into your
branch). Delete the branch after the merge.

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
See *The hook in every clone* in the [CI guide](docs/ci.md#the-hook-in-every-clone) if it does not run.

### Pull requests

- One issue or sub-issue per pull request; the last one of an issue also closes its parent and its
  `[Test]` (see [Sub-issues](#sub-issues)). Keep it small enough to review in one sitting.
- Open it as a **draft** as soon as you push the first commit, and mark it **Ready for review** when
  it is done. The cards on the board follow the pull request status.
- Fill in the [pull request template](.github/pull_request_template.md).
- **Title:** a Conventional Commit, such as `feat(incidents): add incident registration endpoint`. The
  `pr-title` job rejects anything else, and with squash merge the title becomes the commit on `main`.
- **Linked issue:** write `Closes #N` as plain text in the description, never inside a code block.
  GitHub closes the issue automatically only when the pull request is merged into `main`; for other
  target branches, close it by hand after the merge.
- Set the same labels as the issue, its priority label included, and the same milestone. Add the
  `agent-assisted` label when an AI agent created or significantly assisted the change.
- Before asking for review, run the checks of the parts you changed (*Before pushing* in the
  [CI guide](docs/ci.md#before-pushing)) and update the documentation affected by the change.
- Never include secrets, `.env` files or personal data.

#### When a pull request is done

A pull request is ready for review when:

- the required checks pass, and `CHANGELOG.md` has its entry if users can notice the change;
- every case of the `[Test]` that this pull request covers, and every failure and limit case of its
  issue that applies, is covered;
- the documents that describe the changed behavior change in the same pull request. If it adds or
  removes an endpoint, a screen, a profile, a variable or a module, that includes *Current Scope* in
  the README and the status of the requirement in `docs/requirements.md`, and a screen or action a user
  can see also changes its section of `docs/user-guide.md`;
- every behavior it describes in `docs/security.md` or `docs/api.md` has a test that checks it;
- what it documents without being built says `Planned: #N`, and a field the backend does not send
  does not appear in the contract's examples;
- ideas that are not part of the issue are one line each under *Noticed, not done* in the pull
  request, and are not built there.

#### Required checks

A pull request can only be merged when these CI jobs pass:

| Job | Checks |
| --- | --- |
| `lint` | No versioned `.env`, same database image everywhere, valid Compose files with only `web` published, formatting |
| `contract` | The OpenAPI contract is valid |
| `architecture` | The C4 model is valid and its draw.io file is the one it generates |
| `frontend` | Formatting, lint, types, tests and build of the web and mobile clients |
| `test` | Backend unit and integration tests, coverage and static analysis |
| `build` | Packaging and a smoke test of the backend image |
| `pr-title` | The pull request title follows Conventional Commits |

The [CI guide](docs/ci.md) explains each job and what to do when one fails.

#### Database changes

If the pull request changes the database schema, follow the migration rules in the
[database guide](docs/database.md): a new Flyway migration, never an edited one, and the schema
diagram and the datasets updated in the same pull request.

#### Architecture changes

The C4 model is [`docs/architecture/workspace.dsl`](docs/architecture/workspace.dsl). If the pull
request adds, removes or changes a container, a component, a relationship, a deployment node or the
status of an ADR shown in the model, update `workspace.dsl` and regenerate
`docs/architecture/UrbanPulse-C4.drawio` in the same pull request, from the repository root:

```bash
docker compose -f docs/architecture/compose.yaml run --rm drawio
```

The draw.io file is an output of the DSL: never edit it by hand, because the next regeneration
overwrites the change.

### Changelog

From the first release on `main`, every pull request that adds a feature, fixes a bug or changes
behavior adds a short entry to [`CHANGELOG.md`](CHANGELOG.md), under `[Unreleased]`, in the same pull
request. Use the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) sections (`Added`, `Changed`,
`Fixed`, `Removed`) and link the issue or pull request.

The changelog tells the people who use or deploy UrbanPulse what changed between versions, so it only
lists changes they can notice:

| Goes in the changelog | Does not |
| --- | --- |
| New features and endpoints (`Added`) | Documentation, guides and ADRs |
| Changes to existing behavior, the API contract, configuration or environment variables (`Changed`) | Refactorings with no change in behavior |
| Removed features or endpoints (`Removed`) | Tests, CI and repository tooling |
| Bugs fixed in a released version (`Fixed`) | Formatting and dependency bumps without a visible effect |

Keep each entry consistent with the rest of the file:

- `Fixed` is only for bugs that exist in a released version. A bug in something that is still under
  `[Unreleased]` is not a separate entry: correct the original entry if its description changes.
- If a later pull request changes or removes something still under `[Unreleased]`, edit or delete its
  entry instead of adding a contradictory one.
- Write each entry from the user's point of view, as one short noun phrase, like the existing ones.

### Architecture decisions

A significant technical decision (technology, architecture, persistence, security, infrastructure) is
recorded as an ADR in [`docs/adr/`](docs/adr/), using the [ADR template](.github/adr-template.md) and the
ADR issue template. A new ADR starts as **Proposed** and becomes **Accepted** only when the team agrees.
An ADR that records a decision of a [design document](docs/design/) names it in its *Design document*
line, and the design document links its ADRs once they exist.

## 👥 Team workflow

This part applies to the team members, who have write access to the repository: how they plan,
build, review and merge every change, including documentation.

### Team and roles

The roles below organize the work on this repository. The Members column shows how our team assigns
them, as a reference; update it whenever the assignment changes.

| Role | Responsibility | Members |
| --- | --- | --- |
| Maintainer | Manages the repository settings, branch rules, releases and long-lived branches | Dalibex |
| Developer | Implements issues, opens pull requests and answers review comments | Everyone |
| Reviewer | Reviews pull requests and approves them | Pablit023, AlejandroCuetoDiaz |
| Tester | Writes the acceptance tests of each feature and validates that it works as described in its issue | Lewas88 |
| Product owner | Prioritizes issues and plans the milestones | Fjjorda |

A person can hold more than one role, but nobody approves their own pull request: a reviewer other
than its author approves it. When no reviewer can review in time and the pull request cannot wait,
for example a critical fix, another team member who is not its author may approve it, and says why
in the review.

### Workflow at a glance

1. Pick an issue from the **Ready** column of the project board and assign it to yourself.
2. Create a branch from the latest `main`, push the first commit and open a **draft** pull request
   that closes its issue (`Closes #N`) and, if it is the last pull request of that issue, its
   `[Test]` (see [Sub-issues](#sub-issues)). The tester adds the acceptance tests to it (see
   [Testing lifecycle](#testing-lifecycle)). The cards move to **In progress** by themselves.
3. Commit small changes with Conventional Commits.
4. Mark the pull request **Ready for review** when it is done (see
   [When a pull request is done](#when-a-pull-request-is-done)). The cards move to **In review** by
   themselves.
5. Fix what the CI or the reviewer reports, pushing to the same branch.
6. A reviewer approves, and you merge with **Squash and merge**. The issues close and move to
   **Done** by themselves.

The only card you move by hand is **Backlog → Ready**, when the issue is planned for the sprint.

`main` is the only long-lived branch. The application skeleton was built on a separate
`boilerplate` branch and merged into `main` as version `0.0.1`.

### Issues

Every change starts from an issue, opened from one of these templates:

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

Record dependencies in the issue's **Relationships**, with **Blocked by**: its pull request is not
merged until the blocking one is, because it would not work without it. A `[Test]` issue is not
blocked by its feature: it is worked on inside the draft pull request of its feature, while the
feature is built. The checkpoint issue of a version uses **Blocked by** for its scope instead (see
[Versioning](#versioning)).

An issue blocked by the checkpoint of an earlier version waits until that version is released: it
does not start, and no pull request is opened for it. Only its documents can start before: an ADR,
a design document, or the table of cases of its `[Test]`.

A blocked issue can still start when it only needs what the blocker looks like from outside, and
that is already in `main`: the OpenAPI contract and its generated types, or an accepted decision.
Its tests simulate the missing part, and the manual test and the merge wait for the blocker. It
waits to start when it needs the blocker's code: a migration or entity it uses, a backend class it
calls, a shared component still being written, or a contract not decided yet. The *Dependencies*
field of the issue says both, with `Start:` and `Merge after:`. Each issue still gets its own
branch from `main`, never from the blocker's branch.

Every feature, bug and task names, in its *Failure and limit cases* field, the cases of
[Failure and limit cases](#failure-and-limit-cases) that apply to it, and in *Out of scope* what it
does not do.

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

#### Sub-issues

Split an issue into sub-issues when its pieces have different blockers, or when someone else waits
for one of them.

- Each sub-issue has its own pull request, which says `Closes #<sub-issue>`.
- The parent stays open until every sub-issue is done. The last pull request also says
  `Closes #<parent>` and `Closes #<test>`.
- There is one `[Test]` per parent. Each sub-issue lists which cases of that `[Test]` it covers,
  and the tester adds them to the sub-issue's pull request.

### Testing lifecycle

Each feature or task that changes behavior has a `[Test]` sub-issue whose table of test cases is the
specification of the feature, written from the issue and the [OpenAPI contract](docs/api/openapi.yml).
The code and the acceptance tests are written in parallel on the same draft pull request, so
neither waits for the other: the developer works from the issue, the tester from the `[Test]` and
the contract, and each one starts as soon as they can. What is fixed is the end, not the order:
the pull request is reviewed only when both are complete.

| Step | Who | What |
| --- | --- | --- |
| 1. Start | Developer | Creates the branch and opens a **draft** pull request from the first commit, for every issue that has testing. |
| 2. Build | Developer | Implements the feature, with unit tests for the internal logic. |
| 3. Test | Tester | At the same time, adds the acceptance tests to the draft pull request: HTTP, integration, contract and components. |
| 4. Evidence | Tester | Runs the manual cases on the pull request's commit and adds the evidence, with that commit, to *Manual Evidence* in the pull request description. |
| 5. Review | Developer | Marks it ready for review when every case of the `[Test]` that this pull request covers is covered and the checks pass. |

- A feature is merged only with its tests.
- The tester and the developer push to the same branch: pull before pushing and avoid editing the same
  files at the same time.
- Failing tests never reach `main`: the required checks block the merge until they pass.
- The contract of an endpoint is merged before or with its implementation ([API guide](docs/api.md)).

### Dependabot pull requests

Dependabot opens its own pull requests for vulnerable or outdated dependencies. They follow this
workflow, with three differences:

1. **A `[Task]` issue is the card on the board.** It has the labels `dependencies`, `security` when it
   fixes an alert, the priority, the area and the size, and the milestone of the sprint, and it is
   assigned to whoever handles it. The Dependabot pull request itself is not added to the board.
2. **The description keeps Dependabot's text.** Put the [pull request template](.github/pull_request_template.md)
   on top (Type of Change *Maintenance* and `Closes #N` of the task), and leave everything Dependabot
   wrote below it, under `## Dependabot Changes`. The pull request gets the labels and milestone of
   the issue.
3. **Each fixed alert gets its row** in *Security findings* of `docs/security.md`, committed on the
   Dependabot branch: `docs(security): record the <package> Dependabot alerts`.

> [!TIP]
> Write each alert as a link, `[#N](https://github.com/Dalibex/urban-incidence-manager/security/dependabot/N)`,
> never as a bare `#N`, which GitHub links to issue N, and keep alert numbers out of titles.

### Review and merge

1. The author requests a review from a reviewer and fixes the comments with new commits on the same
   branch.
2. The pull request needs **one approval**, all the required checks in green and the branch up to
   date with `main`. Authors never approve their own pull requests.
3. If you push a change that is not trivial after the approval (new behavior, a fix the reviewer has
   not seen), ask for a new review before merging. The ruleset does not dismiss the approval by
   itself, so this is your responsibility. Updating the branch with `main` or fixing formatting does
   not need a new review.
4. **The author merges** with **Squash and merge**, keeping the pull request title as the commit
   message. Merge commits and rebase merges are not used.
5. The branch is deleted after the merge. GitHub does it automatically; if it does not, the author
   deletes it.
6. The issue closes and moves to **Done** by itself. If the pull request did not target `main`, the
   author closes the issue by hand.

A review is judged against the acceptance criteria, this guide and `AGENTS.md`. A finding needs a
`file:line` and a concrete failure; a style preference is not a finding.

### Release review

Each pull request is reviewed against its issue, so before every release someone reviews the whole
for bugs. A version is never released without it (see [Versioning](#versioning)). The review has an
end:

- **Scope:** only the diff since the previous release (`git diff <release>..origin/main`), plus a
  short checklist: the seven cases of section 9 of `AGENTS.md`, consistency between the documents and
  the code, `CHANGELOG.md` and the status of the ADRs.
- **Judged against:** the acceptance criteria, this guide and `AGENTS.md`, not style preferences.
- **Excluded:** what is already planned in a milestone and what is in *Accepted Risks* of the
  [architecture overview](docs/architecture.md).
- **Threshold:** each high and medium finding, with a `file:line` and a concrete failure scenario,
  gets its own issue and **blocks the release being prepared**. The low ones go to one issue of this
  release that does not block it: the team fixes the ones it chooses before the release, and the
  rest move to the next release and are listed as known issues in the release notes.
- **Verification:** before being reported, each finding is checked against the current `main` and
  the open issues and pull requests.
- **Stop:** one review pass and one verification pass. A second review only re-checks the list and
  does not look for new findings. After the fixes, a short pass on the release candidate checks
  them; a bug found later, before the release, follows the same threshold.
- **Decision:** the product owner can move a medium finding to the next release only when the
  release has a deadline it would miss; it is then listed as a known issue.

| Severity | Meaning |
| --- | --- |
| High | Breaks a flow, loses data or opens a security hole |
| Medium | Incorrect behavior with a workaround, or a document that contradicts the code |
| Low | Anything else |

### Protection of `main`

`main` is protected by a branch ruleset that matches this guide:

- Pull request required, with one approval and squash merge only. An approval is not dismissed by
  later pushes (see *Review and merge*).
- Required status checks: `lint`, `contract`, `architecture`, `frontend`, `test`, `build` and `pr-title`, with the
  branch up to date with `main`.
- Linear history, no force pushes and no deletion of the branch.

The maintainer configures it as described in the [CI guide](docs/ci.md#protect-main-with-a-ruleset).

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

#### What a version contains

Every version, before and after `1.0.0`, follows these rules. The `0.x` versions are checkpoints on
the way to `1.0.0`, the final delivery and the first stable version. The number says what a version
holds:

| Number | Goes up for | Example |
| --- | --- | --- |
| Patch | Only fixes of bugs of a released version | `0.3.1`, `1.0.1` |
| Minor | New features that break nothing; the usual checkpoint | `0.4.0`, `1.1.0` |
| Major | A change that breaks a client: an incompatible contract or a removed feature | `2.0.0` |

Each version is defined by its scope, not by a date:

- **A checkpoint issue per version**, `[Task]: Checkpoint vX.Y.Z`, whose **Blocked by** lists the
  issues that must be closed for it: features, tasks, their `[Test]`, the decisions its work starts
  from (ADRs, design documents), the previous checkpoint and the bugs of its review. An issue that
  blocks no checkpoint does not hold any release. The milestone of an issue says when it is worked
  on; the checkpoint it blocks says in which version it ships.
- **One version at a time.** Each issue in the scope of a version, and each of its open
  sub-issues, is also **blocked by the checkpoint of the previous version**, so its work starts when
  that version is released (see [Issues](#issues) for what can start before).
- **The scope grows while the version is prepared.** Any issue the version needs, opened before it
  is released, is added to the **Blocked by** of its checkpoint: a bug, a small change, or a feature
  nobody planned but the version cannot ship without. Its issue says so, and the product owner
  agrees. Whatever does not need to be in this version goes to the next checkpoint instead.
- **Released when every blocker is closed.** A date is a deadline, not the trigger: if it comes
  first, the product owner moves blockers to the next version, never a high bug, and the release
  notes say what moved.
- **Only the blockers merge while a release is being prepared**, and the fixes of its low-findings
  issue. The release being prepared is the open checkpoint with the lowest version. A release is cut
  from `main`, so any other pull request waits until the release is published, unless it has no
  changelog entry (documentation, tests, CI).
- **A bug review before every release**, as [Release review](#release-review) says. A high or
  medium bug found before the release, in that review or by anyone meanwhile, gets its issue and is
  added to the **Blocked by** of the checkpoint. A low one goes to the low-findings issue of this version,
  which never blocks it: the team decides which ones are fixed before the release, and the rest
  move to the next version and to the known issues of the release notes.
- **An idea is not in any version until it has an issue.** The product owner then places it: in the
  version being prepared, as a blocker, if that version needs it, or in a later one.
- **A fix that cannot wait for the next version** is released as a patch (`0.3.1`), with its own
  checkpoint issue blocked by the fixes and a review limited to them, and only *Fixed* entries in
  the changelog, when `main` has nothing else since the last release; otherwise it goes out with the
  next version.

A `0.x` release is published as a GitHub pre-release with its known issues. From `1.0.0`, a version
may have release candidates first (`1.1.0-rc.1`); how a version reaches production is decided when
there is one.
