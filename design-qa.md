**Status**
- Current as of 2026-07-07.
- This file records the current product surface, not the older homepage-embedded AI module.

**Current Visual Truth**
- Homepage `/`: overview metrics, trend, focus issues, and a lightweight `AI 智能洞察` entry card.
- Data module `/data`: 数据总览，使用治理指数、关键变化、趋势分解、结构剖面、效率剖面和可钻取数据集。
- Data module `/data?tab=trend|structure|efficiency`: 数据模块聚焦页签，只展示对应剖面，URL 可分享。
- Data module `/data/analysis`: 数据分析明细主界面，使用分析路径、维度树、筛选趋势、较基准差异、问题明细和数据口径。
- Full AI workspace `/ai-insights`: Action Command Center layout with risk radar, suggested priority queue, AI analysis assistant, and bottom follow-up input.
- Design system source: `frontend/DESIGN_SYSTEM.md`.

**AI Insight Surface**
- The homepage does not render the full `AiInsightCommandCenter`.
- The full command center is the independent left-nav module `AI 洞察`.
- Wide layout uses three columns: risk radar, priority list, and AI assistant.
- AI assistant supports empty state, thinking state, SSE streaming answer, done state, error fallback, quick follow-up prompts, and server-side session history.
- Risk card selection filters the middle priority list.
- Clicking priority rows navigates to issue detail.

**Backend Contract**
- `GET /api/health`: public liveness endpoint for service health checks.
- `GET /api/readiness`: public readiness endpoint for database, AI configuration, and auth-secret status. It must not expose secrets.
- `GET /api/ai-insights/overview`: returns local rule result immediately with `aiStatus=pending` when model analysis is still loading.
- `GET /api/ai-insights/ai-analysis`: applies model explanation when available.
- `POST /api/ai-insights/sessions`: creates a server-side AI session.
- `POST /api/ai-insights/sessions/{sessionId}/chat/stream`: SSE stream for follow-up analysis.
- `GET /api/reports/overview`: returns data overview metrics and report suggestions.
- `GET /api/reports/analysis?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&departments=技术部,产品部`: optional date range and departments; returns governance score, dimensions, trend, efficiency buckets, issue detail rows, available departments, applied filters, and metric definitions for drill-down analysis. Default is the previous complete natural month.
- `GET /api/roles`: returns configurable account roles; supports `enabledOnly=true` for account role selectors.
- `GET /api/roles/permissions`: returns the fixed permission catalog that admins can assign to roles.
- `POST /api/roles`, `PUT /api/roles/{id}`, `PATCH /api/roles/{id}/enabled`, `DELETE /api/roles/{id}`: admin-only role configuration APIs. Built-in roles cannot be disabled or deleted; custom roles in use cannot be disabled or deleted.
- `GET /api/departments?enabledOnly=true`: returns synced department options used by account and role default department selectors.
- `POST /api/departments/sync`: admin-only department sync endpoint for enterprise directory or HR imports.
- `POST /api/departments`, `PUT /api/departments/{id}`, `PATCH /api/departments/{id}/enabled`, `DELETE /api/departments/{id}`: admin-only department configuration APIs. Departments referenced by accounts or role defaults cannot be disabled or deleted.
- `POST /api/auth/sso/callback`: protected SSO callback endpoint. It requires `X-SSO-Token`, binds `ssoSubject`, and can auto-provision accounts when enabled.
- AI responses must be based on the current user's visible issue data scope.

**Known Validation Notes**
- If `AI_API_KEY` is absent or the model call fails, the page must show local rule analysis and an explicit unavailable state. It must not pretend fallback text is model output.
- Data scope is enforced by the backend; frontend filtering is only a display aid.
- Docker production entry remains `http://localhost:18000`; Vite development entry remains `http://localhost:18000`.
- Data module department filtering is recomputed by `/api/reports/analysis`; frontend filtering is only a defensive display aid while refreshed data is loading.
- Account role assignment is driven by `/api/roles?enabledOnly=true`; role permissions, default department, and default data scope are configured in the account management page instead of hardcoded in the frontend.
- Account and role default department selectors are driven by `/api/departments?enabledOnly=true`; department options can be maintained in the account management page or synced from an enterprise directory.
- Production env generation is handled by `scripts/generate-prod-env.sh`; readiness is checked by `scripts/check-env.sh`; real Docker Compose smoke coverage is provided by `scripts/docker-smoke.sh`; MySQL backup/restore are handled by `scripts/mysql-backup.sh` and `scripts/mysql-restore.sh`.

**Next QA Pass**
- Re-run visual QA at 1920px after AI key configuration.
- Playwright E2E currently covers login, issue creation, issue status transition, data module filter/drill-down/CSV export, AI draft confirmation, account disabling, role creation, and department creation.
- Verify streaming output height stays constrained inside the AI assistant panel.
- Verify admin, product, tech, cs, and viewer accounts only see data allowed by their data scope.
