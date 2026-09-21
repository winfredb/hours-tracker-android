#!/usr/bin/env bash
#
# scripts/ci_poll.sh — poll GitHub Actions for a workflow run on this repo.
#
# Usage:
#   scripts/ci_poll.sh                  # latest run
#   scripts/ci_poll.sh <sha>            # latest run whose head_sha starts with <sha>
#   scripts/ci_poll.sh --run <id>       # a specific run id
#   scripts/ci_poll.sh --interval 10 --timeout 900   # tuning (seconds)
#
# Auth (in order of preference):
#   1. $GITHUB_TOKEN, if set
#   2. the https credentials already embedded in the `origin` remote
# The token is never printed, logged, or written to disk.
#
# Exit status: 0 = succeeded, 1 = failed/cancelled, 2 = script/API error.
#
# No jq dependency — JSON is parsed with python3.

set -uo pipefail

REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || { echo "ERROR: not in a git repo" >&2; exit 2; }
cd "$REPO_ROOT"

REMOTE_URL=$(git remote get-url origin 2>/dev/null) || true
SLUG=$(printf '%s' "$REMOTE_URL" | sed -E 's|.*github\.com[:/]||; s|\.git$||')
[ -n "$SLUG" ] || { echo "ERROR: cannot derive owner/repo from the origin remote" >&2; exit 2; }
API="https://api.github.com/repos/$SLUG"

if [ -n "${GITHUB_TOKEN:-}" ]; then
  CURL_AUTH=(--user "x-access-token:${GITHUB_TOKEN}")
else
  CREDS=$(printf '%s' "$REMOTE_URL" | sed -E 's|https://([^@]+)@.*|\1|')
  [ -n "$CREDS" ] || { echo "ERROR: no GITHUB_TOKEN and no credentials in origin remote" >&2; exit 2; }
  CURL_AUTH=(--user "${CREDS}")
fi
unset GITHUB_TOKEN CREDS REMOTE_URL 2>/dev/null || true

# --- args -------------------------------------------------------------------
WANT_SHA=""
WANT_RUN=""
INTERVAL=15
TIMEOUT=600
while [ $# -gt 0 ]; do
  case "$1" in
    --run)      WANT_RUN="${2:-}"; shift 2 ;;
    --interval) INTERVAL="${2:-15}"; shift 2 ;;
    --timeout)  TIMEOUT="${2:-600}"; shift 2 ;;
    -h|--help)  sed -n '3,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*)         echo "ERROR: unknown option $1" >&2; exit 2 ;;
    *)          WANT_SHA="$1"; shift ;;
  esac
done

# --- http -------------------------------------------------------------------
# Note: the body goes to a file rather than stdout, because assigning from a
# command substitution would run gh_get in a subshell and lose HTTP_CODE.
BODY_FILE=$(mktemp)
trap 'rm -f "$BODY_FILE"' EXIT

HTTP_CODE=""
gh_get() {  # $1 = API path; body -> $BODY_FILE, status -> $HTTP_CODE
  HTTP_CODE=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' "${CURL_AUTH[@]}" \
      -H 'Accept: application/vnd.github+json' "$API$1" 2>/dev/null) || HTTP_CODE=000
}

check_code() {  # $1 = context label
  case "$HTTP_CODE" in
    200) return 0 ;;
    401|403) echo "ERROR: GitHub API auth rejected (HTTP $HTTP_CODE) in $1." >&2
             echo "       The embedded token may lack Actions:read, or have expired." >&2
             exit 2 ;;
    *) echo "ERROR: GitHub API returned HTTP ${HTTP_CODE:-none} in $1." >&2; exit 2 ;;
  esac
}

# Dotted-path JSON getter, e.g. jget 'workflow_runs[0].head_sha'
jget() {
  python3 -c '
import json, re, sys
path = sys.argv[1]
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(3)
for tok in re.findall(r"[^.\[\]]+|\[\d+\]", path):
    if tok.startswith("["):
        d = d[int(tok[1:-1])]
    else:
        if not isinstance(d, dict) or tok not in d:
            sys.exit(3)
        d = d[tok]
print("" if d is None else d)
' "$1"
}

# --- resolve the run --------------------------------------------------------
if [ -n "$WANT_RUN" ]; then
  RID="$WANT_RUN"
else
  gh_get "/actions/runs?per_page=30"; check_code "list runs"
  if [ -n "$WANT_SHA" ]; then
    RID=$(python3 -c '
import json, sys
want = sys.argv[1]
for r in json.load(sys.stdin).get("workflow_runs", []):
    if r.get("head_sha", "").startswith(want):
        print(r["id"]); break
' "$WANT_SHA" < "$BODY_FILE")
    [ -n "$RID" ] || { echo "ERROR: no workflow run found for sha $WANT_SHA" >&2; exit 2; }
  else
    RID=$(jget 'workflow_runs[0].id' < "$BODY_FILE")
    [ -n "$RID" ] || { echo "ERROR: no workflow runs found for $SLUG" >&2; exit 2; }
  fi
fi

gh_get "/actions/runs/$RID"; check_code "get run $RID"
NAME=$(jget 'name' < "$BODY_FILE")
SHA=$(jget 'head_sha' < "$BODY_FILE" | cut -c1-7)
BRANCH=$(jget 'head_branch' < "$BODY_FILE")

echo "RUN    $RID  ($NAME)"
echo "SHA    $SHA  branch=$BRANCH"
echo "URL    https://github.com/$SLUG/actions/runs/$RID"
echo

# --- poll -------------------------------------------------------------------
DEADLINE=$(( SECONDS + TIMEOUT ))
STATUS=""; CONCLUSION=""
while :; do
  gh_get "/actions/runs/$RID"; check_code "poll run $RID"
  STATUS=$(jget 'status' < "$BODY_FILE")
  CONCLUSION=$(jget 'conclusion' < "$BODY_FILE")
  printf '  [%4ds] status=%s conclusion=%s\n' "$(( SECONDS - (DEADLINE - TIMEOUT) ))" "$STATUS" "${CONCLUSION:--}"
  [ "$STATUS" = "completed" ] && break
  if [ "$SECONDS" -ge "$DEADLINE" ]; then
    echo "ERROR: timed out after ${TIMEOUT}s (still $STATUS)" >&2
    exit 2
  fi
  sleep "$INTERVAL"
done

# --- report -----------------------------------------------------------------
echo
echo "=== RESULT: $CONCLUSION ==="
echo
echo "=== JOBS / STEPS ==="
gh_get "/actions/runs/$RID/jobs"; check_code "jobs for run $RID"
python3 -c '
import json, sys
for job in json.load(sys.stdin).get("jobs", []):
    print("{}  [{}]".format(job.get("name"), job.get("conclusion") or job.get("status")))
    for s in job.get("steps", []):
        mark = {"success": "ok", "skipped": "--", "failure": "XX"}.get(s.get("conclusion"), "..")
        print("   {}  {}".format(mark, s.get("name")))
' < "$BODY_FILE"
echo

if [ "$CONCLUSION" = "success" ]; then
  echo "CI PASSED"
  exit 0
fi
echo "CI FAILED ($CONCLUSION)" >&2
exit 1