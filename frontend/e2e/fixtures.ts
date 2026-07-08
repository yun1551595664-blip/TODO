import type { Page, Route } from "@playwright/test";

const apiBase = "http://127.0.0.1:8080/api";

export const adminUser = {
  username: "admin",
  displayName: "照远",
  role: "ADMIN",
  department: "管理部",
  dataScope: "ALL",
  permissions: [
    "issue:create",
    "issue:edit",
    "issue:delete",
    "issue:status",
    "issue:log",
    "field:manage",
    "account:manage",
    "ai:execute",
  ],
};

export const authSession = {
  token: "e2e-token",
  user: adminUser,
  expiresAt: Math.floor(Date.now() / 1000) + 3600,
};

const baseIssues = [
  {
    id: 1,
    issueNo: "PBI-20260603-0001",
    title: "支付成功后订单状态延迟更新",
    description: "支付成功后订单状态未及时同步。",
    source: "客服反馈",
    businessScene: "订单支付",
    issueType: "系统缺陷",
    impactScope: "部分用户",
    customerImpact: "影响订单查询并引发重复支付咨询",
    reproduceSteps: "完成支付后刷新订单详情。",
    priority: "P0",
    status: "处理中",
    responsibleDepartment: "技术部",
    responsiblePerson: "张臻",
    tapdUrl: "https://tapd.example.com/1",
    rootCause: "支付回调依赖服务响应不稳定。",
    fixSolution: "增加回调重试与状态补偿。",
    verifyResult: "",
    reopened: false,
    expectedFinishTime: "2026-06-10T18:00:00",
    actualFinishTime: undefined,
    createdBy: "客服同学",
    createdAt: "2026-06-03T09:00:00",
    updatedAt: "2026-06-08T10:00:00",
    logs: [
      {
        id: 1,
        actionType: "创建问题",
        content: "创建问题并进入待处理",
        operator: "客服同学",
        createdAt: "2026-06-03T09:00:00",
      },
    ],
  },
  {
    id: 2,
    issueNo: "PBI-20260604-0002",
    title: "报表字段缺失",
    description: "导出报表缺少业务字段。",
    source: "业务巡检",
    businessScene: "数据报表",
    issueType: "报表问题",
    impactScope: "内部用户",
    customerImpact: "影响业务复盘效率",
    reproduceSteps: "导出日报并检查字段。",
    priority: "P2",
    status: "已完成",
    responsibleDepartment: "产品部",
    responsiblePerson: "李娜",
    rootCause: "字段映射未同步。",
    fixSolution: "补齐导出字段。",
    verifyResult: "已验证",
    reopened: false,
    expectedFinishTime: "2026-06-07T18:00:00",
    actualFinishTime: "2026-06-06T18:00:00",
    createdBy: "产品负责人",
    createdAt: "2026-06-04T09:00:00",
    updatedAt: "2026-06-06T18:00:00",
    logs: [],
  },
];

const dictionaryGroups = {
  ISSUE_SOURCE: [
    dictionaryItem(1, "ISSUE_SOURCE", "客服反馈"),
    dictionaryItem(2, "ISSUE_SOURCE", "业务巡检"),
  ],
  BUSINESS_SCENE: [
    dictionaryItem(3, "BUSINESS_SCENE", "订单支付"),
    dictionaryItem(4, "BUSINESS_SCENE", "数据报表"),
  ],
  ISSUE_TYPE: [
    dictionaryItem(5, "ISSUE_TYPE", "系统缺陷"),
    dictionaryItem(6, "ISSUE_TYPE", "报表问题"),
  ],
  IMPACT_SCOPE: [
    dictionaryItem(7, "IMPACT_SCOPE", "部分用户"),
    dictionaryItem(8, "IMPACT_SCOPE", "内部用户"),
  ],
};

