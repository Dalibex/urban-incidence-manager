#!/usr/bin/env bash
# Decides whether a CI run has to check the client workspace (job frontend, ADR-0007).
# Writes clients=true|false to $GITHUB_OUTPUT and a line to the job summary.
#
# Inputs (environment):
#   EVENT        github.event_name
#   BASE_SHA     base commit of the pull request (pull_request events)
#   BEFORE_SHA   commit before the push (push events)
#   FRONTEND_DIR client workspace folder (ci.yml), default frontend
#   HEAD_REF     commit to compare, default HEAD (only overridden to test the script locally)
#
# When in doubt (manual run, first push of a branch, base commit not available) it answers true:
# skipping a check that should have run is worse than running one that was not needed.
set -euo pipefail

frontend_dir="${FRONTEND_DIR:-frontend}"
head_ref="${HEAD_REF:-HEAD}"

# A change in any of these paths can break the clients: the workspace itself, the API contract its
# api-client types are generated from, and the CI files that check it.
client_paths=(
  "$frontend_dir/"
  "docs/api/"
  ".github/workflows/ci.yml"
  ".github/actions/setup-js/"
)

answer() {
  echo "clients=$1" >> "${GITHUB_OUTPUT:-/dev/stdout}"
  echo "$2"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "**Client checks:** $2" >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 0
}

case "${EVENT:-}" in
  pull_request) base="${BASE_SHA:-}" ;;
  push) base="${BEFORE_SHA:-}" ;;
  *) answer true "run (event '${EVENT:-unknown}' always checks the clients)." ;;
esac

# An all-zero "before" is the first push of a new branch: there is nothing to compare with.
if [ -z "$base" ] || [ -z "${base//0/}" ] || ! git cat-file -e "$base^{commit}" 2>/dev/null; then
  answer true "run (no base commit to compare with)."
fi

changed=$(git diff --name-only "$base" "$head_ref")

while IFS= read -r file; do
  for path in "${client_paths[@]}"; do
    if [[ "$file" == "$path"* ]]; then
      answer true "run ($file changed)."
    fi
  done
done <<< "$changed"

answer false "skipped: nothing under ${client_paths[*]} changed."
