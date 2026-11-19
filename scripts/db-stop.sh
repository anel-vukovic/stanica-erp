#!/usr/bin/env bash
# Zaustavlja SQL Server (podaci ostaju u kontejneru) i gasi Docker Desktop da ne troši bateriju.
set -euo pipefail

docker stop stanica-sql >/dev/null 2>&1 && echo "SQL Server zaustavljen." || echo "SQL Server nije bio pokrenut."
osascript -e 'quit app "Docker"' >/dev/null 2>&1 && echo "Docker Desktop ugašen." || true