const initialAccounts = [
  {
    id: 1,
    username: "admin",
    displayName: "照远",
    role: "ADMIN",
    department: "管理部",
    dataScope: "ALL",
    enabled: true,
    ssoSubject: "",
    lastLoginAt: "2026-06-30T09:30:00",
    createdAt: "2026-06-01T09:00:00",
    updatedAt: "2026-06-30T09:30:00",
  },
  {
    id: 2,
    username: "viewer",
    displayName: "观察员",
    role: "VIEWER",
    department: "产品部",
    dataScope: "DEPARTMENT",
    enabled: true,
    ssoSubject: "",
    lastLoginAt: undefined,
    createdAt: "2026-06-01T09:00:00",
    updatedAt: "2026-06-01T09:00:00",
  },
];

const initialRoles = [
  roleItem(
    1,
    "ADMIN",
    "管理员",
    [
      "issue:create",
      "issue:edit",
      "issue:delete",
      "issue:status",
      "issue:log",
      "field:manage",
      "account:manage",
      "ai:execute",
    ],
    "ALL",
    "管理部",
    true,
    10,
    1,
  ),
  roleItem(2, "VIEWER", "观察员", [], "DEPARTMENT", "产品部", true, 50, 1),
];

const departmentConfigs = [
  {
    id: 1,
    code: "MANAGEMENT",
    name: "管理部",
    parentCode: undefined,
    enabled: true,
    sortOrder: 10,
    source: "SYSTEM",
    accountCount: 1,
    roleCount: 1,
  },
  {
    id: 2,
    code: "PRODUCT",
    name: "产品部",
    parentCode: undefined,
    enabled: true,
    sortOrder: 20,
    source: "SYSTEM",
    accountCount: 1,
    roleCount: 1,
  },
  {
    id: 3,
    code: "TECH",
    name: "技术部",
    parentCode: undefined,
    enabled: true,
    sortOrder: 30,
    source: "SYSTEM",
    accountCount: 0,
    roleCount: 0,
  },
];

const permissionLabels = {
  "issue:create": "新增问题",
  "issue:edit": "编辑问题",
  "issue:delete": "删除问题",
  "issue:status": "状态流转/复发标记",
  "issue:log": "新增处理记录",
  "field:manage": "字段配置",
  "account:manage": "账号与角色管理",
  "ai:execute": "AI 草稿确认执行",
};

function ok(data: unknown) {
  return {
    code: 0,
    message: "ok",
    data,
  };
}

async function fulfill(route: Route, data: unknown, status = 200) {
  await route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(status >= 400 ? data : ok(data)),
  });
}

function dictionaryItem(id: number, dictType: string, name: string) {
  return {
    id,
    dictType,
    name,
    description: `${name}测试选项`,
    sortOrder: id * 10,
    enabled: true,
    builtin: false,
    usageCount: 0,
  };
}

function roleItem(
  id: number,
  code: string,
  name: string,
  permissions: string[],
  defaultDataScope: string,
  defaultDepartment: string,
  systemBuiltin: boolean,
  sortOrder: number,
  accountCount: number,
) {
  return {
    id,
    code,
    name,
    description: `${name}测试角色`,
    permissions,
    defaultDataScope,
    defaultDepartment,
    enabled: true,
    systemBuiltin,
    sortOrder,
    accountCount,
    createdAt: "2026-06-01T09:00:00",
    updatedAt: "2026-06-30T09:30:00",
  };
}

function pageData(content = baseIssues) {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    number: 0,
    size: 10,
  };
}

function trend() {
  return [
    { date: "2026-06-01", 新增: 1, 完成: 0, 待处理: 2 },
    { date: "2026-06-08", 新增: 2, 完成: 1, 待处理: 3 },
    { date: "2026-06-15", 新增: 1, 完成: 1, 待处理: 2 },
    { date: "2026-06-22", 新增: 1, 完成: 0, 待处理: 2 },
  ];
}

