#!/usr/bin/env bash
#
# Build the browser client and publish the self-contained static site to the repo's gh-pages branch
# for GitHub Pages.
#
#   Usage: scripts/publish-gh-pages.sh [remote]      (remote defaults to "origin")
#
# Prereqs: a JDK + Maven, and the OpenRSC cache the build bakes from (../openrsc/Client_Base/Cache,
# or -Dopenrsc.cache.dir=...). Enable serving once in the repo: Settings -> Pages -> Deploy from a
# branch -> gh-pages / (root).
set -euo pipefail

cd "$(dirname "$0")/.."
repo_root="$(pwd)"
site="$repo_root/target/client-base-teavm-1.0-SNAPSHOT"
branch="gh-pages"
remote="${1:-origin}"

echo "==> Building the self-contained client (mvn clean package)..."
mvn -q clean package
[ -f "$site/index.html" ] || { echo "error: build did not produce $site/index.html" >&2; exit 1; }

echo "==> Publishing to $remote/$branch..."
worktree="$(mktemp -d)"
cleanup() { git worktree remove --force "$worktree" 2>/dev/null || true; rm -rf "$worktree"; }
trap cleanup EXIT

# Base the gh-pages worktree on the existing remote branch, or start it fresh (orphan).
if git ls-remote --exit-code --heads "$remote" "$branch" >/dev/null 2>&1; then
  git fetch -q "$remote" "$branch"
  git worktree add --force -B "$branch" "$worktree" "$remote/$branch"
else
  git worktree add --force --detach "$worktree" HEAD
  git -C "$worktree" checkout --orphan "$branch"
fi

# Replace the branch contents with the freshly built static site.
git -C "$worktree" rm -rfq . >/dev/null 2>&1 || true
cp -a "$site/." "$worktree/"
rm -rf "$worktree/WEB-INF" "$worktree/META-INF"  # WAR scaffolding, not needed for static hosting
touch "$worktree/.nojekyll"                       # serve every file verbatim (skip Jekyll)

git -C "$worktree" add -A
if git -C "$worktree" diff --cached --quiet; then
  echo "==> Already up to date; nothing to publish."
  exit 0
fi
git -C "$worktree" commit -qm "Publish client build from $(git -C "$repo_root" rev-parse --short HEAD)"
git -C "$worktree" push "$remote" "$branch"
echo "==> Published $branch to $remote."
