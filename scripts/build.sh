#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_JDK17="$ROOT_DIR/.jdks/jdk-17"
LOCAL_JDK21="$ROOT_DIR/.jdks/jdk-21"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x "$LOCAL_JDK21/bin/java" ]]; then
    export JAVA_HOME="$LOCAL_JDK21"
  elif [[ -x "$LOCAL_JDK17/bin/java" ]]; then
    export JAVA_HOME="$LOCAL_JDK17"
  fi
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

JAVA_MAJOR="$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2}')"
if [[ "$JAVA_MAJOR" -lt 17 ]]; then
  echo "Java 17+ is required for this build. Current Java version: $JAVA_MAJOR"
  echo "Set JAVA_HOME to a Java 17+ JDK or place one at:"
  echo "  $LOCAL_JDK17"
  echo "  $LOCAL_JDK21"
  exit 1
fi

cd "$ROOT_DIR"
./gradlew build