function reportOverview() {
  return {
    topIssues: [
      { name: "系统缺陷", value: 1 },
      { name: "报表问题", value: 1 },
    ],
    duplicateCount: 0,
    typeDistribution: [
      { name: "系统缺陷", value: 1 },
      { name: "报表问题", value: 1 },
    ],
    departmentDistribution: [
      { name: "技术部", value: 1 },
      { name: "产品部", value: 1 },
    ],
    averageHandleHours: 57,
    overdueIssues: [baseIssues[0]],
    suggestions: [],
  };
}

function dimensionItem(name: string, value: number, riskLevel = "低") {
  return {
    key: name,
    name,
    value,
    share: value === 2 ? 100 : 50,
    overdueCount: riskLevel === "高" ? 1 : 0,
    overdueRate: riskLevel === "高" ? 100 : 0,
    reopenedCount: 0,
    reopenedRate: 0,
    averageHandleHours: 57,
    riskLevel,
  };
}

function analysisData(department?: string | null) {
  const issues = department
    ? baseIssues.filter((issue) => issue.responsibleDepartment === department)
    : baseIssues;
  const total = issues.length;
  const technicalOnly = department === "技术部";

  return {
    period: {
      startDate: "2026-06-01",
      endDate: "2026-06-30",
      previousStartDate: "2026-05-02",
      previousEndDate: "2026-05-31",
      label: "2026-06-01 至 2026-06-30",
      previousLabel: "2026-05-02 至 2026-05-31",
    },
    appliedFilters: {
      departments: department ? [department] : [],
      startDate: "2026-06-01",
      endDate: "2026-06-30",
    },
    availableDepartments: ["产品部", "技术部"],
    summary: {
      total,
      current: total,
      newIssues: total,
      pending: technicalOnly ? 1 : 1,
      overdue: technicalOnly ? 1 : 1,
      reopened: 0,
      highPriority: technicalOnly ? 1 : 1,
      completed: technicalOnly ? 0 : 1,
      slaRate: technicalOnly ? 0 : 50,
      overdueRate: technicalOnly ? 100 : 50,
      reopenedRate: 0,
      averageHandleHours: technicalOnly ? 0 : 57,
      governanceScore: technicalOnly ? 42 : 76,
      governanceDelta: technicalOnly ? -8 : 5,
      subScores: [
        { label: "闭环效率", value: technicalOnly ? 30 : 78, delta: "+6", deltaValue: 6 },
        { label: "超期控制", value: technicalOnly ? 20 : 62, delta: "-8", deltaValue: -8 },
        { label: "复发控制", value: 100, delta: "+7", deltaValue: 7 },
        { label: "高优先级响应", value: technicalOnly ? 50 : 83, delta: "+4", deltaValue: 4 },
      ],
    },
    periodSummary: [
      { label: "新增问题", value: total, previousValue: 0, delta: `+${total}`, deltaValue: total, deltaRate: 100, tone: "up" },
      { label: "完成问题", value: technicalOnly ? 0 : 1, previousValue: 0, delta: technicalOnly ? "0" : "+1", deltaValue: technicalOnly ? 0 : 1, deltaRate: 100, tone: technicalOnly ? "flat" : "up" },
      { label: "待处理问题", value: technicalOnly ? 1 : 1, previousValue: 0, delta: "+1", deltaValue: 1, deltaRate: 100, tone: "danger" },
      { label: "超期问题", value: technicalOnly ? 1 : 1, previousValue: 0, delta: "+1", deltaValue: 1, deltaRate: 100, tone: "danger" },
    ],
    dimensions: [
      {
        key: "businessScene",
        label: "业务场景",
        items: technicalOnly
          ? [dimensionItem("订单支付", 1, "高")]
          : [dimensionItem("订单支付", 1, "高"), dimensionItem("数据报表", 1)],
      },
      {
        key: "issueType",
        label: "问题类型",
        items: technicalOnly
          ? [dimensionItem("系统缺陷", 1, "高")]
          : [dimensionItem("系统缺陷", 1, "高"), dimensionItem("报表问题", 1)],
      },
      {
        key: "responsibleDepartment",
        label: "责任部门",
        items: technicalOnly
          ? [dimensionItem("技术部", 1, "高")]
          : [dimensionItem("技术部", 1, "高"), dimensionItem("产品部", 1)],
      },
      {
        key: "source",
        label: "问题来源",
        items: [dimensionItem("客服反馈", technicalOnly ? 1 : 1, technicalOnly ? "高" : "高")],
      },
      {
        key: "impactScope",
        label: "影响范围",
        items: [dimensionItem("部分用户", technicalOnly ? 1 : 1, technicalOnly ? "高" : "高")],
      },
    ],
    trend: [
      { date: "2026-06-01", newIssues: 0, completed: 0, pending: 0, overdue: 0 },
      { date: "2026-06-08", newIssues: technicalOnly ? 1 : 2, completed: technicalOnly ? 0 : 1, pending: technicalOnly ? 1 : 1, overdue: technicalOnly ? 1 : 1 },
      { date: "2026-06-15", newIssues: 0, completed: 0, pending: technicalOnly ? 1 : 1, overdue: technicalOnly ? 1 : 1 },
      { date: "2026-06-30", newIssues: 0, completed: 0, pending: technicalOnly ? 1 : 1, overdue: technicalOnly ? 1 : 1 },
    ],
    efficiencyBuckets: [
      { label: "0-1天", total: 0, highPriority: 0, normal: 0 },
      { label: "1-3天", total: technicalOnly ? 0 : 1, highPriority: 0, normal: technicalOnly ? 0 : 1 },
      { label: "3-7天", total: 0, highPriority: 0, normal: 0 },
      { label: "7天以上", total: 0, highPriority: 0, normal: 0 },
    ],
    keyChanges: [
      {
        title: "超期问题上升",
        description: "支付类问题超过预计完成时间",
        detail: "超期 1 条",
        value: "+1",
        delta: 1,
        direction: "up",
        evidence: 1,
        tone: "warning",
      },
    ],
    structureMatrix: [
      { name: "系统缺陷", source: 50, impact: 50, reopened: 0, overdue: 100, value: 1 },
      ...(technicalOnly ? [] : [{ name: "报表问题", source: 50, impact: 50, reopened: 0, overdue: 0, value: 1 }]),
    ],
    priorityEfficiency: [
      { label: "P0（最高）", values: [0, 0, 0, 100], average: technicalOnly ? 0 : 0, total: technicalOnly ? 1 : 1 },
      { label: "P2（中）", values: [0, 100, 0, 0], average: 2.4, total: technicalOnly ? 0 : 1 },
    ],
    datasets: [
      { key: "issueDetail", title: "问题明细", desc: "按问题维度的完整明细数据", count: total, unit: "条", tone: "purple" },
      { key: "departmentRanking", title: "部门排行", desc: "部门多维度排行与对比", count: technicalOnly ? 1 : 2, unit: "个", tone: "green" },
      { key: "typeDetail", title: "类型明细", desc: "问题类型多维度分析", count: technicalOnly ? 1 : 2, unit: "类", tone: "purple" },
    ],
    events: [{ date: "2026-06-08", label: "支付接口调整" }],
    issues,
    metricDefinitions: [
      "超期率 = 已超过预计完成时间且未实际完成的问题 / 当前可见问题数",
      "复发率 = 标记为复发的问题 / 当前可见问题数",
      "SLA 达成率 = 1 - 超期问题数 / 当前可见问题数",
    ],
    updatedAt: "2026-06-30T09:30:00+08:00",
  };
}

