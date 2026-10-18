#!/usr/bin/env bash
set -euo pipefail

# Cuts a production release: tags the current origin/main as the next vMAJOR.MINOR.PATCH and pushes
# the tag, which starts the Release workflow (tests, image, deploy, GitHub release). Merging to main
# deploys nothing; this is the step that does.

usage() {
  cat >&2 <<'EOF'
Usage: scripts/release.sh patch|minor|major [--yes]
       scripts/release.sh vMAJOR.MINOR.PATCH [--yes]

  patch  fixes only
  minor  new features, nothing removed
  major  something users or operators must act on (a breaking change, a one-way data migration)
  --yes  skip the confirmation prompt
EOF
  exit 2
}

bump="${1:-}"
confirm=true
[[ "${2:-}" == "--yes" ]] && confirm=false
[[ -z "$bump" ]] && usage

remote="${RELEASE_REMOTE:-origin}"
git fetch --quiet "$remote" main --tags

last="$(git tag --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -n 1 || true)"
IFS=. read -r major minor patch <<<"${last#v}"
major="${major:-0}" minor="${minor:-0}" patch="${patch:-0}"

case "$bump" in
  major) next="v$((major + 1)).0.0" ;;
  minor) next="v${major}.$((minor + 1)).0" ;;
  patch) next="v${major}.${minor}.$((patch + 1))" ;;
  v*) next="$bump" ;;
  *) usage ;;
esac

if [[ ! "$next" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "$next is not a release tag (vMAJOR.MINOR.PATCH)." >&2
  exit 1
fi
if git rev-parse -q --verify "refs/tags/$next" >/dev/null; then
  echo "$next already exists." >&2
  exit 1
fi

target="$(git rev-parse "$remote/main")"
if [[ -n "$last" && "$(git rev-list -n 1 "$last")" == "$target" ]]; then
  echo "$remote/main is still $last; there is nothing new to release." >&2
  exit 1
fi

echo "Release $next from $remote/main: $(git log -1 --format='%h %s' "$target")"
if [[ -n "$last" ]]; then
  echo "Since $last:"
  git log --first-parent --format='  %h %s' "$last..$target"
else
  echo "This is the first release tag."
fi

if $confirm; then
  read -r -p "Tag and deploy $next to production? [y/N] " answer
  [[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "Nothing tagged."; exit 1; }
fi

git tag -a "$next" -m "Release $next" "$target"
git push "$remote" "refs/tags/$next"
echo "Pushed $next. The Release workflow now tests, publishes and deploys it:"
echo "  https://github.com/rfm-9300/rfm-edubot/actions/workflows/release.yml"
