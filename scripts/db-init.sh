#!/usr/bin/env bash
# Pravi bazu StanicaERP iz nule: šema, view-ovi, triggeri, procedure i testni podaci (zadnjih 60 dana).
# Može se pokrenuti više puta; svaki put briše i ponovo pravi bazu.
set -euo pipefail
cd "$(dirname "$0")/.."

NAME=stanica-sql
PASSWORD='Stanica_Demo2026!'

for f in database/0*.sql; do
  echo "→ $f"
  if ! out=$(docker exec -i "$NAME" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$PASSWORD" \
      -C -I -b -f 65001 -h -1 -W < "$f" 2>&1); then
    echo "$out"
    echo "GREŠKA u $f"
    exit 1
  fi
  echo "$out" | grep -E "^(KAL|RAC|FAK|NIV) " | sed 's/^/   dokumenata: /' || true
done

echo "Baza StanicaERP je spremna."
