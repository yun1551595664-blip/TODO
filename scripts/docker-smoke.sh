#!/usr/bin/env bash
set -euo pipefail

env_file="${1:-${ENV_FILE:-.env}}"
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

backend_port="$(get_env BACKEND_PORT 8080)"
frontend_port="$(get_env FRONTEND_PORT 18000)"
api_url="${API_URL:-http://127.0.0.1:${backend_port}/api}"
frontend_url="${FRONTEND_URL:-http://127.0.0.1:${frontend_port}}"
auth_users="$(get_env AUTH_USERS "admin|admin123|ADMIN|照远")"
first_auth_user="${auth_users%%;*}"
default_username="${first_auth_user%%|*}"
remaining_auth_user="${first_auth_user#*|}"
default_password="${remaining_auth_user%%|*}"
username="${SMOKE_USERNAME:-$default_username}"
password="${SMOKE_PASSWORD:-$default_password}"

wait_for() {
  local name="$1"
  local url="$2"
  local seconds="${3:-90}"
  local start
  start="$(date +%s)"
  until curl -fsS "$url" >/dev/null 2>&1; do
    if [ "$(( $(date +%s) - start ))" -ge "$seconds" ]; then
      printf 'smoke failed: %s not ready at %s\n' "$name" "$url" >&2
      exit 1
    fi
    sleep 2
  done
}

call_api() {
  local path="$1"
  curl -fsS -H "Authorization: Bearer ${token}" "${api_url}${path}" >/dev/null
  printf 'ok: GET %s\n' "$path"
}

wait_for "backend health" "${api_url}/health"
wait_for "frontend" "${frontend_url}"

login_payload="{\"username\":\"${username}\",\"password\":\"${password}\"}"
login_response="$(
  curl -fsS \
    -H "Content-Type: application/json" \
    -d "$login_payload" \
    "${api_url}/auth/login"
)"
token="$(printf '%s' "$login_response" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"

if [ -z "$token" ]; then
  printf 'smoke failed: cannot extract login token for %s\n' "$username" >&2
  exit 1
fi

curl -fsS "${api_url}/readiness" >/dev/null
printf 'ok: GET /readiness\n'

call_api "/auth/me"
call_api "/dashboard/statistics"
call_api "/dashboard/trend?range=8w"
call_api "/reports/analysis"
call_api "/ai-insights/overview"
call_api "/departments?enabledOnly=true"

curl -fsS "$frontend_url" | grep -Eq '<div id="root"|IssueOps'
printf 'ok: frontend shell %s\n' "$frontend_url"
printf 'docker smoke passed\n'
