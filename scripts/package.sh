#!/usr/bin/env bash
# Builds the portable archive for this OS: shaded jar + jlink runtime + launchers.
# Usage: scripts/package.sh <version> <linux-x64|windows-x64>
# Needs JAVA_HOME (JDK 21) and target/jrs-hotfix.jar (scripts/mvn.sh -DskipTests package).
# Runs on Linux and, from Git Bash, on Windows.
set -euo pipefail
cd "$(dirname "$0")/.."
[ $# -eq 2 ] || { sed -n '3p' "$0" | sed 's/^# //' >&2; exit 1; }
VERSION="$1"; PLATFORM="$2"; NAME="jrs-hotfix-$VERSION"; OUT="target/dist/$NAME"
case "$PLATFORM" in linux-x64|windows-x64) ;; *) echo "unknown platform: $PLATFORM" >&2; exit 1 ;; esac
[ -n "${JAVA_HOME:-}" ] || { echo "JAVA_HOME must point at a JDK 21" >&2; exit 1; }
[ -f target/jrs-hotfix.jar ] || { echo "target/jrs-hotfix.jar missing; run scripts/mvn.sh -DskipTests package" >&2; exit 1; }
rm -rf target/dist; mkdir -p "$OUT/bin" "$OUT/lib"
cp target/jrs-hotfix.jar "$OUT/lib/"
MODULES=$("$JAVA_HOME/bin/jdeps" --ignore-missing-deps --print-module-deps --multi-release 21 target/jrs-hotfix.jar | tr -d '\r')
echo "jlink modules: $MODULES,jdk.crypto.ec,jdk.charsets"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES,jdk.crypto.ec,jdk.charsets" --strip-debug --no-header-files --no-man-pages --compress zip-6 --output "$OUT/runtime"
cp dist/bin/jrs-hotfix dist/bin/jrs-hotfix.cmd "$OUT/bin/"; chmod +x "$OUT/bin/jrs-hotfix"
cp README.md LICENSE "$OUT/"
cd target/dist
if [ "$PLATFORM" = "windows-x64" ]; then
  # jar, not PowerShell 5.1's Compress-Archive: that one writes backslashes into entry names
  "$JAVA_HOME/bin/jar" --create --no-manifest --file "$NAME-$PLATFORM.zip" "$NAME"
else
  tar -czf "$NAME-$PLATFORM.tar.gz" "$NAME"
fi
sha256sum "$NAME-$PLATFORM".* > "SHA256SUMS-$PLATFORM"
cat "SHA256SUMS-$PLATFORM"