function priorityIssue() {
  return {
    id: 1,
    issueId: "PBI-20260603-0001",
    issueNo: "PBI-20260603-0001",
    rank: 1,
    title: "支付成功后订单状态延迟更新",
    priority: "P0",
    status: "处理中",
    department: "技术部",
    owner: "张臻",
    overdueDays: 6,
    repeatCount: 0,
    impact: "影响订单查询并引发重复支付咨询",
    expectedImpact: "降低支付咨询量并恢复订单履约信任",
    reason: "P0 且已超期，需要当天确认修复进展",
    evidenceTags: ["P0", "处理中", "超期 6 天", "支付链路"],
    evidence: ["P0 高优先级", "超期 6 天"],
    filters: ["overdue", "highPriority"],
    score: 96,
  };
}

function aiOverview() {
  const issue = priorityIssue();
  return {
    insightId: "insight-e2e",
    period: "近 30 天",
    totalIssues: 2,
    updatedAt: "2026-06-30T09:30:00+08:00",
    riskLevel: "高风险",
    riskRadar: [
      {
        key: "overdue",
        label: "超期问题",
        value: 1,
        description: "需要升级跟进",
        tone: "warning",
        icon: "clock",
      },
      {
        key: "reopened",
        label: "复发问题",
        value: 0,
        description: "暂无复发",
        tone: "positive",
        icon: "repeat",
      },
      {
        key: "highPriority",
        label: "P0/P1 问题",
        value: 1,
        description: "优先推进",
        tone: "danger",
        icon: "zap",
      },
    ],
    priorityIssues: [issue],
    aiReply: {
      question: "帮我判断哪些问题需要今天跟进",
      judgmentBasis: ["支付成功后订单状态延迟更新为 P0 且已超期"],
      recommendedPriority: "优先处理支付链路问题",
      impactScope: "部分用户订单查询",
      processingOrder: ["先确认技术部修复进度", "再补充验证结论"],
    },
    suggestedActions: ["当天确认修复进展", "补充处理记录"],
    issueContext: [issue],
    summary: "当前高风险集中在支付链路超期问题。",
    generatedBy: "deepseek",
    aiAvailable: true,
    aiStatus: "applied",
    modelInfo: {
      provider: "deepseek",
      model: "deepseek-v4-pro",
    },
    finalView: undefined,
    fallback: {
      used: false,
    },
  };
}

