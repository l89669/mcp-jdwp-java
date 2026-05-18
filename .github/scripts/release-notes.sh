#!/usr/bin/env bash
# Generate GitHub Release notes for a tag.
#
# Usage:
#   release-notes.sh <current-tag> [<previous-tag>]
#
# If <previous-tag> is omitted, the previous v* tag (semver-sorted) is used.
# Output is the release body, on stdout.
#
# Format mirrors the Release Generator action template:
#   https://github.com/marketplace/actions/release-generator
#
# Env:
#   GITHUB_REPOSITORY  — "owner/repo" (default: derived from `git remote get-url origin`)
#   GH_TOKEN / GITHUB_TOKEN — used by `gh` to resolve commit author logins via the API.
#                              If absent, falls back to git-log author names (no @-handle).

set -euo pipefail

cur="${1:?usage: release-notes.sh <current-tag> [<previous-tag>]}"
prev="${2:-}"

repo="${GITHUB_REPOSITORY:-}"
if [[ -z "$repo" ]]; then
  remote_url=$(git remote get-url origin 2>/dev/null || true)
  # Accept git@github.com:owner/repo(.git) and https://github.com/owner/repo(.git)
  if [[ "$remote_url" =~ github\.com[:/]([^/]+/[^/]+)(\.git)?$ ]]; then
    repo="${BASH_REMATCH[1]}"
    repo="${repo%.git}"
  else
    echo "error: cannot determine GITHUB_REPOSITORY; set it or run in a checkout with an origin remote" >&2
    exit 1
  fi
fi

app="jdwp-debugging"
version="${cur#v}"

if [[ -z "$prev" ]]; then
  prev=$(git tag --list 'v*' --sort=-v:refname | grep -vxF "$cur" | head -n1 || true)
fi

range_arg=""
if [[ -n "$prev" ]]; then
  range_arg="${prev}..${cur}"
else
  range_arg="$cur"
fi

# Pull commits as: <sha>\t<author-login-or-name>\t<subject>
# Prefer the GitHub compare API (resolves @-handles); fall back to git log.
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT

# jq emits the author column as "@<login>" when GitHub knows the commit's
# author, or the raw git author name otherwise. Downstream rendering then
# just prints whatever it gets — no guessing whether a name is a GH login.
use_api=0
if [[ -n "$prev" ]] && command -v gh >/dev/null 2>&1 && [[ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]]; then
  if gh api "/repos/$repo/compare/$prev...$cur" \
       --jq '.commits[] | [
              .sha,
              (if .author and .author.login then "@" + .author.login else .commit.author.name end),
              (.commit.message | split("\n")[0])
            ] | @tsv' \
       > "$tmp" 2>/dev/null; then
    use_api=1
  fi
fi

if [[ "$use_api" -eq 0 ]]; then
  git log "$range_arg" --reverse --format='%H%x09%an%x09%s' > "$tmp"
fi

# Bucket commits by Conventional Commit type prefix.
# Keys are types; values are accumulated markdown lines.
declare -A bucket_lines
uncat_lines=""

# Stable bucket order + emoji headers (matches release-generator).
bucket_order=(feat fix docs style refactor test chore)
declare -A bucket_header
bucket_header[feat]=":sparkles: Features"
bucket_header[fix]=":bug: Fixes"
bucket_header[docs]=":books: Documentation"
bucket_header[style]=":art: Style changes"
bucket_header[refactor]=":arrows_counterclockwise: Refactors"
bucket_header[test]=":vertical_traffic_light: Tests"
bucket_header[chore]=":gear: Chore"

# build / ci collapse into chore (per the action's grouping).
declare -A bucket_alias
bucket_alias[build]=chore
bucket_alias[ci]=chore

# Skip release-bump commits — they're noise in their own release notes.
skip_pattern='^chore: release '

# Conventional-commit prefix: type, optional (scope), optional !, then ': '.
# Stored in a variable so bash doesn't try to parse `]` inside `[[ =~ ]]`.
cc_pattern='^([a-z]+)(\([^)]*\))?!?:[[:space:]]'

while IFS=$'\t' read -r sha author subject; do
  [[ -z "$sha" ]] && continue
  [[ "$subject" =~ $skip_pattern ]] && continue

  short="${sha:0:7}"
  url="https://github.com/$repo/commit/$sha"

  type=""
  if [[ "$subject" =~ $cc_pattern ]]; then
    raw="${BASH_REMATCH[1]}"
    if [[ -n "${bucket_alias[$raw]:-}" ]]; then
      type="${bucket_alias[$raw]}"
    elif [[ -n "${bucket_header[$raw]:-}" ]]; then
      type="$raw"
    fi
  fi

  # Author column is already formatted by the API jq pipeline as "@login"
  # (when GitHub knows the author) or the raw git name otherwise.
  author_md=""
  [[ -n "$author" ]] && author_md=" ($author)"

  line="- ${subject} — [${short}](${url})${author_md}"

  if [[ -n "$type" ]]; then
    bucket_lines[$type]="${bucket_lines[$type]:-}${line}"$'\n'
  else
    uncat_lines+="${line}"$'\n'
  fi
done < "$tmp"

# ── Render ───────────────────────────────────────────────────────────────────

printf '# %s %s release\n\n' "$app" "$version"

if [[ -n "$prev" ]]; then
  printf '[Compare %s…%s](https://github.com/%s/compare/%s...%s)\n\n' \
    "$prev" "$cur" "$repo" "$prev" "$cur"
fi

printf '## Changelog\n\n'

# Uncategorized first, no header (per template).
if [[ -n "$uncat_lines" ]]; then
  printf '%s\n' "$uncat_lines"
fi

for b in "${bucket_order[@]}"; do
  content="${bucket_lines[$b]:-}"
  [[ -z "$content" ]] && continue
  printf '### %s\n\n%s\n' "${bucket_header[$b]}" "$content"
done

# Pull requests section — this repo pushes direct to main, so always empty.
# Kept for template fidelity. If you start using PRs, replace with a query:
#   gh api "/repos/$repo/compare/$prev...$cur" --jq '.commits[] | .commit.message
#     | match("\\(#([0-9]+)\\)") | .captures[].string'
printf '## Pull Requests\n\n_None — pushed direct to main._\n'
