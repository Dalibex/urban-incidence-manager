# Setting up the CI pipeline

Step-by-step guide to get the CI, the formatting hook and squash merge running. The day-to-day reference (what each job does, how to scale it, what to do when it fails) is in [ci.md](ci.md). The decision is in [ADR-0015](adr/0015-ci-pipeline-and-pre-commit-formatting.md).

## 0. Before you start

- The repository is on GitHub. `main` is the default branch and the only integration branch. While the CI was rolled out, the integration branch was `boilerplate` (section 5).
- The repository is `urban-incidence-manager` and the backend lives in the **`backend/`** subfolder (that is where `pom.xml` and `mvnw` are). `.github/`, `.githooks/` goes in the repository **root**. That is why `BACKEND_DIR` is `backend` in `.github/workflows/ci.yml` and in `.githooks/pre-commit`. If the backend is moved, change the path in those two places.
- **JDK 25** installed and visible as `JAVA_HOME`. It is the one the CI uses (`JAVA_VERSION`) and the one `java.version` in `pom.xml` asks for. With any other JDK (17, 27…) the build fails.
- Docker running. It is used by the integration tests (Testcontainers) and by `docker compose` for local development.
- `docker-compose.yaml` and `.env.example` in `backend/`. The `lint` job looks for those exact names.
- On Windows, read the tricks in section 2 before you start.

## 1. Step-by-step rollout (the flow that was followed)

The CI was not added all at once. A `ci/pipeline` branch was created from `boilerplate` and **one commit per step** was made, so that the history explains each decision and each step can be checked before moving on. If a check fails, do not go on to the next milestone: fix it in that same step.

```bash
git switch boilerplate && git pull
git switch -c ci/pipeline
```

Maven commands are run **inside `backend/`**; `git add` commands with paths, from the repository root.

| # | Commit | What it contains | How to check it |
|---|---|---|---|
| 1 | `docs(adr): add ADR-0015 for CI pipeline and pre-commit formatting` | The decision | It shows up in `docs/adr/` |
| 2 | `build: add ci plugins and dependencies to pom` | Surefire, Failsafe, Spotless, JaCoCo, SpotBugs, Testcontainers, `install-git-hooks` profile, explicit `exec-maven-plugin` version | `./mvnw validate` with no plugin warnings in IntelliJ or VS Code |
| 3 | `chore: add editorconfig and gitattributes` | LF everywhere, `.env` ignored | `git ls-files --eol` |
| 4 | `style: apply google-java-format and remove placeholder` | Result of `./mvnw spotless:apply` and removal of `HelloWorld` | `./mvnw validate` → `BUILD SUCCESS` |
| 5 | `test: run integration tests against Testcontainers postgis` | `TestcontainersConfiguration` and `UrbanpulseApplicationTests` → `UrbanpulseApplicationIT` (`git mv`) | `./mvnw verify` with Docker: Failsafe runs the `*IT` |
| 6 | `test: add modularity test` | `ModularityTest` (ADR-0010) | `./mvnw test` |
| 7 | `ci: add pre-commit hook to format staged java files` | `.githooks/pre-commit` with `BACKEND_DIR="backend"` and execute permission | `git ls-files --stage .githooks/pre-commit` starts with `100755`; a commit with a badly formatted `.java` comes out formatted |
| 8 | `ci: add lint job` | `ci.yml` with the `lint` job | `actionlint` with no warnings |
| 9 | `ci: do not block commits when maven cannot start in the pre-commit hook` | The hook warns and lets the commit through if Maven cannot start (JAVA_HOME) | See §6 |
| 10 | `ci: add job summary script for tests and coverage` | `.github/scripts/ci_summary.py` | — |
| 11 | `build: run coverage merge on verify` | `merge-coverage` goes from `<phase>none</phase>` to `verify` | `./mvnw clean verify` creates `target/jacoco-merged.exec` and `target/site/jacoco-merged/jacoco.csv` |
| 12 | `ci: add test job with coverage and static analysis gates` | `test` job | `actionlint` |
| 13 | `feat(security): permit actuator health endpoints` | `SecurityConfig` that opens only `/actuator/health/**` | The manual smoke test below answers `200` |
| 14 | `ci: add build job with smoke test` | `build` job | Manual smoke test (section 1.1) |
| 15 | `ci: validate pull request titles` | `pr-title.yml` | `actionlint` |
| 16 | `docs: add ci guide and setup instructions` | `ci.md` and `ci-setup.md` | — |
| 17 | `ci: temporarily run workflows on boilerplate` | `boilerplate` in the triggers (see section 5) | Opening the PR runs the checks |

Before opening the PR, run locally the same things the CI will run:

```bash
./mvnw verify        # lint + test (needs Docker)
git config --get core.hooksPath     # → .githooks
```

`./mvnw verify` also installs the hook: the `install-git-hooks` profile in `pom.xml` runs `git config core.hooksPath .githooks`.

