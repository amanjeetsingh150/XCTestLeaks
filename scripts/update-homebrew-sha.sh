#!/bin/bash
set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: scripts/update-homebrew-sha.sh <SHA256>"
  echo "  Get the SHA256 from the release workflow output."
  exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FORMULA="$REPO_ROOT/homebrew/xctestleaks.rb"
SHA256="$1"

sed -i '' -E "s/sha256 \"[a-fA-F0-9_]+\"/sha256 \"$SHA256\"/" "$FORMULA"
echo "Updated homebrew/xctestleaks.rb SHA256 to: $SHA256"