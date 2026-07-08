#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "docs drift: $1" >&2
  exit 1
}

require_text() {
  local file="$1"
  local text="$2"
  grep -Fq "$text" "$file" || fail "$file missing: $text"
}

vite_port="$(
  grep -Eo 'port:[[:space:]]*[0-9]+' frontend/vite.config.ts \
    | head -n 1 \
    | grep -Eo '[0-9]+'
)"

compose_frontend_port="$(
  grep -E '^FRONTEND_PORT=' .env.example \
    | head -n 1 \
    | cut -d= -f2
)"

[ -n "$vite_port" ] || fail "cannot detect Vite dev port from frontend/vite.config.ts"
[ -n "$compose_frontend_port" ] || fail "cannot detect FRONTEND_PORT from .env.example"

require_text README.md "http://localhost:${vite_port}"
require_text README.md "http://localhost:${compose_frontend_port}"
require_text design-qa.md "Docker production entry remains \`http://localhost:${compose_frontend_port}\`; Vite development entry remains \`http://localhost:${vite_port}\`."

required_api_docs=(
  "/api/health"
  "/api/readiness"
  "/api/dashboard/statistics"
  "/api/dashboard/trend?range=8w"
  "/api/reports/analysis?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&departments="
  "/api/ai-insights/overview"
  "/api/ai-insights/sessions/{sessionId}/chat/stream"
  "/api/ai-insights/actions/execute"
  "/api/accounts/{id}/enabled"
  "/api/roles"
  "/api/roles/permissions"
  "/api/roles/{id}/enabled"
  "/api/departments?enabledOnly=true"
  "/api/departments/{id}/enabled"
  "/api/auth/sso/callback"
  "/api/auth/sso/config"
)

for endpoint in "${required_api_docs[@]}"; do
  require_text README.md "$endpoint"
done

required_script_docs=(
  "scripts/generate-prod-env.sh"
  "scripts/check-env.sh"
  "scripts/docker-smoke.sh"
  "scripts/mysql-backup.sh"
  "scripts/mysql-restore.sh"
  ".env.production.example"
)

for script_doc in "${required_script_docs[@]}"; do
  require_text README.md "$script_doc"
done

require_text README.md "npm run test:e2e"
require_text .github/workflows/ci.yml "npm run test:e2e"
require_text frontend/package.json "\"test:e2e\""

echo "documentation contract check passed"