### 1.1 Test the smoke test by hand

The `build` job starts the `.jar` with environment variables only. Reproduce it before pushing (PowerShell; in bash use `export`):

```powershell
docker run -d --name smoke-db -p 5432:5432 -e POSTGRES_DB=urbanpulse -e POSTGRES_USER=urbanpulse -e POSTGRES_PASSWORD=smoke postgis/postgis:18-3.6
./mvnw "-Dspotless.check.skip=true" -DskipTests package
$env:SERVER_PORT="9090"
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/urbanpulse"
$env:SPRING_DATASOURCE_USERNAME="urbanpulse"
$env:SPRING_DATASOURCE_PASSWORD="smoke"
java -jar (Get-ChildItem target\*.jar | Where-Object Name -notlike "*.original").FullName
# in another terminal:
curl.exe -i http://localhost:9090/actuator/health      # 200 and "status":"UP"
```

When you are done: `docker rm -f smoke-db` and close that terminal (the `$env:` variables only live in it).

If it answers `401`, the `SecurityConfig` that opens `/actuator/health/**` is missing.

### 1.2 Open the PR

Open a Pull Request to `boilerplate` (later, to `main`) with the title `ci: add CI pipeline and pre-commit formatting` and `Closes #<issue>` in the description. In the **Checks** tab you should see `lint`, `test`, `build` and `pr-title`.

