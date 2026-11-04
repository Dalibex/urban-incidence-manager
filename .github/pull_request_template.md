# Description

Briefly describe the changes made and why.

## Type of Change

Select the relevant option:

- [ ] New feature
- [ ] Bug fix
- [ ] Refactoring
- [ ] Documentation
- [ ] Tests
- [ ] Maintenance

## Related Issue

Write it as plain text, not inside a code block, so GitHub links and closes the issue. If the issue
is split into sub-issues, close the sub-issue; the last pull request also closes the parent and its
`[Test]` (*Sub-issues* in `CONTRIBUTING.md`).

Closes #XX

## Changes Made

-

## How Has This Been Tested?

Describe the tests performed to verify the changes, and the manual checks with their result.

## Screenshots

For changes to the web or mobile screens, add a screenshot or a short recording. Delete this section
otherwise.

## Risks

What could break, what the reviewer should look at closely, and how to roll it back if needed (for
example, a migration cannot be undone by reverting the image). Write "None" if there are none.

## Noticed, not done

Ideas that are not part of the issue, one line each. They are not built in this pull request.
Write "None" if there are none.

## Checklist

Before requesting a review (*When a pull request is done* in `CONTRIBUTING.md`). Tick every item
once you have checked it. Each item says when it does not apply: tick it too in that case, so a
ticked list means everything was checked.

- [ ] The checks of the parts I changed pass locally (*Before pushing* in `docs/ci.md`): backend
      `./mvnw verify`; clients `pnpm format:check`, `pnpm lint`, `pnpm typecheck`, `pnpm test` and
      `pnpm build`. Or I changed no backend or client code.
- [ ] Every case of the `[Test]` that this pull request covers is covered, or the change has no
      behavior to test.
- [ ] Every failure and limit case of the issue that applies is covered, or the issue has none.
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]`, or the change is not one users can notice
      (*Changelog* in `CONTRIBUTING.md`).
- [ ] If the change touches them, they are updated in this pull request: the OpenAPI contract, the
      permission matrix (`docs/security.md`), the database diagram and the C4 model
      (`workspace.dsl` and the regenerated draw.io file).
- [ ] Every security alert raised on this pull request (CodeQL, Dependabot) is fixed or dismissed,
      with its row in *Security findings* (`docs/security.md`), or none was raised.
- [ ] The documents that describe the changed behavior are updated, or no documented behavior
      changed. If it adds or removes an endpoint, screen, profile, variable or module, that
      includes the status in the README and in `docs/requirements.md`.
- [ ] Every behavior it describes in `docs/security.md` or `docs/api.md` has a test, and what it
      documents without building says `Planned: #N`. Or it documents no behavior.
- [ ] I have not included credentials or sensitive information.
- [ ] This pull request contains only changes related to this task.
- [ ] The pull request has the labels and milestone of its issue, its priority label included.
