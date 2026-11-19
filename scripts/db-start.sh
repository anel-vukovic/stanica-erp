#!/usr/bin/env bash
# Pokreće SQL Server 2022 u Dockeru, ograničen na 2 GB RAM-a i 2 CPU-a.
set -euo pipefail

NAME=stanica-sql
PASSWORD='Stanica_Demo2026!'

if ! docker info >/dev/null 2>&1; then
  echo "Pokrećem Docker Desktop..."
  open -a Docker
  until docker info >/dev/null 2>&1; do sleep 2; done
fi

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  docker start "$NAME" >/dev/null
else
  docker run -d --name "$NAME" --platform linux/amd64 --memory 2g --cpus 2 \
    -e ACCEPT_EULA=Y -e "MSSQL_SA_PASSWORD=$PASSWORD" -e MSSQL_MEMORY_LIMIT_MB=1536 -e MSSQL_PID=Developer \
    -p 1433:1433 mcr.microsoft.com/mssql/server:2022-latest >/dev/null
fi

printf "Čekam SQL Server"
until docker exec "$NAME" /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -P "$PASSWORD" -C -Q "SELECT 1" >/dev/null 2>&1; do
  printf "."
  sleep 2
done
echo " spreman na localhost:1433"
