**Status**
- Current as of 2026-07-03.
- This file records the current product surface, not the older homepage-embedded AI module.

**Current Visual Truth**
- Homepage `/`: overview metrics, trend, focus issues, and a lightweight `AI 智能洞察` entry card.
- Data module `/data`: 数据总览，使用治理指数、关键变化、趋势分解、结构剖面和效率剖面。
- Data module `/data/analysis`: 数据分析主界面，使用分析路径、维度树、筛选趋势、较全量差异、问题明细和数据口径。
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
- `GET /api/ai-insights/overview`: returns local rule result immediately with `aiStatus=pending` when model analysis is still loading.
- `GET /api/ai-insights/ai-analysis`: applies model explanation when available.
- `POST /api/ai-insights/sessions`: creates a server-side AI session.
- `POST /api/ai-insights/sessions/{sessionId}/chat/stream`: SSE stream for follow-up analysis.
- `GET /api/reports/overview`: returns data overview metrics and report suggestions.
- `GET /api/reports/analysis`: returns governance score, dimensions, trend, efficiency buckets, issue detail rows, and metric definitions for drill-down analysis.
- AI responses must be based on the current user's visible issue data scope.

**Known Validation Notes**
- If `AI_API_KEY` is absent or the model call fails, the page must show local rule analysis and an explicit unavailable state. It must not pretend fallback text is model output.
- Data scope is enforced by the backend; frontend filtering is only a display aid.
- Docker production entry remains `http://localhost:18000`; Vite development entry remains `http://localhost:5173`.

**Next QA Pass**
- Re-run visual QA at 1920px after AI key configuration.
- Verify streaming output height stays constrained inside the AI assistant panel.
- Verify admin, product, tech, cs, and viewer accounts only see data allowed by their data scope.
