# Contributing to UrbanPulse

This guide describes how the team works on this repository: issues, branches, commits, pull requests,
review and merge. Follow it for every change, including documentation.

To set up the project locally, follow the [development guide](docs/development.md). This document does
not repeat it.

## Team and roles

The roles below organize the work on this repository. The Members column shows how our team assigns
them, as a reference; update it whenever the assignment changes.

| Role | Responsibility | Members |
| --- | --- | --- |
| Maintainer | Manages the repository settings, branch rules, releases and long-lived branches | Dalibex |
| Developer | Implements issues, opens pull requests and answers review comments | Everyone |
| Reviewer | Reviews pull requests, approves them and performs the merge | Pablit023 |
| Tester | Validates that a feature works as described in its issue | Lewas88 |
| Product owner | Prioritizes issues and plans the milestones | Fjjorda |

A person can hold more than one role, but nobody approves their own pull request. When the reviewer
opens a pull request, another team member approves it and the reviewer merges it afterwards.

## Workflow at a glance

1. Pick an issue from the project board, assign it to yourself and move it to **In progress**.
2. Create a branch from the latest `main`.
3. Commit small changes with Conventional Commits.
4. Open a pull request that links the issue and move both cards to **In review**.
5. Fix what the CI or the reviewer reports, pushing to the same branch.
6. The reviewer approves and merges with **Squash and merge**.
7. Move the cards to **Done**.

`main` is the only long-lived branch. The application skeleton was built on a separate
`boilerplate` branch and merged into `main` as version `0.0.1`.

## Issues

Every change starts from an issue. Write issues, pull requests and commits in English.

Create the issue with the template that fits the work:

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

Give each issue its labels, the milestone it belongs to and add it to the project board. Use
`priority: high`, `priority: medium` or `priority: low` to order the work.

## Branches

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

## Commits

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

## Pull requests

- One issue per pull request. Keep it small enough to review in one sitting.
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

### Required checks

A pull request can only be merged when these CI jobs pass:

| Job | Checks |
| --- | --- |
| `lint` | No versioned `.env`, same database image everywhere, valid Compose file, formatting |
| `test` | Unit and integration tests, coverage and static analysis |
| `build` | Packaging and a smoke test of the application |
| `pr-title` | The pull request title follows Conventional Commits |

The [CI guide](docs/ci.md) explains each job and what to do when one fails.

### Database changes

If the pull request changes the database schema, follow the migration rules in the
[development guide](docs/development.md): a new Flyway migration, never an edited one, and the schema
diagram updated in the same pull request.

## Review and merge

1. The author requests a review from a reviewer and fixes the comments with new commits on the same
   branch.
2. The pull request needs **one approval** and all the required checks in green. Authors never approve
   their own pull requests.
3. **The reviewer merges** with **Squash and merge**, keeping the pull request title as the commit
   message. Merge commits and rebase merges are not used.
4. The branch is deleted after the merge. GitHub does it automatically; if it does not, the reviewer
   deletes it.
5. The author moves the cards to **Done** and closes the issue if it was not closed automatically.

## Protection of `main`

`main` is protected by a branch ruleset that matches this guide:

- Pull request required, with one approval and squash merge only.
- Required status checks: `lint`, `test`, `build` and `pr-title`.
- Linear history, no force pushes and no deletion of the branch.

The maintainer configures it as described in the [CI setup guide](docs/ci-setup.md#4-protect-main-with-a-ruleset).

## Changelog

From the first release on `main`, every pull request that adds a feature, fixes a bug or changes
behavior adds a short entry to [`CHANGELOG.md`](CHANGELOG.md), under `[Unreleased]`, in the same pull
request. Use the [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) sections (`Added`, `Changed`,
`Fixed`, `Removed`) and link the issue or pull request.

## Architecture decisions

A significant technical decision (technology, architecture, persistence, security, infrastructure) is
recorded as an ADR in [`docs/adr/`](docs/adr/), using the [ADR template](.github/adr-template.md) and the
ADR issue template. A new ADR starts as **Proposed** and becomes **Accepted** only when the team agrees.

## Security

Read the [security policy](SECURITY.md) before reporting a vulnerability. Do not open a public issue for
it.
