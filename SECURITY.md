# Security Policy

## Supported versions

UrbanPulse has no published release yet. Security fixes are applied to the latest code on the default
branch.

## Reporting a vulnerability

**Do not open a public issue, pull request or discussion for a vulnerability.** Public reports expose the
problem before it is fixed.

Report it privately instead:

1. Open the repository's **Security** tab and choose **Report a vulnerability** (GitHub private
   vulnerability reporting).
2. Describe the problem, the affected part of the system, the steps to reproduce it and its possible
   impact.
3. Do not include real personal data, passwords or tokens in the report.

The maintainers acknowledge the report, agree on a fix and publish it once it is resolved.

## What must never be committed

- `.env` files, passwords, API keys, tokens or any other secret. Only `.env.example`, with placeholder
  values, is versioned.
- Real personal data: names, emails, locations or photos of citizens, including in tests, screenshots,
  issues and pull requests.
- Database dumps or evidence files.

If a secret is committed by mistake, tell the maintainers at once and revoke or rotate it. Removing it
from the latest commit is not enough: it stays in the Git history.
