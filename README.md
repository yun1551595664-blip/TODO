# 产品与业务问题进度管理看板

公司内部问题闭环管理系统。采用 React + TypeScript + Ant Design 前端、Java 21 + Spring Boot 3 + JPA 后端和 MySQL 8，支持 Docker Compose 一键启动。

## 已实现

- Apple-inspired Design System：颜色、字体、间距、圆角、阴影、按钮、表格、卡片、状态标签统一复用。
- 总览看板：9 项核心指标、本月新增/完成、问题趋势、待处理趋势、重点问题、AI 洞察入口。
- 问题台账：完整 15 个业务字段、搜索、8 类筛选、创建时间范围、分页、TAPD、查看与删除。
- 新增/编辑：完整业务字段、责任归属、TAPD 与附件地址。
- 字段配置：维护问题来源、业务场景、问题类型、影响范围；已被历史问题引用的选项只能停用，不能删除。
- 问题详情：文档式信息、原因/修复/验证、处理时间线、复发信息。
- 状态流转：`待处理 → 处理中 → 待验证 → 已完成`；复发作为独立标记，每次变更自动写入处理记录。
- 处理记录：详情页直接新增。
- 数据模块：数据总览与可钻取数据分析，覆盖治理指数、趋势分解、结构剖面、效率剖面、维度树、问题明细和数据口径；支持周期筛选、部门筛选后端重算、对比上期开关、页签聚焦、CSV 导出和 URL 化钻取。
- AI 能力：独立 AI 洞察页、SSE 流式追问、AI 生成待确认操作草稿、问题详情归因/建议/重复判断；未配置模型时使用本地规则兜底。
- 登录与角色：内置内部账号登录，账号落库管理，密码 PBKDF2 哈希保存，支持禁用账号、字段配置、数据范围和 AI 草稿执行权限控制。
- 操作日志：问题新增、编辑、删除、状态变更、复发标记、处理记录和 AI 确认执行动作均写入审计，并可在问题详情页查看。
- 前端性能：核心业务页面按路由懒加载，降低首屏同步加载的业务代码体积。
- CI：GitHub Actions 自动执行文档契约、后端测试、前端构建和 Playwright E2E；当前 E2E 覆盖登录、新增问题、状态流转、数据模块筛选/钻取/CSV 导出、AI 草稿确认执行、账号禁用、角色新增和部门新增。
- 统一响应：`{ code, message, data }`；统一异常处理。

## 目录

```text
frontend/            React + TypeScript + Vite + Ant Design + Axios
backend/             Java 21 + Spring Boot + Spring Data JPA + Maven
mysql/init.sql       Docker 演示库基础脚本；完整结构以 Flyway 迁移为准
backend/src/main/resources/db/migration 后端 Flyway 迁移
docker-compose.yml   MySQL、后端、Nginx 前端编排
```

设计规范见 [frontend/DESIGN_SYSTEM.md](frontend/DESIGN_SYSTEM.md)。

## 本地启动

### 1. MySQL

创建 `issue_ops` 数据库。后端启动时会通过 Flyway 自动校验/迁移表结构；完整 schema 以 `backend/src/main/resources/db/migration` 为准。`mysql/init.sql` 是 Docker 演示库基础脚本，不作为完整迁移源。手动导入演示数据时，请先让后端完成 Flyway 迁移，再执行：

```bash
mysql -uroot -p < mysql/init.sql
```

默认连接参数：`root / root123456 / localhost:3306/issue_ops`。可通过环境变量覆盖：

```text
DB_URL
DB_USERNAME
DB_PASSWORD
```

### 2. 后端

```bash
cd backend
mvn spring-boot:run
```

后端地址：`http://localhost:8080`，API 前缀：`http://localhost:8080/api`。

### 3. 前端

```bash
cd frontend
npm install
npm run dev
```

开发地址：`http://localhost:18000`。本地浏览器访问 `localhost` 或 `127.0.0.1` 时，前端默认通过 Vite 代理访问 `http://127.0.0.1:8080/api`，可通过 `VITE_API_BASE_URL` 覆盖。若 Docker 前端容器也占用了 `18000`，请先停止容器或临时修改 Vite 端口。

### 默认登录账号

