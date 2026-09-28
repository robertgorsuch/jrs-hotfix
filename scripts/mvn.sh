#!/usr/bin/env bash
# Runs Maven with the JDK 21 this project requires. Usage: scripts/mvn.sh verify
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JRSHOTFIX_JDK:=}"
if [ -z "$JRSHOTFIX_JDK" ]; then
  for c in /usr/lib/jvm/temurin-21-jdk-amd64 /usr/lib/jvm/java-21-openjdk-amd64 "$HOME/tools/jdk-21" "/c/Program Files/Microsoft/jdk-21.0.9.10-hotspot"; do
    [ -x "$c/bin/java" ] && JRSHOTFIX_JDK="$c" && break
  done
fi
[ -x "$JRSHOTFIX_JDK/bin/java" ] || { echo "JDK 21 not found; set JRSHOTFIX_JDK" >&2; exit 1; }
export JAVA_HOME="$JRSHOTFIX_JDK"; export PATH="$JAVA_HOME/bin:$PATH"
exec ./mvnw -B "$@"