> If `./mvnw verify` or any job fails in this first PR, see [ci.md section 8](ci.md#8-how-to-make-sure-the-ci-works-and-does-not-fail-for-silly-reasons) and the table in section 6 of this guide.

## 2. The hook in every clone

Nobody on the team has to do anything special: the first `./mvnw test`, `verify`, `package` or `spring-boot:run` in their clone sets `core.hooksPath`. If someone wants to install it before building:

```bash
git config core.hooksPath .githooks
```

Notes:

- **IntelliJ IDEA:** in the commit window, ⚙ → turn on **Run Git hooks** (on by default).
- **VS Code, GitHub Desktop and the terminal** always run the hooks. **GitHub Desktop** uses its own bundled Git and may not read `JAVA_HOME` correctly; see below.
- **Windows:** Git for Windows runs the hook with its `sh`. It works thanks to `.gitattributes`, which forces LF on `.githooks/*`.
- **If the hook cannot start Maven** (for example an old `JAVA_HOME`), it does not block the commit: it warns that it did not format and lets it through. `lint` will catch it in the PR; fix it with `./mvnw spotless:apply`. If the error comes from the code (e.g. a `.java` with a syntax error), the hook does block and shows the first lines of the error.
- If you already used another hook manager (Husky, pre-commit…), `core.hooksPath` replaces it. Move those hooks to `.githooks/`.

### Windows tricks worth knowing

| What | Command | Why |
|---|---|---|
| Set JDK 25 permanently (a **user** variable, which takes precedence over the system one) | `[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\path\to\jdk-25", "User")` and **restart** IntelliJ, GitHub Desktop and your terminals | A system `JAVA_HOME` pointing to JDK 17 makes the hook and Maven fail outside your terminal. `$env:JAVA_HOME = ...` only works in that window |
| Check which JDK Maven uses | `./mvnw -v` (`Java version` line) | It must say 25 |
| Make the hook executable | `git update-index --chmod=+x .githooks/pre-commit` | Windows does not store permissions; without this, Git silently ignores the hook on Linux/macOS |
| Check the permission | `git ls-files --stage .githooks/pre-commit` | It must start with `100755` (not `100644`). Repeat it if the file is recreated (`reset --hard`, copied again) |
| Avoid the `LF will be replaced by CRLF` warning | `git config core.autocrlf false` (in this repo) | `.gitattributes` already forces LF |
| Leave Git's `(END)` screen | `q`, or use `git --no-pager <command>` | It is the pager |

## 3. Squash merge (clean history on `main`)

In **Settings → General → Pull Requests**:

1. Uncheck **Allow merge commits**.
2. Check **Allow squash merging** and, under *Default commit message*, choose **Pull request title and description** (or just **Pull request title**).
3. Uncheck **Allow rebase merging**.
4. Check **Always suggest updating pull request branches** and **Automatically delete head branches**.

Result: each PR enters `main` as **a single commit** whose message is the PR title. That title has already been validated by `pr-title`. The intermediate commits on the branch (`wip`, `fix typo`, `style: …`) do not reach `main`.

> **Exception for the rollout PR to `boilerplate`:** with squash only, the 17 commits from section 1 would become one. For that PR, temporarily turn on **Allow rebase merging**, merge with *Rebase and merge* and turn it off again. That way `boilerplate` keeps the history of each step.
>
> **Exception for the `boilerplate` → `main` PR:** it is merged with a **merge commit**, so every commit and author of the skeleton reaches `main`. Temporarily turn on **Allow merge commits**, merge with *Create a merge commit* and turn it off again, before creating the ruleset from section 4 (its linear history rule would block that merge).

## 4. Protect `main` with a ruleset

> A check only shows up in the ruleset search **after it has run once**. Do this after the PR from step 1.

In **Settings → Rules → Rulesets → New ruleset → New branch ruleset**:

| Field | Value |
|---|---|
| Ruleset name | `main` |
| Enforcement status | **Active** |
| Target branches | **Include default branch** |
| Restrict deletions | ✅ |
| Require linear history | ✅ (fits with squash) |
| Require a pull request before merging | ✅ · *Required approvals*: 1 · *Allowed merge methods*: **Squash** |
| Require status checks to pass | ✅ · add `lint`, `contract`, `frontend`, `test`, `build`, `pr-title` (source: GitHub Actions) |
| Block force pushes | ✅ |


## 5. Test on `boilerplate` before `main`

While the skeleton was built on `boilerplate`, `ci.yml` and `pr-title.yml` only listened to `main`, so a PR to `boilerplate` would run nothing. Commit 17 from section 1 did one thing: it added `boilerplate` to the trigger branches, with a `# TEMPORARY` comment.

```yaml
# ci.yml
on:
  push:
    branches: [main, boilerplate] # TEMPORARY: remove boilerplate before merging into main
  pull_request:
    branches: [main, boilerplate] # TEMPORARY: remove boilerplate before merging into main
```

```yaml
# pr-title.yml
    branches: [main, boilerplate] # TEMPORARY: remove boilerplate before merging into main
```

How it works:

- On a PR, GitHub runs the workflow **from the PR's branch**, so the PR to `boilerplate` already runs the four checks.
- When you merge, the *push* to `boilerplate` runs `ci.yml` again. That is the equivalent of "the CI running on `main`".
- The *Run workflow* button (manual run) only appears when the file is on the default branch (`main`).
- The checks are not mandatory on `boilerplate` unless you create a temporary ruleset there. The one in §4 is for `main`.
- If a job fails, fix it with a new commit on the PR's branch; the PR is re-evaluated on its own.

**Notifications.** You see success as a green ✔ in the PR's *Checks* tab. In *Settings → Notifications → System → Actions*, GitHub has the option *Send notifications for failed workflows only*: by default it only notifies about failures. Uncheck it if you also want a notification when everything passes.

**Moving to `main`.** Those three lines were removed in #28, before the merge. From then on only PRs to `main` run the checks, starting with the `boilerplate` → `main` PR. After the merge, create the `main` ruleset from section 4: the checks will have run by then and will show up in the search.

## 6. Common errors during rollout

These are the ones that came up while setting up the CI in this repository.

| Symptom | Cause | Fix |
|---|---|---|
| IntelliJ: `Plugin 'org.codehaus.mojo:exec-maven-plugin:' not found` (no version) | The plugin has no `<version>` and the Spring Boot parent does not manage it | `exec-maven-plugin.version` property and `<version>` in the plugin. Maven → *Reload All Projects*. VS Code: *Java: Update Project* |
| `BUILD FAILURE` in `spotless:check` on `validate` | Initializr files with tabs | `./mvnw spotless:apply` and a `style:` commit |
| `ExceptionInInitializerError: com.sun.tools.javac.tree.EndPosTable` | Maven runs with a JDK newer (27) than what Lombok supports | `./mvnw -v` must say 25; set `JAVA_HOME` (§2). There is no need to touch the Lombok version |
| `release version 25 not supported` | Maven runs with JDK 17 (old system `JAVA_HOME`) | A **user** variable with JDK 25 and restart the programs |
| Hook: `sh: ./mvnw: No such file or directory` | `BACKEND_DIR="."` but `mvnw` is in `backend/` | `BACKEND_DIR="backend"` in `.githooks/pre-commit` |
| `git ls-files --stage` shows `100644` | Windows does not store the permission | `git update-index --chmod=+x .githooks/pre-commit` and `git commit --amend --no-edit` |
| GitHub Desktop: `The JAVA_HOME environment variable is not defined correctly` / `cd: /home/...: No such file` | GitHub Desktop's bundled Git mangles the `JAVA_HOME` path | The current hook warns and lets the commit through. To format: `./mvnw spotless:apply` or commit from the terminal/IntelliJ |
| `Skipping JaCoCo execution due to missing execution data file ...jacoco-merged.exec` | `merge-coverage` was still on `<phase>none</phase>` | Change it to `verify` and run `./mvnw clean verify` |
| `UrbanpulseApplicationTests` runs under Surefire and needs Docker | A Spring test named `*Tests` | Rename it to `*IT` with `git mv` and use `TestcontainersConfiguration` |
| The `build` job or the manual smoke test returns `401` on `/actuator/health` | Spring Security protects every endpoint by default | A `SecurityConfig` that permits `/actuator/health/**` |
