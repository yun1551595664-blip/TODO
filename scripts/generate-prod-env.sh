#!/usr/bin/env bash
set -euo pipefail

output="${1:-.env.production.local}"
source_env="${SOURCE_ENV:-.env}"

if [ -f "$output" ] && [ "${FORCE_OVERWRITE:-false}" != "true" ]; then
  printf 'refusing to overwrite existing %s. Set FORCE_OVERWRITE=true to replace it.\n' "$output" >&2
  exit 1
fi

declare -A file_env=()

if [ -f "$source_env" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" != *=* ]] && continue
    key="${line%%=*}"
    value="${line#*=}"
    file_env["$key"]="$value"
  done < "$source_env"
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

random_hex() {
  local bytes="${1:-24}"
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$bytes"
  else
    LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c "$((bytes * 2))"
  fi
}

db_password="$(random_hex 18)"
auth_secret="$(random_hex 32)"
admin_password="$(random_hex 12)"
sso_callback_secret="$(random_hex 32)"
ai_api_key="$(get_env AI_API_KEY)"
deepseek_api_key="$(get_env DEEPSEEK_API_KEY)"
ai_base_url="$(get_env AI_BASE_URL "$(get_env DEEPSEEK_BASE_URL "https://api.deepseek.com")")"
ai_model="$(get_env AI_MODEL "$(get_env DEEPSEEK_MODEL "deepseek-v4-pro")")"

if [ -z "$ai_api_key" ] && [ -n "$deepseek_api_key" ]; then
  ai_api_key="$deepseek_api_key"
fi

cat > "$output" <<EOF
# Generated production-like deployment file.
# Keep this file out of Git. It contains real secrets.

COMPOSE_PROJECT_NAME=$(get_env COMPOSE_PROJECT_NAME issueops)

BACKEND_PORT=$(get_env BACKEND_PORT 8080)
FRONTEND_PORT=$(get_env FRONTEND_PORT 18000)
MYSQL_PORT=$(get_env MYSQL_PORT 3306)

DB_NAME=$(get_env DB_NAME issue_ops)
DB_USERNAME=$(get_env DB_USERNAME root)
DB_PASSWORD=${DB_PASSWORD:-$db_password}

DOCKER_DNS_PRIMARY=$(get_env DOCKER_DNS_PRIMARY 223.5.5.5)
DOCKER_DNS_SECONDARY=$(get_env DOCKER_DNS_SECONDARY 114.114.114.114)

AUTH_SECRET=${AUTH_SECRET:-$auth_secret}
AUTH_TOKEN_TTL_SECONDS=$(get_env AUTH_TOKEN_TTL_SECONDS 28800)
AUTH_USERS=admin|${ADMIN_PASSWORD:-$admin_password}|ADMIN|照远|全部|ALL
AUTH_SSO_ENABLED=$(get_env AUTH_SSO_ENABLED false)
AUTH_SSO_PROVIDER_NAME=$(get_env AUTH_SSO_PROVIDER_NAME "企业 SSO")
AUTH_SSO_LOGIN_URL=$(get_env AUTH_SSO_LOGIN_URL)
AUTH_SSO_CALLBACK_SECRET=${AUTH_SSO_CALLBACK_SECRET:-$sso_callback_secret}
AUTH_SSO_AUTO_PROVISION=$(get_env AUTH_SSO_AUTO_PROVISION true)
AUTH_SSO_DEFAULT_ROLE=$(get_env AUTH_SSO_DEFAULT_ROLE VIEWER)
AUTH_SSO_DEFAULT_DATA_SCOPE=$(get_env AUTH_SSO_DEFAULT_DATA_SCOPE DEPARTMENT)

ORG_DEPARTMENTS=$(get_env ORG_DEPARTMENTS "全部,产品部,技术部,客服部,管理部")

AI_PROVIDER=$(get_env AI_PROVIDER deepseek)
AI_API_KEY=${AI_API_KEY:-$ai_api_key}
AI_BASE_URL=$ai_base_url
AI_MODEL=$ai_model
AI_TIMEOUT_MS=$(get_env AI_TIMEOUT_MS 60000)
AI_TEMPERATURE=$(get_env AI_TEMPERATURE 0.2)
AI_MAX_TOKENS=$(get_env AI_MAX_TOKENS 2000)

# Backward-compatible DeepSeek variables. Prefer AI_* for new deployments.
DEEPSEEK_API_KEY=${DEEPSEEK_API_KEY:-$deepseek_api_key}
DEEPSEEK_BASE_URL=$(get_env DEEPSEEK_BASE_URL "$ai_base_url")
DEEPSEEK_MODEL=$(get_env DEEPSEEK_MODEL "$ai_model")
EOF

printf 'generated: %s\n' "$output"
printf 'initial admin username: admin\n'
printf 'initial admin password is written to AUTH_USERS in %s\n' "$output"
