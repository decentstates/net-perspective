#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:?"Usage: $0 <version>"}

[[ "$(git branch --show-current)" == "main" ]] || { echo "Error: not on main branch."; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Error: working tree is not clean."; git status --short; exit 1; }
git fetch origin main
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "Error: branch is not up to date with origin/main."; exit 1; }

PREV_VERSION=$(cat prsp/VERSION)

echo "Will release prsp $PREV_VERSION -> $VERSION"
echo "This will:"
echo "  - Write $VERSION to prsp/VERSION"
echo "  - Commit and tag prsp-v$VERSION"
echo "  - Push main and prsp-v$VERSION to origin (triggering the release workflow)"
echo
read -r -p "Proceed? [y/N] " confirm
[[ "$confirm" == [yY] ]] || { echo "Aborted."; exit 1; }

echo "$VERSION" > prsp/VERSION

git add prsp/VERSION
git commit -m "Release prsp $VERSION"
git tag "prsp-v$VERSION"
git push origin main "prsp-v$VERSION"
