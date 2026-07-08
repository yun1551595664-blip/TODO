export type Issue = {
  id: number;
  issueNo: string;
  title: string;
  description?: string;
  source?: string;
  businessScene?: string;
  issueType?: string;
  impactScope?: string;
  customerImpact?: string;
  reproduceSteps?: string;
  priority: string;
  status: string;
  responsibleDepartment?: string;
  responsiblePerson?: string;
  tapdUrl?: string;
  attachmentUrl?: string;
  rootCause?: string;
  fixSolution?: string;
  verifyResult?: string;
  reopened: boolean;
  reopenedReason?: string;
  expectedFinishTime?: string;
  actualFinishTime?: string;
  createdBy?: string;
  createdAt: string;
  updatedAt: string;
  logs: IssueLog[];
};

export type IssueLog = {
  id: number;
  actionType: string;
  content: string;
  operator: string;
  createdAt: string;
};

export type AuditLog = {
  id: number;
  operatorName?: string;
  operatorRole?: string;
  actionType: string;
  targetType: string;
  targetId?: string;
  targetNo?: string;
  source: "MANUAL" | "AI" | string;
  aiActionId?: string;
  beforeData?: string;
  afterData?: string;
  ip?: string;
  userAgent?: string;
  createdAt: string;
};

export type PageData<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

export type TrendPoint = {
  date: string;
  新增: number;
  完成: number;
  待处理: number;
};

export type DashboardData = {
  total: number;
  pending: number;
  processing: number;
  verifying: number;
  completed: number;
  reopened: number;
  overdue: number;
  monthlyNew: number;
  monthlyCompleted: number;
  updatedAt?: string;
  dataUpdatedAt?: string;
};

export type AuthUser = {
  username: string;
  displayName: string;
  role: "ADMIN" | "PRODUCT" | "TECH" | "CS" | "VIEWER" | string;
  department?: string;
  dataScope?: AccountDataScope | string;
  permissions: string[];
};

export type AuthSession = {
  token: string;
  user: AuthUser;
  expiresAt: number;
};

export type AccountRole = "ADMIN" | "PRODUCT" | "TECH" | "CS" | "VIEWER" | string;
export type AccountDataScope = "ALL" | "DEPARTMENT" | "OWN" | "ASSIGNED";

export type RoleConfig = {
  id: number;
  code: string;
  name: string;
  description?: string;
  permissions: string[];
  defaultDataScope: AccountDataScope | string;
  defaultDepartment?: string;
  enabled: boolean;
  systemBuiltin: boolean;
  sortOrder: number;
  accountCount: number;
  createdAt?: string;
  updatedAt?: string;
};

export type RolePayload = {
  code?: string;
  name: string;
  description?: string;
  permissions: string[];
  defaultDataScope: AccountDataScope | string;
  defaultDepartment?: string;
  enabled?: boolean;
  sortOrder?: number;
};

export type DepartmentConfig = {
  id: number;
  code: string;
  name: string;
  parentCode?: string;
  enabled: boolean;
  sortOrder?: number;
  source?: string;
  accountCount?: number;
  roleCount?: number;
  createdAt?: string;
  updatedAt?: string;
};

export type DepartmentPayload = {
  code?: string;
  name: string;
  parentCode?: string;
  enabled?: boolean;
  sortOrder?: number;
};

