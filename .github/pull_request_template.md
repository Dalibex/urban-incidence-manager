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

Write it as plain text, not inside a code block, so GitHub links and closes the issue. Add the
`[Test]` sub-issue too. If the issue is split into several pull requests, write `Part of #XX` in all
of them except the last one.

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

## Checklist

Before requesting a review:

- [ ] The checks of the parts I changed pass locally (`docs/ci.md`, section 8.1):
  - [ ] Backend: `./mvnw verify`.
  - [ ] Clients: `pnpm format:check`, `pnpm lint`, `pnpm typecheck`, `pnpm test` and `pnpm build`.
- [ ] The `[Test]` sub-issue is closed by this pull request, or the change has no behavior to test.
- [ ] `CHANGELOG.md` has an entry under `[Unreleased]`, or the change is not one users can notice
      (*Changelog* in `CONTRIBUTING.md`).
- [ ] If the change touches them, they are updated in this pull request: the OpenAPI contract, the
      permission matrix (`docs/security.md`), the database diagram and the C4 model
      (`workspace.dsl` and the regenerated draw.io file).
- [ ] Every security alert raised on this pull request (CodeQL, Dependabot) is fixed or dismissed,
      with its row in *Security findings* (`docs/security.md`).
- [ ] I have updated the documentation where necessary.
- [ ] I have not included credentials or sensitive information.
- [ ] This pull request contains only changes related to this task.
- [ ] The pull request has the labels and milestone of its issue, its priority label included.
