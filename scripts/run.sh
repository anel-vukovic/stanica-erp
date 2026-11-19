#!/usr/bin/env bash
# Gradi aplikaciju ako je kod mijenjan, pa je pokreće.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f target/stanica-erp.jar ] || [ -n "$(find src pom.xml -newer target/stanica-erp.jar -print -quit)" ]; then
  echo "Gradim aplikaciju (mvn package)..."
  mvn -q -B package -DskipTests
fi

exec java -jar target/stanica-erp.jar "$@"