function pendingAction() {
  return {
    actionId: "action-e2e-1",
    actionType: "UPDATE_STATUS",
    title: "将支付问题更新为待验证",
    summary: "基于对话生成状态更新草稿，确认后写入系统。",
    payload: {
      issueId: 1,
      issueNo: "PBI-20260603-0001",
      title: "支付成功后订单状态延迟更新",
      status: "待验证",
      content: "修复完成，进入验证。",
      operator: "照远",
    },
    warnings: ["确认修复证据后再写入"],
    requiresConfirmation: true,
    expiresAt: "2026-06-30T10:00:00+08:00",
  };
}

function aiAnswer(question: string) {
  return {
    insightId: "insight-e2e",
    sessionId: "session-e2e",
    question,
    answer: "已生成待确认操作，尚未写入系统。请核对下方内容后确认执行。",
    evidence: ["支付成功后订单状态延迟更新为 P0", "当前状态为处理中"],
    suggestedActions: ["确认验证结果", "更新状态为待验证"],
    relatedIssues: [priorityIssue()],
    generatedAt: "2026-06-30T09:40:00+08:00",
    generatedBy: "deepseek",
    model: "deepseek-v4-pro",
    pendingAction: pendingAction(),
  };
}

export type MockCalls = {
  loginBodies: unknown[];
  analysisSearches: string[];
  statusBodies: unknown[];
  createIssueBodies: unknown[];
  accountEnabledBodies: unknown[];
  roleCreateBodies: unknown[];
  roleUpdateBodies: unknown[];
  roleEnabledBodies: unknown[];
  departmentCreateBodies: unknown[];
  departmentUpdateBodies: unknown[];
  departmentEnabledBodies: unknown[];
  actionExecuteBodies: unknown[];
};