系统启动时会读取 `AUTH_USERS`，并只创建数据库中不存在的种子账号；对于已经存在的账号，页面维护的显示名、角色、部门、数据范围和启停状态不会被重启覆盖，仅在密码哈希缺失或历史明文密码时补齐/加密。之后可由管理员在左侧 `账号管理` 页面新增、编辑、停用账号、配置所属部门、配置数据范围或重置密码。默认账号仅用于本地开发和演示：

| 角色 | 账号 | 密码 | 权限 |
| --- | --- | --- | --- |
| 管理员 | `admin` | `admin123` | 全部权限，含字段配置和删除 |
| 产品 | `product` | `product123` | 新增/编辑问题、状态流转、AI 草稿执行 |
| 技术 | `tech` | `tech123` | 编辑问题、状态流转、AI 草稿执行 |
| 客服 | `cs` | `cs123` | 新增问题、追加处理记录 |
| 观察员 | `viewer` | `viewer123` | 只读 |

生产部署时请在 `.env` 中覆盖初始种子账号和认证密钥：

```text
AUTH_SECRET=change-this-to-a-long-random-secret
AUTH_TOKEN_TTL_SECONDS=28800
AUTH_USERS=admin|admin123|ADMIN|照远;product|product123|PRODUCT|产品负责人
```

`AUTH_USERS` 默认格式为 `账号|密码|角色|显示名`，也支持扩展为 `账号|密码|角色|显示名|部门|数据范围`。多个账号用英文分号分隔。角色编码来自后台“账号管理 → 角色配置”，系统默认种子包含 `ADMIN`、`PRODUCT`、`TECH`、`CS`、`VIEWER`，管理员可在页面新增、编辑自定义角色并配置权限点、默认部门和默认数据范围。这些账号只用于创建缺失账号和补齐异常密码哈希，不会在数据库中明文保存密码；已存在账号的角色、部门、数据范围请以账号管理页为准。

### 数据范围权限

系统已经区分“能不能操作”和“能看哪些数据”：

| 数据范围 | 含义 |
| --- | --- |
| `ALL` | 可查看全部问题数据 |
| `DEPARTMENT` | 可查看责任部门为本人部门、本人创建或指派给本人的问题 |
| `OWN` | 仅查看本人创建的问题 |
| `ASSIGNED` | 仅查看责任人为本人的问题 |

数据范围由后端统一过滤，覆盖问题列表、问题详情、首页统计、趋势图、报表、AI 洞察、复盘沉淀、复发分析和 AI 草稿执行。前端展示限制只作为体验补充，不能替代后端过滤。

### 企业 SSO 配置

第一版已提供企业 SSO 入口、受密钥保护的回调落库和自动创建账号能力。未配置时登录页会提示“企业 SSO 尚未启用”，仍使用账号密码登录。真实接入企业微信、OIDC 或 LDAP 时，可由网关或身份服务在认证成功后调用 `/api/auth/sso/callback`，系统会按 `ssoSubject` 绑定账号，必要时自动创建账号，并同步显示名和部门。

```text
AUTH_SSO_ENABLED=false
AUTH_SSO_PROVIDER_NAME=企业 SSO
AUTH_SSO_LOGIN_URL=
AUTH_SSO_CALLBACK_SECRET=
AUTH_SSO_AUTO_PROVISION=true
AUTH_SSO_DEFAULT_ROLE=VIEWER
AUTH_SSO_DEFAULT_DATA_SCOPE=DEPARTMENT

ORG_DEPARTMENTS=全部,产品部,技术部,客服部,管理部
```

`AUTH_SSO_CALLBACK_SECRET` 只放在后端环境变量里，调用回调接口时通过 `X-SSO-Token` 请求头传入；生产环境启用 SSO 时必须配置 32 位以上随机值。`ORG_DEPARTMENTS` 用于启动时同步部门基础数据，同时系统也会从已有账号和角色默认部门补齐部门表。

## Docker Compose 启动

```bash
docker compose up -d --build
```

- 系统入口：`http://localhost:18000`
- 后端：`http://localhost:8080`
- MySQL：`localhost:3306`

端口被占用时可临时覆盖映射，不改变容器内部端口：

```powershell
$env:MYSQL_PORT='13306'
$env:BACKEND_PORT='18081'
$env:FRONTEND_PORT='18000'
docker compose up -d --build
```

停止：

