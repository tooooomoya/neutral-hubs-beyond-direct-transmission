#!/bin/bash
#
# Downloads the Gephi Toolkit fat jar (used only for optional GEXF export) from Maven Central
# instead of vendoring the 79 MB binary in git. Run once before the first build:
#
#   ./lib/fetch-deps.sh
#
set -euo pipefail

VERSION="0.10.0"
DEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gephi"
JAR="gephi-toolkit-${VERSION}-all.jar"
URL="https://repo1.maven.org/maven2/org/gephi/gephi-toolkit/${VERSION}/${JAR}"
SHA1="2d3756d5405b2129b096fe89ae2dccff9bfedaa6"

mkdir -p "$DEST_DIR"

if [ -f "$DEST_DIR/$JAR" ]; then
    echo "Already present: $DEST_DIR/$JAR"
else
    echo "Fetching $URL"
    curl -fL -o "$DEST_DIR/$JAR" "$URL"
fi

ACTUAL_SHA1="$(shasum -a 1 "$DEST_DIR/$JAR" | cut -d' ' -f1)"
if [ "$ACTUAL_SHA1" != "$SHA1" ]; then
    echo "Checksum mismatch for $JAR: expected $SHA1, got $ACTUAL_SHA1" >&2
    rm -f "$DEST_DIR/$JAR"
    exit 1
fi

echo "OK: $DEST_DIR/$JAR (sha1 verified)"
