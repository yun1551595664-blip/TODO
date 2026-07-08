#!/usr/bin/env bash
set -euo pipefail

input="${1:-}"
if [ -z "$input" ] || [ ! -f "$input" ]; then
  printf 'usage: CONFIRM_RESTORE=YES bash scripts/mysql-restore.sh <backup.sql|backup.sql.gz>\n' >&2
  exit 1
fi

if [ "${CONFIRM_RESTORE:-}" != "YES" ]; then
  printf 'restore is destructive. rerun with CONFIRM_RESTORE=YES\n' >&2
  exit 1
fi

env_file="${ENV_FILE:-.env}"
declare -A file_env=()

if [ -f "$env_file" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" != *=* ]] && continue
    key="${line%%=*}"
    value="${line#*=}"
    file_env["$key"]="$value"
  done < "$env_file"
fi

get_env() {
  local key="$1"
  local default="${2:-}"
  if [ -n "${!key+x}" ]; then
    printf '%s' "${!key}"
  elif [ -n "${file_env[$key]+x}" ]; then
    printf '%s' "${file_env[$key]}"
  else
    printf '%s' "$default"
  fi
}

db_name="$(get_env DB_NAME issue_ops)"
db_username="$(get_env DB_USERNAME root)"
db_password="$(get_env DB_PASSWORD root123456)"
mysql_port="$(get_env MYSQL_PORT 3306)"

stream_input() {
  case "$input" in
    *.gz) gzip -dc "$input" ;;
    *) cat "$input" ;;
  esac
}

if command -v docker >/dev/null 2>&1 && docker compose ps -q mysql >/dev/null 2>&1 && [ -n "$(docker compose ps -q mysql)" ]; then
  stream_input | docker compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
else
  stream_input | MYSQL_PWD="$db_password" mysql -h "${DB_HOST:-127.0.0.1}" -P "$mysql_port" -u "$db_username" "$db_name"
fi

printf 'restore completed from: %s\n' "$input"
