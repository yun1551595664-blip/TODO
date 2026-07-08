#!/usr/bin/env bash
set -euo pipefail

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

backup_dir="${BACKUP_DIR:-backups}"
db_name="$(get_env DB_NAME issue_ops)"
db_username="$(get_env DB_USERNAME root)"
db_password="$(get_env DB_PASSWORD root123456)"
mysql_port="$(get_env MYSQL_PORT 3306)"
timestamp="$(date +%Y%m%d-%H%M%S)"
output="${backup_dir}/${db_name}-${timestamp}.sql.gz"

mkdir -p "$backup_dir"

if command -v docker >/dev/null 2>&1 && docker compose ps -q mysql >/dev/null 2>&1 && [ -n "$(docker compose ps -q mysql)" ]; then
  docker compose exec -T mysql sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' | gzip > "$output"
else
  MYSQL_PWD="$db_password" mysqldump -h "${DB_HOST:-127.0.0.1}" -P "$mysql_port" -u "$db_username" "$db_name" | gzip > "$output"
fi

printf 'backup created: %s\n' "$output"