```bash
docker compose down
```

如需清空数据库卷并重新导入演示数据：

```bash
docker compose down -v
docker compose up -d --build
```

### DeepSeek 配置

AI 智能洞察由后端读取环境变量调用 DeepSeek，前端不会接触 API Key。未配置 Key 时，系统会自动使用本地规则生成兜底洞察。

```text
DEEPSEEK_API_KEY=
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_MODEL=deepseek-v4-pro
```

Docker Compose 会自动读取项目根目录的 `.env` 文件或当前 shell 环境变量。`.env` 已加入 `.gitignore`，不要把真实密钥写入源码、README 或前端配置。

如果后端容器内调用大模型时出现 Java DNS 解析失败，可在 `.env` 中保留以下配置；`docker-compose.yml` 会把它们应用到 backend 容器：

```text
DOCKER_DNS_PRIMARY=223.5.5.5
DOCKER_DNS_SECONDARY=114.114.114.114
```

## 生产部署前检查

HTTPS / 反向代理不在本项目内处理，生产环境至少需要完成以下应用侧检查：

```bash
bash scripts/generate-prod-env.sh .env.production.local
bash scripts/check-env.sh .env.production.local
docker compose --env-file .env.production.local up -d --build
bash scripts/docker-smoke.sh .env.production.local
```

`scripts/generate-prod-env.sh` 会生成数据库密码、`AUTH_SECRET`、SSO 回调密钥和初始管理员密码，并从现有 `.env` 继承 `AI_API_KEY` / `DEEPSEEK_API_KEY`。生成的 `.env.production.local` 已被 `.gitignore` 忽略，不要提交到 Git。
生成文件中包含 `COMPOSE_PROJECT_NAME=issueops`，用于固定 Docker Compose 项目名和数据卷名，避免换目录部署时创建另一套 MySQL 卷。

如果需要手工维护生产配置，可以从 `.env.production.example` 复制后替换所有 `CHANGE_ME` 值；自动生成脚本更适合首次部署。

如果你手动维护生产 `.env`，或者生产环境已经替换管理员账号，请在冒烟前设置登录账号：

```bash
SMOKE_USERNAME=admin SMOKE_PASSWORD=your-password bash scripts/docker-smoke.sh
```

如果是本地开发环境，需要允许示例账号和默认数据库密码：

```bash
ALLOW_DEV_DEFAULTS=true bash scripts/check-env.sh .env.example
```

### 部署脚本

| 脚本 | 用途 |
|---|---|
| `scripts/generate-prod-env.sh` | 生成本地忽略的生产环境变量文件，自动填充强随机密钥和初始管理员密码 |
| `scripts/check-env.sh` | 上线前校验 `.env`，拦截默认密码、占位密钥、弱 `AUTH_SECRET` 和端口配置错误 |
| `scripts/docker-smoke.sh` | Docker Compose 启动后执行真实冒烟测试，覆盖登录、健康检查、首页统计、趋势、数据分析和 AI 洞察接口 |
| `scripts/mysql-backup.sh` | 导出 MySQL 备份到 `backups/*.sql.gz` |
| `scripts/mysql-restore.sh` | 从 `.sql` 或 `.sql.gz` 恢复 MySQL，必须显式设置 `CONFIRM_RESTORE=YES` |

备份示例：

```bash
bash scripts/mysql-backup.sh
```

恢复示例：

```bash
CONFIRM_RESTORE=YES bash scripts/mysql-restore.sh backups/issue_ops-YYYYMMDD-HHMMSS.sql.gz
```

### 运维接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 服务存活检查，不需要登录 |
| GET | `/api/readiness` | 就绪检查，不需要登录；返回数据库、AI 配置和认证密钥状态，不暴露密钥内容 |

`/api/readiness` 中数据库不可用时返回 HTTP 503；AI Key 未配置或认证密钥仍是开发默认值时返回 `DEGRADED`，系统仍可通过本地规则兜底运行。

## GitHub Actions CI

仓库已包含 `.github/workflows/ci.yml`，在 push 或 PR 到 `main` 时自动执行：

