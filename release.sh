#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:?"Usage: $0 <version>"}

echo "Will release prsp $VERSION"
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