export async function seedAuth(page: Page) {
  await page.addInitScript((session) => {
    window.localStorage.setItem("issueOpsAuth", JSON.stringify(session));
  }, authSession);
}

export async function mockIssueOpsApi(page: Page): Promise<MockCalls> {
  const calls: MockCalls = {
    loginBodies: [],
    analysisSearches: [],
    statusBodies: [],
    createIssueBodies: [],
    accountEnabledBodies: [],
    roleCreateBodies: [],
    roleUpdateBodies: [],
    roleEnabledBodies: [],
    departmentCreateBodies: [],
    departmentUpdateBodies: [],
    departmentEnabledBodies: [],
    actionExecuteBodies: [],
  };
  let currentIssue = { ...baseIssues[0] };
  let issues = [...baseIssues];
  let accounts = [...initialAccounts];
  let roles = [...initialRoles];
  let departments = [...departmentConfigs];

  await page.route(`${apiBase}/**`, async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace("/api", "");

    if (path === "/auth/sso/config" && request.method() === "GET") {
      await fulfill(route, { enabled: false, providerName: "企业 SSO" });
      return;
    }
    if (path === "/auth/login" && request.method() === "POST") {
      calls.loginBodies.push(request.postDataJSON());
      await fulfill(route, authSession);
      return;
    }
    if (path === "/auth/me" && request.method() === "GET") {
      await fulfill(route, adminUser);
      return;
    }
    if (path === "/dictionaries/grouped" && request.method() === "GET") {
      await fulfill(route, dictionaryGroups);
      return;
    }
    if (path === "/accounts" && request.method() === "GET") {
      await fulfill(route, accounts);
      return;
    }
    if (path === "/roles" && request.method() === "GET") {
      const enabledOnly = url.searchParams.get("enabledOnly") === "true";
      await fulfill(
        route,
        enabledOnly ? roles.filter((role) => role.enabled) : roles,
      );
      return;
    }
    if (path === "/roles/permissions" && request.method() === "GET") {
      await fulfill(route, permissionLabels);
      return;
    }
    if (path === "/departments" && request.method() === "GET") {
      const enabledOnly = url.searchParams.get("enabledOnly") === "true";
      await fulfill(
        route,
        enabledOnly
          ? departments.filter((department) => department.enabled)
          : departments,
      );
      return;
    }
    if (path === "/departments" && request.method() === "POST") {
      const body = request.postDataJSON();
      calls.departmentCreateBodies.push(body);
      const department = {
        id: departments.length + 1,
        code: String(body.code || "").toUpperCase(),
        name: body.name,
        parentCode: body.parentCode,
        enabled: body.enabled ?? true,
        sortOrder: body.sortOrder || 0,
        source: "MANUAL",
        accountCount: 0,
        roleCount: 0,
        createdAt: "2026-06-30T09:30:00",
        updatedAt: "2026-06-30T09:30:00",
      };
      departments = [...departments, department];
      await fulfill(route, department);
      return;
    }
    if (path.match(/^\/departments\/\d+$/) && request.method() === "PUT") {
      const id = Number(path.split("/").at(-1));
      const body = request.postDataJSON();
      calls.departmentUpdateBodies.push(body);
      departments = departments.map((department) =>
        department.id === id
          ? {
              ...department,
              ...body,
              code: department.code,
            }
          : department,
      );
      await fulfill(route, departments.find((department) => department.id === id));
      return;
    }
    if (path.match(/^\/departments\/\d+\/enabled$/) && request.method() === "PATCH") {
      const id = Number(path.split("/").at(-2));
      const body = request.postDataJSON();
      calls.departmentEnabledBodies.push(body);
      departments = departments.map((department) =>
        department.id === id ? { ...department, enabled: Boolean(body.enabled) } : department,
      );
      await fulfill(route, departments.find((department) => department.id === id));
      return;
    }
    if (path.match(/^\/departments\/\d+$/) && request.method() === "DELETE") {
      const id = Number(path.split("/").at(-1));
      departments = departments.filter((department) => department.id !== id);
      await fulfill(route, undefined);
      return;
    }
    if (path === "/roles" && request.method() === "POST") {
      const body = request.postDataJSON();
      calls.roleCreateBodies.push(body);
      const role = {
        ...roleItem(
          roles.length + 1,
          String(body.code || "").toUpperCase(),
          body.name,
          body.permissions || [],
          body.defaultDataScope || "DEPARTMENT",
          body.defaultDepartment || "",
          false,
          body.sortOrder || 0,
          0,
        ),
        description: body.description || "",
        enabled: body.enabled ?? true,
      };
      roles = [...roles, role];
      await fulfill(route, role);
      return;
    }
    if (path.match(/^\/roles\/\d+$/) && request.method() === "PUT") {
      const id = Number(path.split("/").at(-1));
      const body = request.postDataJSON();
      calls.roleUpdateBodies.push(body);
      roles = roles.map((role) =>
        role.id === id
          ? {
              ...role,
              ...body,
              code: role.code,
              permissions: body.permissions || [],
              enabled: role.systemBuiltin ? true : body.enabled ?? role.enabled,
            }
          : role,
      );
      await fulfill(route, roles.find((role) => role.id === id));
      return;
    }
    if (path.match(/^\/roles\/\d+\/enabled$/) && request.method() === "PATCH") {
      const id = Number(path.split("/").at(-2));
      const body = request.postDataJSON();
      calls.roleEnabledBodies.push(body);
      roles = roles.map((role) =>
        role.id === id ? { ...role, enabled: Boolean(body.enabled) } : role,
      );
      await fulfill(route, roles.find((role) => role.id === id));
      return;
    }
    if (path.match(/^\/roles\/\d+$/) && request.method() === "DELETE") {
      const id = Number(path.split("/").at(-1));
      roles = roles.filter((role) => role.id !== id);
      await fulfill(route, undefined);
      return;
    }
    if (path === "/accounts/2/enabled" && request.method() === "PATCH") {
      const body = request.postDataJSON();
      calls.accountEnabledBodies.push(body);
      accounts = accounts.map((account) =>
        account.id === 2 ? { ...account, enabled: Boolean(body.enabled) } : account,
      );
      await fulfill(route, accounts.find((account) => account.id === 2));
      return;
    }
    if (path === "/dashboard/statistics" && request.method() === "GET") {
      await fulfill(route, {
        total: 2,
        pending: 0,
        processing: 1,
        verifying: 0,
        completed: 1,
        reopened: 0,
        overdue: 1,
        monthlyNew: 2,
        monthlyCompleted: 1,
        updatedAt: "2026-06-30T09:30:00+08:00",
        dataUpdatedAt: "2026-06-30T09:30:00+08:00",
      });
      return;
    }
    if (path === "/dashboard/trend" && request.method() === "GET") {
      await fulfill(route, trend());
      return;
    }
    if (path === "/issues" && request.method() === "GET") {
      await fulfill(route, pageData(issues));
      return;
    }
    if (path === "/issues" && request.method() === "POST") {
      const body = request.postDataJSON();
      calls.createIssueBodies.push(body);
      const newIssue = {
        ...baseIssues[0],
        ...body,
        id: 3,
        issueNo: "PBI-20260630-0003",
        status: body.status || "待处理",
        priority: body.priority || "P2",
        reopened: false,
        createdAt: "2026-06-30T11:00:00",
        updatedAt: "2026-06-30T11:00:00",
        logs: [],
      };
      issues = [newIssue, ...issues];
      await fulfill(route, newIssue);
      return;
    }
    if (path === "/issues/1" && request.method() === "GET") {
      await fulfill(route, currentIssue);
      return;
    }
    if (path === "/issues/3" && request.method() === "GET") {
      await fulfill(route, issues.find((issue) => issue.id === 3));
      return;
    }
    if (path === "/issues/1/audits" && request.method() === "GET") {
      await fulfill(route, [
        {
          id: 1,
          operatorName: "照远",
          operatorRole: "ADMIN",
          actionType: "CREATE_ISSUE",
          targetType: "ISSUE",
          targetId: "1",
          targetNo: currentIssue.issueNo,
          source: "MANUAL",
          createdAt: "2026-06-03T09:00:00",
        },
      ]);
      return;
    }
    if (path === "/issues/3/audits" && request.method() === "GET") {
      await fulfill(route, []);
      return;
    }
    if (path === "/issues/1/status" && request.method() === "PATCH") {
      const body = request.postDataJSON();
      calls.statusBodies.push(body);
      currentIssue = {
        ...currentIssue,
        status: body.status,
        logs: [
          ...currentIssue.logs,
          {
            id: 2,
            actionType: "状态变更",
            content: body.content || `处理中 → ${body.status}`,
            operator: body.operator || "照远",
            createdAt: "2026-06-30T10:00:00",
          },
        ],
      };
      await fulfill(route, currentIssue);
      return;
    }
    if (path === "/reports/overview" && request.method() === "GET") {
      await fulfill(route, reportOverview());
      return;
    }
    if (path === "/reports/analysis" && request.method() === "GET") {
      calls.analysisSearches.push(url.search);
      await fulfill(route, analysisData(url.searchParams.get("departments")));
      return;
    }
    if (path === "/ai-insights/overview" && request.method() === "GET") {
      await fulfill(route, aiOverview());
      return;
    }
    if (path === "/ai-insights/ai-analysis" && request.method() === "GET") {
      await fulfill(route, aiOverview());
      return;
    }
    if (path === "/ai-insights/refresh" && request.method() === "POST") {
      await fulfill(route, aiOverview());
      return;
    }
    if (path === "/ai-insights/sessions" && request.method() === "POST") {
      await fulfill(route, {
        sessionId: "session-e2e",
        insightId: "insight-e2e",
        title: "AI 智能洞察对话",
        createdAt: "2026-06-30T09:35:00+08:00",
        updatedAt: "2026-06-30T09:35:00+08:00",
      });
      return;
    }
    if (path === "/ai-insights/sessions/session-e2e/messages" && request.method() === "GET") {
      await fulfill(route, []);
      return;
    }
    if (path === "/ai-insights/sessions/session-e2e/chat/stream" && request.method() === "POST") {
      const question = request.postDataJSON().question || "生成状态更新草稿";
      const answer = aiAnswer(question);
      const chunks = [
        ["session", { sessionId: "session-e2e", insightId: "insight-e2e", title: "AI 智能洞察对话", createdAt: "2026-06-30T09:35:00+08:00", updatedAt: "2026-06-30T09:40:00+08:00" }],
        ["thinking", { step: "正在读取问题数据" }],
        ["delta", { text: "已根据当前问题数据生成待确认操作。" }],
        ["action", pendingAction()],
        ["answer", answer],
        ["done", { sessionId: "session-e2e", generatedBy: "deepseek", model: "deepseek-v4-pro" }],
      ]
        .map(([event, data]) => `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
        .join("");
      await route.fulfill({
        status: 200,
        contentType: "text/event-stream; charset=utf-8",
        body: chunks,
      });
      return;
    }
    if (path === "/ai-insights/actions/execute" && request.method() === "POST") {
      const body = request.postDataJSON();
      calls.actionExecuteBodies.push(body);
      await fulfill(route, {
        executed: true,
        actionType: "UPDATE_STATUS",
        message: "已更新状态：待验证",
        issue: { ...currentIssue, status: "待验证" },
        executedAt: "2026-06-30T09:42:00+08:00",
      });
      return;
    }

    await fulfill(
      route,
      { code: 404, message: `No E2E mock for ${request.method()} ${path}` },
      404,
    );
  });

  return calls;
}