- 文档契约：`bash scripts/verify-docs.sh`
- 后端：`mvn test`
- 前端：`npm ci` 与 `npm run build`
- 前端 E2E：`npm run test:e2e`，通过 Playwright 拦截 API，覆盖登录、新增问题、状态流转、数据分析筛选/钻取/CSV 导出、AI 草稿确认执行、账号禁用、角色新增和部门新增
- 提交内容检查：阻止 `.env`、`node_modules`、`dist`、`target`、`output`、日志和 tsbuildinfo 进入版本库

## AI 洞察说明

首页只保留轻量 AI 洞察入口，完整能力在左侧导航 `AI 洞察` 独立页面中。该页面按 Action Command Center 方案组织为三栏结构：

- 左侧：风险雷达，支持按 `超期问题`、`复发问题`、`P0/P1 问题` 筛选中间优先级列表。
- 中间：本次建议优先级，默认展示 AI/规则识别出的 Top 3 优先处理问题，可展开到最多 6 条，并支持点击进入问题详情。
- 右侧：AI 分析助手，支持思考中、SSE 流式回复、失败兜底、快捷追问和会话历史。
- 底部：继续追问输入框，提交后调用后端 AI 洞察接口。

### 通用大模型配置

后端优先读取通用 `AI_*` 环境变量，前端不会接触 API Key：

```text
AI_PROVIDER=deepseek
AI_API_KEY=
AI_BASE_URL=https://api.deepseek.com
AI_MODEL=deepseek-v4-pro
AI_TIMEOUT_MS=60000
AI_TEMPERATURE=0.2
AI_MAX_TOKENS=2000
```

旧的 `DEEPSEEK_*` 变量仍保留兼容，但建议后续统一使用 `AI_*`。

### 新增 AI 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ai-insights/overview` | 获取 AI 洞察总览 |
| POST | `/api/ai-insights/refresh` | 拉取最新问题数据并刷新洞察 |
| GET | `/api/ai-insights/ai-analysis` | 独立调用大模型生成 AI 解释；页面首屏不等待该接口 |
| POST | `/api/ai-insights/chat` | 基于当前问题上下文继续追问 |
| POST | `/api/ai-insights/sessions` | 创建 AI 洞察会话 |
| GET | `/api/ai-insights/sessions/{sessionId}/messages` | 获取服务端保存的会话历史 |
| POST | `/api/ai-insights/sessions/{sessionId}/chat/stream` | SSE 流式追问，返回 thinking/delta/answer/done 事件 |
| POST | `/api/ai-insights/actions/execute` | 确认执行 AI 生成的新增问题、状态变更或处理记录草稿 |
| GET | `/api/ai-insights/recurrence` | 获取复发问题分析总览 |
| GET | `/api/ai-insights/recurrence/{id}` | 获取单个问题的复发/同源分析 |

