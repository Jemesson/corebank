#!/bin/bash
set -e

PGDATA="${PGDATA:-/var/lib/postgresql/data}"

if [ ! -s "$PGDATA/PG_VERSION" ]; then
  echo "[Replica] volume is empty - cloning primary"
  mkdir -p "$PGDATA"
  rm -rf "${PGDATA:?}/"*
  chown -R postgres:postgres "$PGDATA"

  su-exec postgres env PGPASSWORD="$REPLICATION_PASSWORD" \
    pg_basebackup \
      --host="$PRIMARY_HOST" --port=5432 --username="$REPLICATION_USER" \
      --pgdata="$PGDATA" --format=plain --wal-method=stream \
      --write-recovery-conf --slot=corebank_replica_slot --no-password --progress

  echo "[Replica] cloned primary successfully"
fi

chown -R postgres:postgres "$PGDATA"
chmod 0700 "$PGDATA"

exec su-exec postgres postgres
