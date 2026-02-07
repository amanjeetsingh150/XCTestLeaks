#!/bin/bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_FILE="$REPO_ROOT/VERSION"

if [ $# -eq 1 ]; then
  NEW_VERSION="$1"
  echo "$NEW_VERSION" > "$VERSION_FILE"
  echo "Updated VERSION file to $NEW_VERSION"
else
  NEW_VERSION="$(tr -d '[:space:]' < "$VERSION_FILE")"
  echo "Reading version from VERSION file: $NEW_VERSION"
fi

# 1. App.kt — CLI version string
APP_KT="$REPO_ROOT/src/main/java/xctestleaks/App.kt"
sed -i '' -E "s/version = \[\"xctestleaks [0-9]+\.[0-9]+\.[0-9]+\"\]/version = [\"xctestleaks $NEW_VERSION\"]/" "$APP_KT"
echo "  Updated App.kt"

# 2. Homebrew formula — download URL and test assertion
FORMULA="$REPO_ROOT/homebrew/xctestleaks.rb"
sed -i '' -E "s|releases/download/v[0-9]+\.[0-9]+\.[0-9]+/|releases/download/v${NEW_VERSION}/|" "$FORMULA"
sed -i '' -E "s/xctestleaks [0-9]+\.[0-9]+\.[0-9]+/xctestleaks $NEW_VERSION/" "$FORMULA"
echo "  Updated homebrew/xctestleaks.rb"

echo ""
echo "All files bumped to $NEW_VERSION"
echo ""
echo "Next steps:"
echo "  git add -A && git commit -m 'chore: bump version to $NEW_VERSION'"
echo "  git tag v$NEW_VERSION"
echo "  git push origin main --tags"
echo ""
echo "After the release workflow finishes, update the Homebrew SHA256:"
echo "  scripts/update-homebrew-sha.sh <SHA256>"
