#!/usr/bin/env bash
set -euo pipefail

env_file="${1:-.env}"
allow_dev_defaults="${ALLOW_DEV_DEFAULTS:-false}"
require_ai="${REQUIRE_AI:-false}"

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

errors=()
warnings=()

require_present() {
  local key="$1"
  local value
  value="$(get_env "$key")"
  if [ -z "$value" ]; then
    errors+=("$key is required")
  fi
}

check_port() {
  local key="$1"
  local value
  value="$(get_env "$key")"
  if [ -n "$value" ] && ! [[ "$value" =~ ^[0-9]+$ ]]; then
    errors+=("$key must be a numeric port")
  fi
}

if [ ! -f "$env_file" ]; then
  warnings+=("$env_file not found; checking process environment only")
fi

require_present "DB_NAME"
require_present "DB_USERNAME"
require_present "DB_PASSWORD"
require_present "AUTH_SECRET"
require_present "AUTH_USERS"

check_port "BACKEND_PORT"
check_port "FRONTEND_PORT"
check_port "MYSQL_PORT"

db_password="$(get_env DB_PASSWORD)"
auth_secret="$(get_env AUTH_SECRET)"
auth_users="$(get_env AUTH_USERS)"
ai_api_key="$(get_env AI_API_KEY)"
deepseek_api_key="$(get_env DEEPSEEK_API_KEY)"
auth_sso_enabled="$(get_env AUTH_SSO_ENABLED false)"
auth_sso_callback_secret="$(get_env AUTH_SSO_CALLBACK_SECRET)"

if [ "$allow_dev_defaults" != "true" ]; then
  if [ "$db_password" = "root123456" ] || [[ "$db_password" == *CHANGE_ME* ]]; then
    errors+=("DB_PASSWORD must be changed before production deployment")
  fi
  if [ "$auth_secret" = "dev-local-issue-ops-secret-change-me" ] ||
    [ "$auth_secret" = "change-this-to-a-long-random-secret" ] ||
    [[ "$auth_secret" == *CHANGE_ME* ]]; then
    errors+=("AUTH_SECRET must be a real random secret before production deployment")
  fi
  if [[ "$auth_users" == *"admin123"* ]] || [[ "$auth_users" == *"product123"* ]] || [[ "$auth_users" == *CHANGE_ME* ]]; then
    errors+=("AUTH_USERS contains default or placeholder passwords")
  fi
fi

if [ -n "$auth_secret" ] && [ "${#auth_secret}" -lt 32 ]; then
  errors+=("AUTH_SECRET must be at least 32 characters")
fi

if [ "$auth_sso_enabled" = "true" ]; then
  if [ -z "$auth_sso_callback_secret" ]; then
    errors+=("AUTH_SSO_CALLBACK_SECRET is required when AUTH_SSO_ENABLED=true")
  elif [ "$allow_dev_defaults" != "true" ] && [ "${#auth_sso_callback_secret}" -lt 32 ]; then
    errors+=("AUTH_SSO_CALLBACK_SECRET must be at least 32 characters in production")
  fi
fi

if [ "$require_ai" = "true" ] && [ -z "$ai_api_key" ] && [ -z "$deepseek_api_key" ]; then
  errors+=("AI_API_KEY or DEEPSEEK_API_KEY is required when REQUIRE_AI=true")
elif [[ "$ai_api_key" == *CHANGE_ME* ]] || [[ "$deepseek_api_key" == *CHANGE_ME* ]]; then
  errors+=("AI key contains a placeholder value")
elif [ -z "$ai_api_key" ] && [ -z "$deepseek_api_key" ]; then
  warnings+=("AI key is not configured; AI pages will use local rule fallback")
fi

if [ "${#warnings[@]}" -gt 0 ]; then
  printf 'warnings:\n'
  for item in "${warnings[@]}"; do
    printf '  - %s\n' "$item"
  done
fi

if [ "${#errors[@]}" -gt 0 ]; then
  printf 'environment check failed:\n' >&2
  for item in "${errors[@]}"; do
    printf '  - %s\n' "$item" >&2
  done
  exit 1
fi

printf 'environment check passed: %s\n' "$env_file"