AI 接口会先在当前登录账号可见的数据范围内执行本地规则计算，再调用 OpenAI-compatible 大模型接口做解释和建议。返回数据会区分 `ruleAnalysis`、`aiAnalysis`、`finalView` 和 `fallback`，避免把确定性的规则指标与大模型解释混在一起。`overview/refresh` 会先返回 `aiStatus=pending` 的规则结果；`ai-analysis` 成功后变为 `applied`，失败时变为 `failed` 并返回 `aiFailure.code/message`，页面仍展示本地规则结果。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/issues` | 问题列表、筛选与分页 |
| GET | `/api/issues/{id}` | 问题详情 |
| GET | `/api/issues/{id}/audits` | 问题操作日志 |
| POST | `/api/issues` | 新增问题 |
| PUT | `/api/issues/{id}` | 编辑问题 |
| DELETE | `/api/issues/{id}` | 逻辑删除 |
| PATCH | `/api/issues/{id}/status` | 修改状态并写入日志 |
| PATCH | `/api/issues/{id}/reopened` | 标记/取消复发并写入日志 |
| POST | `/api/issues/{id}/logs` | 新增处理记录 |
| GET | `/api/dashboard/statistics` | 看板统计 |
| GET | `/api/dashboard/trend?range=8w` | 问题趋势 |
| GET | `/api/dashboard/ai-insight` | 兼容旧版首页 AI 洞察接口，新页面使用 `/api/ai-insights/*` |
| POST | `/api/dashboard/ai-insight/query` | 兼容旧版首页 AI 提问接口 |
| GET | `/api/reports/overview` | 数据报表 |
| GET | `/api/reports/analysis?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD&departments=技术部,产品部` | 数据分析主界面，日期和部门参数可选；默认上一个完整自然月，按当前用户可见数据和部门筛选重新聚合，返回治理指数、维度树、趋势、效率分布、明细和数据口径 |
| GET | `/api/retrospectives/overview` | 复盘沉淀总览 |
| GET | `/api/retrospectives/ai-suggestion` | 复盘沉淀 AI 建议 |
| POST | `/api/retrospectives/draft` | 基于问题生成复盘草稿 |
| POST | `/api/issues/{id}/ai/{type}` | 问题详情 AI 分析；`root-cause/suggestion/duplicate` |
| GET | `/api/dictionaries?type=ISSUE_SOURCE` | 获取指定类型字段选项 |
| GET | `/api/dictionaries/grouped` | 获取全部字段选项分组 |
| POST | `/api/dictionaries` | 新增字段选项 |
| PUT | `/api/dictionaries/{id}` | 编辑字段选项 |
| PATCH | `/api/dictionaries/{id}/enabled` | 启用/停用字段选项 |
| DELETE | `/api/dictionaries/{id}` | 删除未被引用的字段选项 |
| GET | `/api/dictionaries/{id}/usage` | 查询字段选项引用数量 |
| POST | `/api/auth/login` | 账号密码登录 |
| GET | `/api/auth/me` | 获取当前登录用户 |
| GET | `/api/accounts` | 账号列表，管理员 |
| POST | `/api/accounts` | 新增账号，管理员；支持部门和数据范围 |
| PUT | `/api/accounts/{id}` | 编辑账号/重置密码/部门/数据范围，管理员 |
| PATCH | `/api/accounts/{id}/enabled` | 启用/停用账号，管理员 |
| GET | `/api/roles` | 角色列表，管理员；支持 `enabledOnly=true` |
| GET | `/api/roles/permissions` | 可配置权限点及中文标签，管理员 |
| POST | `/api/roles` | 新增后台角色，管理员 |
| PUT | `/api/roles/{id}` | 编辑角色名称、权限、默认部门和默认数据范围，管理员 |
| PATCH | `/api/roles/{id}/enabled` | 启用/停用自定义角色，管理员 |
| DELETE | `/api/roles/{id}` | 删除未被账号使用的自定义角色，管理员 |
| GET | `/api/departments?enabledOnly=true` | 部门列表，登录用户可读；账号和角色默认部门下拉使用 |
| POST | `/api/departments` | 新增后台部门，管理员 |
| PUT | `/api/departments/{id}` | 编辑部门名称、上级部门、排序和启用状态，管理员 |
| PATCH | `/api/departments/{id}/enabled` | 启用/停用未被引用的部门，管理员 |
| DELETE | `/api/departments/{id}` | 删除未被账号或角色引用的部门，管理员 |
| POST | `/api/departments/sync` | 同步部门列表，管理员；用于对接企业通讯录或 HR 系统 |
| GET | `/api/auth/sso/config` | 获取企业 SSO 启用状态 |
| POST | `/api/auth/sso/login` | 获取企业 SSO 登录跳转地址 |
| POST | `/api/auth/sso/callback` | 企业 SSO 回调落库，需 `X-SSO-Token` 共享密钥 |

字段配置支持的 `type`：

```text
ISSUE_SOURCE    问题来源
BUSINESS_SCENE  业务场景
ISSUE_TYPE      问题类型
IMPACT_SCOPE    影响范围
```

字段配置的删除规则：如果选项已经被历史问题引用，系统会拒绝删除，只允许停用。停用后不会出现在新增/编辑和筛选下拉中，但历史问题仍保留原值。

## 后续建议

1. 接入真实企业身份源：企业微信、OIDC、LDAP 或 AD，并把 SSO callback 的共享密钥模式替换为标准授权码/OIDC token 校验。
2. 增强部门治理：增加企业通讯录同步记录、部门批量迁移账号、部门层级树形展示。
3. 补字段级可见/编辑权限，解决不同角色在详情页能看什么、能改什么的问题。
4. 数据分析继续补保存视图、分享链接标题和导出任务记录，提升运营复盘可追溯性。
5. 将 CI 的文档契约扩展到 OpenAPI 或 Spring REST Docs，减少接口文档人工维护成本。