export type Account = {
  id: number;
  username: string;
  displayName: string;
  role: AccountRole | string;
  department?: string;
  dataScope?: AccountDataScope | string;
  enabled: boolean;
  ssoSubject?: string;
  lastLoginAt?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type AccountPayload = {
  username?: string;
  password?: string;
  displayName: string;
  role: AccountRole | string;
  enabled?: boolean;
  department?: string;
  dataScope?: AccountDataScope | string;
  ssoSubject?: string;
};

export type SsoConfig = {
  enabled: boolean;
  providerName: string;
  callbackConfigured?: boolean;
  autoProvision?: boolean;
};

export type SsoLoginResponse = {
  providerName: string;
  loginUrl: string;
};

export type AiRiskKey = "overdue" | "reopened" | "highPriority";

export type AiRiskRadarItem = {
  key: AiRiskKey;
  label: string;
  value: number;
  description: string;
  tone: "primary" | "warning" | "danger" | "positive";
  icon: string;
};

export type AiPriorityIssue = {
  id: number;
  issueId: string;
  issueNo: string;
  rank: number;
  title: string;
  priority: string;
  status: string;
  department: string;
  owner: string;
  overdueDays: number;
  repeatCount: number;
  impact: string;
  expectedImpact: string;
  reason: string;
  evidenceTags: string[];
  evidence?: string[];
  filters: AiRiskKey[];
  score: number;
};

export type AiReplyContent = {
  question: string;
  judgmentBasis: string[];
  recommendedPriority: string;
  impactScope: string;
  processingOrder: string[];
};

export type AiModelInfo = {
  provider: string;
  model: string;
};

export type AiInsightRuleAnalysis = {
  riskLevel: string;
  riskRadar: AiRiskRadarItem[];
  priorityIssues: AiPriorityIssue[];
  summary: string;
  suggestedActions: string[];
  issueContext: AiPriorityIssue[];
  source: string;
};

export type AiInsightAiAnalysis = {
  available: boolean;
  applied: boolean;
  provider: string;
  model: string;
  status?: "pending" | "applied" | "failed";
  summary?: string;
  riskLevel?: string;
  aiReply?: AiReplyContent | Record<string, unknown>;
  suggestedActions?: string[];
  error?: string;
  failure?: {
    code: string;
    message: string;
  };
};

export type AiInsightFallback = {
  used: boolean;
  reason?: string;
};

export type AiActionType = "CREATE_ISSUE" | "UPDATE_STATUS" | "ADD_LOG";

export type AiPendingAction = {
  actionId: string;
  actionType: AiActionType;
  title: string;
  summary: string;
  payload: Record<string, unknown>;
  warnings: string[];
  requiresConfirmation: boolean;
  expiresAt?: string;
  expiresInSeconds?: number;
};

export type AiActionExecution = {
  executed: boolean;
  actionType: AiActionType;
  message: string;
  issue?: Issue;
  logId?: number;
  executedAt: string;
};

export type AiInsightOverview = {
  insightId: string;
  period: string;
  totalIssues: number;
  updatedAt: string;
  riskLevel: string;
  riskRadar: AiRiskRadarItem[];
  priorityIssues: AiPriorityIssue[];
  aiReply: AiReplyContent;
  suggestedActions: string[];
  issueContext: AiPriorityIssue[];
  summary: string;
  generatedBy: string;
  aiAvailable: boolean;
  aiError?: string;
  aiStatus?: "pending" | "applied" | "failed";
  aiFailure?: {
    code: string;
    message: string;
  };
  modelInfo: AiModelInfo;
  ruleAnalysis?: AiInsightRuleAnalysis;
  aiAnalysis?: AiInsightAiAnalysis;
  finalView?: {
    riskLevel: string;
    riskRadar: AiRiskRadarItem[];
    priorityIssues: AiPriorityIssue[];
    summary: string;
    aiReply: AiReplyContent;
    suggestedActions: string[];
  };
  fallback?: AiInsightFallback;
};

export type AiChatAnswer = {
  insightId: string;
  sessionId?: string;
  question: string;
  answer: string;
  evidence: string[];
  suggestedActions: string[];
  relatedIssues: AiPriorityIssue[];
  generatedAt: string;
  generatedBy: string;
  model: string;
  aiError?: string;
  pendingAction?: AiPendingAction | null;
};

export type AiInsightSession = {
  sessionId: string;
  insightId: string;
  title: string;
  createdAt: string;
  updatedAt: string;
};

export type AiInsightMessage = {
  id: number;
  sessionId: string;
  role: "user" | "assistant";
  content: string;
  structured?: Partial<AiChatAnswer>;
  model?: string;
  generatedBy?: string;
  createdAt: string;
};

export type AiStreamEvent =
  | { type: "session"; data: AiInsightSession }
  | { type: "thinking"; data: { step: string } }
  | { type: "delta"; data: { text: string } }
  | { type: "action"; data: AiPendingAction }
  | { type: "answer"; data: AiChatAnswer }
  | { type: "done"; data: { sessionId: string; generatedBy: string; model: string } }
  | { type: "error"; data: { message: string } };

export type IssueAiAnalysis = {
  type: "root-cause" | "suggestion" | "duplicate";
  issueId: number;
  issueNo: string;
  title: string;
  summary: string;
  evidence: string[];
  suggestedActions: string[];
  draft: string[];
  relatedIssues: Array<{
    id: number;
    issueNo: string;
    title: string;
    businessScene?: string;
    issueType?: string;
    status?: string;
    priority?: string;
    department?: string;
    reopened?: boolean;
  }>;
  generatedAt: string;
  generatedBy: string;
  model: string;
  aiError?: string;
};

export type RecurrenceHypothesis = {
  hypothesis: string;
  confidence: number;
  evidence: string[];
  grounded: boolean;
};

export type RecurrenceCorrelatedIssue = {
  issueNo: string;
  relation: string;
};

export type RecurrenceGroundingReport = {
  validIssueNoCount: number;
  droppedCorrelations?: string[];
  fabricatedReferences?: string[];
  maxConfidence?: number;
};

export type RecurrenceInsight = {
  issueNo: string;
  title: string;
  recurrenceSummary: string;
  rootCauseHypotheses: RecurrenceHypothesis[];
  whyPreviousFixFailed: string;
  correlatedIssues: RecurrenceCorrelatedIssue[];
  systemicFix: string[];
  verifyPlan: string[];
  needHumanReview: boolean;
  groundingReport: RecurrenceGroundingReport;
  analysisMode: "ai" | "evidence-only";
  generatedBy: string;
  model: string;
};

export type AnalysisItem = {
  name: string;
  value: number;
  reopened?: number;
};

export type OptimizationSuggestion = {
  title: string;
  description: string;
  owner: string;
  expectedImpact: string;
};

export type ReportData = {
  topIssues: AnalysisItem[];
  duplicateCount: number;
  typeDistribution: AnalysisItem[];
  departmentDistribution: AnalysisItem[];
  averageHandleHours: number;
  overdueIssues: Issue[];
  suggestions: OptimizationSuggestion[];
};

export type ReportSubScore = {
  label: string;
  value: number;
  delta: string;
  deltaValue?: number;
  deltaTone?: "up" | "down" | "flat" | string;
};

export type ReportAnalysisSummary = {
  total: number;
  current: number;
  newIssues?: number;
  pending?: number;
  overdue: number;
  reopened: number;
  highPriority: number;
  completed: number;
  slaRate: number;
  overdueRate: number;
  reopenedRate: number;
  averageHandleHours: number;
  governanceScore: number;
  governanceDelta?: number;
  subScores: ReportSubScore[];
};

export type ReportDimensionItem = {
  key: string;
  name: string;
  value: number;
  share: number;
  overdueCount: number;
  overdueRate: number;
  reopenedCount: number;
  reopenedRate: number;
  averageHandleHours: number;
  riskLevel: "高" | "中" | "低" | string;
};

export type ReportDimension = {
  key: "businessScene" | "issueType" | "responsibleDepartment" | "source" | "impactScope" | string;
  label: string;
  items: ReportDimensionItem[];
};

export type ReportAnalysisTrendPoint = {
  date: string;
  newIssues: number;
  completed: number;
  pending: number;
  overdue: number;
};

export type ReportEfficiencyBucket = {
  label: string;
  total: number;
  highPriority: number;
  normal: number;
};

export type ReportKeyChange = {
  metric?: string;
  title: string;
  description: string;
  detail?: string;
  value?: string;
  delta?: number;
  direction?: "up" | "down" | "flat" | string;
  evidence?: number;
  tone: "primary" | "warning" | "positive" | "neutral" | string;
};

export type ReportPeriod = {
  startDate: string;
  endDate: string;
  previousStartDate: string;
  previousEndDate: string;
  label: string;
  previousLabel: string;
};

export type ReportPeriodSummaryItem = {
  label: string;
  value: number;
  previousValue: number;
  delta: string;
  deltaValue: number;
  deltaRate: number;
  tone: "up" | "down" | "danger" | "flat" | string;
};

export type ReportStructureMatrixRow = {
  name: string;
  source: number;
  impact: number;
  reopened: number;
  overdue: number;
  value?: number;
};

export type ReportPriorityEfficiencyRow = {
  label: string;
  values: number[];
  average: number;
  averageDays?: number;
  total?: number;
};

export type ReportDatasetCard = {
  key: string;
  title: string;
  desc?: string;
  description?: string;
  count: number;
  unit?: string;
  countLabel?: string;
  tone: string;
};

export type ReportTrendEvent = {
  date: string;
  label: string;
};

export type ReportAnalysisData = {
  period?: ReportPeriod;
  appliedFilters?: {
    departments?: string[];
    startDate?: string;
    endDate?: string;
  };
  availableDepartments?: string[];
  summary: ReportAnalysisSummary;
  periodSummary?: ReportPeriodSummaryItem[];
  dimensions: ReportDimension[];
  trend: ReportAnalysisTrendPoint[];
  efficiencyBuckets: ReportEfficiencyBucket[];
  keyChanges: ReportKeyChange[];
  structureMatrix?: ReportStructureMatrixRow[];
  priorityEfficiency?: ReportPriorityEfficiencyRow[];
  datasets?: ReportDatasetCard[];
  events?: ReportTrendEvent[];
  issues: Issue[];
  metricDefinitions: string[];
  updatedAt: string;
};

export type RetrospectivePipelineStep = {
  label: string;
  description: string;
  value: number;
};

export type RetrospectiveQueueItem = {
  id: number;
  issueNo: string;
  title: string;
  priority: string;
  status: string;
  retrospectiveStatus: string;
  reviewReason: string;
  department: string;
  owner: string;
  deadline: string;
  overdueDays: number;
  rootCauseTag: string;
  impact: string;
  score: number;
};

export type RetrospectiveCauseCluster = {
  name: string;
  count: number;
  share: number;
  changePercent: number;
  issueNos: string[];
};

export type RetrospectiveAction = {
  title: string;
  owner: string;
  department: string;
  deadline: string;
  progress: number;
  status: string;
  sourceIssueId: number;
  sourceIssueNo: string;
};

export type RetrospectiveAiSuggestion = {
  available: boolean;
  applied: boolean;
  generatedBy: string;
  model: string;
  generatedAt?: string;
  error?: string;
  summary?: string;
  priorityIssueNos?: string[];
  evidence?: string[];
  nextActions?: string[];
};

export type RetrospectiveOverview = {
  updatedAt: string;
  period: string;
  pipeline: {
    steps: RetrospectivePipelineStep[];
    queueCount: number;
  };
  reviewQueue: RetrospectiveQueueItem[];
  causeClusters: RetrospectiveCauseCluster[];
  actionClosure: {
    pending: number;
    inProgress: number;
    completed: number;
    completionRate: number;
    actions: RetrospectiveAction[];
  };
  aiSuggestion: RetrospectiveAiSuggestion;
  modelInfo: AiModelInfo;
  aiAvailable: boolean;
};

export type RetrospectiveDraft = {
  available: boolean;
  generatedBy: string;
  model: string;
  generatedAt?: string;
  error?: string;
  issueId?: number;
  issueNo?: string;
  title?: string;
  rootCauseDraft?: string;
  fixReview?: string;
  verificationConclusion?: string;
  preventionActions?: string[];
  reusePlaybook?: string[];
  evidence?: string[];
};

export type ApiResponse<T> = { code: number; message: string; data: T };

export type DictionaryType =
  | "ISSUE_SOURCE"
  | "BUSINESS_SCENE"
  | "ISSUE_TYPE"
  | "IMPACT_SCOPE";

export type DictionaryItem = {
  id: number;
  dictType: DictionaryType;
  code: string;
  name: string;
  description?: string;
  sortOrder: number;
  enabled: boolean;
  systemBuiltin: boolean;
  createdAt: string;
  updatedAt: string;
  deleted: boolean;
};

export type DictionaryUsage = {
  used: boolean;
  issueCount: number;
  canDelete: boolean;
};
