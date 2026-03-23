#!/usr/bin/env bash
set -euo pipefail

VERSION=${1:?"Usage: $0 <version>"}

echo "$VERSION" > prsp/VERSION

git add prsp/VERSION
git commit -m "Release prsp $VERSION"
git tag "prsp-v$VERSION"
git push origin main "prsp-v$VERSION"
