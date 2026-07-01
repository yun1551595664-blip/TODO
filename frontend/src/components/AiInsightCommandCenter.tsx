import {
  ArrowRightOutlined,
  ClockCircleOutlined,
  ReloadOutlined,
  RetweetOutlined,
  RobotOutlined,
  SendOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { Button, Empty, Input, Spin, message } from "antd";
import type { ReactNode } from "react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { issueApi } from "../api";
import type {
  AiChatAnswer,
  AiInsightMessage,
  AiInsightOverview,
  AiPendingAction,
  AiPriorityIssue,
  AiRiskKey,
  AiRiskRadarItem,
} from "../types";
import StatusTag from "./StatusTag";

const riskIcons: Record<string, ReactNode> = {
  clock: <ClockCircleOutlined />,
  repeat: <RetweetOutlined />,
  priority: <ThunderboltOutlined />,
};

type AssistantStatus = "idle" | "thinking" | "streaming" | "done" | "error";

type AssistantSection = {
  title: string;
  items: string[];
};

const thinkingSteps = [
  "正在读取问题数据",
  "正在分析超时与优先级",
  "正在判断影响范围",
  "正在生成处理建议",
];

const idlePrompts = [
  "哪个问题最紧急？",
  "为什么这个问题排第一？",
  "哪些问题会影响客户体验？",
  "帮我生成本周处理建议",
];

const followUpPrompts = [
  "为什么它比支付问题更紧急？",
  "帮我生成处理计划",
  "按负责人拆分任务",
  "生成给领导看的汇报话术",
];

const statusLabels: Record<AssistantStatus, string> = {
  idle: "等待提问",
  thinking: "思考中",
  streaming: "流式回复中",
  done: "回复完成",
  error: "请求失败",
};

const minThinkingMs = 1700;
const maxAssistantTurns = 4;
const sessionStorageKey = "issueOps.aiInsight.sessionId";

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}

function formatTime(value?: string) {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  return `${String(date.getMonth() + 1).padStart(2, "0")}-${String(
    date.getDate(),
  ).padStart(2, "0")} ${String(date.getHours()).padStart(2, "0")}:${String(
    date.getMinutes(),
  ).padStart(2, "0")}`;
}

function aiOverviewStage(overview: AiInsightOverview, loading: boolean) {
  if (loading || overview.aiStatus === "pending" || overview.aiAnalysis?.status === "pending") {
    return "AI 分析中";
  }
  if (overview.aiStatus === "failed" || overview.aiAnalysis?.status === "failed") {
    return "AI 分析失败";
  }
  if (overview.aiAnalysis?.applied || overview.aiStatus === "applied") {
    return "规则计算 + AI 解释";
  }
  return "规则计算";
}

function aiModelLabel(overview: AiInsightOverview) {
  if (overview.aiAvailable) {
    return `${overview.modelInfo.provider} · ${overview.modelInfo.model}`;
  }
  return "未配置 AI";
}

function RiskCard({
  item,
  active,
  onClick,
}: {
  item: AiRiskRadarItem;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`ai-risk-card ai-risk-card-${item.tone} ${active ? "active" : ""}`}
      onClick={onClick}
    >
      <span className="ai-risk-icon">{riskIcons[item.icon] || <WarningOutlined />}</span>
      <span className="ai-risk-copy">
        <b>{item.value}</b>
        <em>{item.label}</em>
        <small>{item.description}</small>
      </span>
    </button>
  );
}

function evidenceTags(issue: AiPriorityIssue) {
  const duplicated = new Set([issue.priority, issue.status]);
  return (issue.evidenceTags || [])
    .filter((tag) => tag && !duplicated.has(tag))
    .slice(0, 3);
}

function PriorityIssueRow({
  issue,
  index,
  onOpen,
}: {
  issue: AiPriorityIssue;
  index: number;
  onOpen: () => void;
}) {
  const evidence = evidenceTags(issue);
  return (
    <button type="button" className="ai-priority-row" onClick={onOpen}>
      <span className="ai-priority-rank">{index + 1}</span>
      <span className="ai-priority-main">
        <span className="ai-priority-title">{issue.title}</span>
        <span className="ai-priority-meta">
          <i>{issue.priority}</i>
          <StatusTag status={issue.status} size="compact" />
          <span>{issue.department}</span>
          <span>{issue.owner}</span>
        </span>
        <span className="ai-evidence-tags">
          {evidence.map((tag) => (
            <em key={tag}>{tag}</em>
          ))}
        </span>
      </span>
      <span className="ai-priority-impact">
        <small>预期影响</small>
        <b>{issue.expectedImpact}</b>
      </span>
      <ArrowRightOutlined className="ai-row-arrow" />
    </button>
  );
}

function actionTypeLabel(type: AiPendingAction["actionType"]) {
  const labels: Record<AiPendingAction["actionType"], string> = {
    CREATE_ISSUE: "新增问题",
    UPDATE_STATUS: "更新状态",
    ADD_LOG: "新增处理记录",
  };
  return labels[type] || type;
}

function formatPayloadValue(value: unknown) {
  if (value === undefined || value === null || value === "") return "未填写";
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

function isLowValueQuestion(value: string) {
  const text = value.trim().toLowerCase();
  const lowValueKeywords = ["你是什么模型", "什么模型", "你是谁", "model"];
  const businessKeywords = [
    "问题",
    "超期",
    "优先",
    "风险",
    "客户",
    "处理",
    "负责人",
    "复发",
    "状态",
    "计划",
    "汇报",
    "影响",
    "部门",
    "建议",
    "p0",
    "p1",
  ];
  return (
    lowValueKeywords.some((keyword) => text.includes(keyword)) &&
    !businessKeywords.some((keyword) => text.includes(keyword))
  );
}

function compactList(items: Array<string | undefined | null>, limit = 4) {
  return Array.from(
    new Set(items.map((item) => item?.trim()).filter(Boolean) as string[]),
  ).slice(0, limit);
}

function textSnippet(value: string, limit = 120) {
  const normalized = value.replace(/\s+/g, " ").trim();
  if (normalized.length <= limit) return normalized;
  return `${normalized.slice(0, limit)}...`;
}

function relatedIssueScope(answer: AiChatAnswer, overview: AiInsightOverview) {
  return answer.relatedIssues?.length
    ? answer.relatedIssues
    : overview.priorityIssues.slice(0, 3);
}

function deadlineText(issue: AiPriorityIssue) {
  if (issue.priority === "P0" || issue.overdueDays > 0) {
    return `${issue.title}：今天下班前确认阻塞点、责任人和下一步时间。`;
  }
  if (issue.priority === "P1") {
    return `${issue.title}：明日 12:00 前给出处理计划或验证结论。`;
  }
  return `${issue.title}：2 个工作日内补齐影响范围和预计完成时间。`;
}

function buildAssistantSections(
  answer: AiChatAnswer,
  overview: AiInsightOverview,
): AssistantSection[] {
  const scopedIssues = relatedIssueScope(answer, overview);
  const riskReasons = compactList(
    scopedIssues.map((issue) => {
      const signals = compactList(
        [
          issue.priority === "P0" || issue.priority === "P1"
            ? `${issue.priority} 高优先级`
            : undefined,
          issue.overdueDays > 0 ? `已超期 ${issue.overdueDays} 天` : undefined,
          issue.repeatCount > 0 ? `复发 ${issue.repeatCount} 次` : undefined,
          issue.impact,
        ],
        4,
      );
      return signals.length ? `${issue.title}：${signals.join("，")}` : undefined;
    }),
    4,
  );
  const owners = compactList(
    scopedIssues.map(
      (issue) => `${issue.title}：${issue.department} / ${issue.owner}`,
    ),
    4,
  );
  return [
    {
      title: "结论",
      items: compactList([answer.answer || overview.summary], 1),
    },
    {
      title: "判断依据",
      items: compactList(answer.evidence?.length ? answer.evidence : [overview.summary], 4),
    },
    {
      title: "风险原因",
      items: riskReasons.length ? riskReasons : [overview.summary],
    },
    {
      title: "建议动作",
      items: compactList(answer.suggestedActions || [], 4),
    },
    {
      title: "建议负责人",
      items: owners.length ? owners : ["当前数据不足，建议先补齐责任部门和责任人。"],
    },
    {
      title: "建议截止时间",
      items: scopedIssues.length
        ? scopedIssues.slice(0, 3).map(deadlineText)
        : ["当前数据不足，无法判断截止时间。"],
    },
  ].filter((section) => section.items.length > 0);
}

function buildStreamText(answer: AiChatAnswer, overview: AiInsightOverview) {
  return buildAssistantSections(answer, overview)
    .map((section) => `${section.title}\n${section.items.map((item) => `- ${item}`).join("\n")}`)
    .join("\n\n");
}

function localGuardAnswer(
  question: string,
  overview: AiInsightOverview,
  visibleIssues: AiPriorityIssue[],
): AiChatAnswer {
  return {
    insightId: overview.insightId,
    question,
    answer:
      "我会聚焦当前问题数据做分析，不展开模型身份等闲聊。当前可以继续分析优先级、超期风险、客户影响、负责人拆分和处理计划。",
    evidence: compactList(
      [
        overview.summary,
        `当前风险等级：${overview.riskLevel}`,
        `当前可见优先队列：${visibleIssues.map((issue) => issue.title).join("、")}`,
      ],
      4,
    ),
    suggestedActions: [
      "改问：哪个问题最紧急？",
      "改问：按负责人拆分任务",
      "改问：生成本周处理建议",
    ],
    relatedIssues: visibleIssues.slice(0, 3),
    generatedAt: new Date().toISOString(),
    generatedBy: "local-rules",
    model: "business-guard",
    pendingAction: null,
  };
}

function answerFromMessage(message: AiInsightMessage): AiChatAnswer | undefined {
  if (message.role !== "assistant" || !message.structured?.answer) return undefined;
  return {
    insightId: message.structured.insightId || "",
    sessionId: message.sessionId,
    question: message.structured.question || "",
    answer: message.structured.answer || message.content,
    evidence: message.structured.evidence || [],
    suggestedActions: message.structured.suggestedActions || [],
    relatedIssues: message.structured.relatedIssues || [],
    generatedAt: message.structured.generatedAt || message.createdAt,
    generatedBy: message.structured.generatedBy || message.generatedBy || "",
    model: message.structured.model || message.model || "",
    aiError: message.structured.aiError,
    pendingAction: message.structured.pendingAction || null,
  };
}

function fieldLabel(key: string) {
  const labels: Record<string, string> = {
    title: "问题标题",
    description: "问题描述",
    source: "问题来源",
    businessScene: "业务场景",
    issueType: "问题类型",
    impactScope: "影响范围",
    customerImpact: "客户影响",
    reproduceSteps: "复现步骤",
    priority: "优先级",
    status: "目标状态",
    responsibleDepartment: "责任部门",
    responsiblePerson: "责任人",
    issueNo: "问题编号",
    content: "操作说明",
    operator: "操作人",
  };
  return labels[key] || key;
}

function visiblePayloadFields(action: AiPendingAction) {
  const order = [
    "title",
    "issueNo",
    "description",
    "source",
    "businessScene",
    "issueType",
    "impactScope",
    "customerImpact",
    "priority",
    "status",
    "responsibleDepartment",
    "responsiblePerson",
    "content",
    "operator",
  ];
  const entries = Object.entries(action.payload || {}).filter(
    ([, value]) => value !== undefined && value !== null && value !== "",
  );
  return entries.sort(
    ([left], [right]) =>
      (order.includes(left) ? order.indexOf(left) : 999) -
      (order.includes(right) ? order.indexOf(right) : 999),
  );
}

function actionImpactText(action: AiPendingAction) {
  if (action.actionType === "CREATE_ISSUE") {
    return "确认后将创建一条新问题，并进入问题台账与后续跟进流程。";
  }
  if (action.actionType === "UPDATE_STATUS") {
    return `确认后将把该问题状态更新为「${formatPayloadValue(
      action.payload?.status,
    )}」，并写入处理记录。`;
  }
  return "确认后将把这条处理记录追加到问题详情时间线。";
}

function PendingActionCard({
  action,
  loading,
  onExecute,
  onDismiss,
}: {
  action: AiPendingAction;
  loading: boolean;
  onExecute: (action: AiPendingAction) => void;
  onDismiss: () => void;
}) {
  const fields = visiblePayloadFields(action);
  return (
    <section className="ai-pending-action">
      <div className="ai-pending-head">
        <span>待确认 · {actionTypeLabel(action.actionType)}</span>
        <b>10 分钟内有效</b>
      </div>
      <h4>{action.title}</h4>
      <p>{action.summary}</p>
      {action.expiresAt && (
        <p className="ai-pending-expire">有效期至 {formatTime(action.expiresAt)}</p>
      )}
      <div className="ai-pending-impact">
        <small>写入影响</small>
        <b>{actionImpactText(action)}</b>
      </div>
      <div className="ai-pending-fields">
        {fields.slice(0, 7).map(([key, value]) => (
          <span key={key}>
            <small>{fieldLabel(key)}</small>
            <b>{formatPayloadValue(value)}</b>
          </span>
        ))}
      </div>
      {action.warnings?.length > 0 && (
        <ul>
          {action.warnings.slice(0, 3).map((item) => (
            <li key={item}>{item}</li>
          ))}
        </ul>
      )}
      <div className="ai-pending-actions">
        <Button onClick={onDismiss} disabled={loading}>
          暂不执行
        </Button>
        <Button
          type="primary"
          loading={loading}
          disabled={!action.actionId}
          onClick={() => onExecute(action)}
        >
          确认写入系统
        </Button>
      </div>
    </section>
  );
}

function UserQuestionBubble({ question }: { question: string }) {
  return (
    <div className="ai-chat-row ai-chat-row-user">
      <div className="ai-chat-bubble ai-chat-bubble-user">{question}</div>
    </div>
  );
}

function AssistantHistorySummary({ answer }: { answer: AiChatAnswer }) {
  return (
    <div className="ai-chat-row ai-chat-row-ai">
      <div className="ai-chat-history-card">
        <span>历史回复</span>
        <p>{textSnippet(answer.answer || "已生成分析回复。")}</p>
      </div>
    </div>
  );
}

function AssistantThinking({ activeStep }: { activeStep: number }) {
  return (
    <div className="ai-chat-row ai-chat-row-ai">
      <div className="ai-thinking-card">
        <div className="ai-thinking-head">
          <span>AI 正在分析</span>
          <i>
            <em />
            <em />
            <em />
          </i>
        </div>
        <div className="ai-thinking-steps">
          {thinkingSteps.map((step, index) => (
            <span
              key={step}
              className={
                index < activeStep ? "done" : index === activeStep ? "active" : ""
              }
            >
              {step}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

function AssistantStreaming({ text }: { text: string }) {
  return (
    <div className="ai-chat-row ai-chat-row-ai">
      <div className="ai-chat-bubble ai-chat-bubble-ai">
        <pre>{text}</pre>
        <span className="ai-stream-cursor" />
      </div>
    </div>
  );
}

function AssistantDone({
  answer,
  overview,
  actionLoading,
  onExecuteAction,
  onDismissAction,
  onQuickAsk,
  showPendingAction = true,
  showFollowups = true,
}: {
  answer: AiChatAnswer;
  overview: AiInsightOverview;
  actionLoading: boolean;
  onExecuteAction: (action: AiPendingAction) => void;
  onDismissAction: () => void;
  onQuickAsk: (question: string) => void;
  showPendingAction?: boolean;
  showFollowups?: boolean;
}) {
  const sections = buildAssistantSections(answer, overview);
  return (
    <div className="ai-chat-row ai-chat-row-ai">
      <div className="ai-chat-bubble ai-chat-bubble-ai">
        <div className="ai-answer-sections">
          {sections.map((section) => (
            <section key={section.title}>
              <h4>{section.title}</h4>
              {section.items.length === 1 ? (
                <p>{section.items[0]}</p>
              ) : (
                <ol>
                  {section.items.map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ol>
              )}
            </section>
          ))}
        </div>
        {showPendingAction && answer.pendingAction && (
          <PendingActionCard
            action={answer.pendingAction}
            loading={actionLoading}
            onExecute={onExecuteAction}
            onDismiss={onDismissAction}
          />
        )}
        {showFollowups && (
          <div className="ai-followups">
            <span>快捷追问</span>
            {followUpPrompts.map((prompt) => (
              <button key={prompt} type="button" onClick={() => onQuickAsk(prompt)}>
                {prompt}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function AssistantIdle({
  overview,
  onAsk,
}: {
  overview: AiInsightOverview;
  onAsk: (question: string) => void;
}) {
  return (
    <div className="ai-assistant-idle">
      <RobotOutlined />
      <b>可以直接追问当前问题数据</b>
      <p>
        我会结合 {overview.totalIssues} 个问题、风险雷达、优先级、状态、负责人、
        超期天数和预计影响进行分析。
      </p>
      <div>
        {idlePrompts.map((prompt) => (
          <button key={prompt} type="button" onClick={() => onAsk(prompt)}>
            {prompt}
          </button>
        ))}
      </div>
    </div>
  );
}

function AssistantError({
  message: errorMessage,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div className="ai-chat-row ai-chat-row-ai">
      <div className="ai-chat-bubble ai-chat-bubble-error">
        <b>AI 分析失败</b>
        <p>{errorMessage || "请求失败，请稍后重试。"}</p>
        <Button size="small" onClick={onRetry}>
          重新分析
        </Button>
      </div>
    </div>
  );
}

export default function AiInsightCommandCenter() {
  const [overview, setOverview] = useState<AiInsightOverview>();
  const [selectedRisk, setSelectedRisk] = useState<AiRiskKey | undefined>(
    "highPriority",
  );
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [aiAnalysisLoading, setAiAnalysisLoading] = useState(false);
  const [chatLoading, setChatLoading] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [priorityExpanded, setPriorityExpanded] = useState(false);
  const [question, setQuestion] = useState("");
  const [answers, setAnswers] = useState<AiChatAnswer[]>([]);
  const [sessionId, setSessionId] = useState("");
  const [assistantStatus, setAssistantStatus] =
    useState<AssistantStatus>("idle");
  const [activeQuestion, setActiveQuestion] = useState("");
  const [thinkingStep, setThinkingStep] = useState(0);
  const [streamedReply, setStreamedReply] = useState("");
  const [assistantError, setAssistantError] = useState("");
  const [error, setError] = useState("");
  const assistantThreadRef = useRef<HTMLDivElement>(null);
  const nav = useNavigate();

  const resetAssistant = () => {
    setAssistantStatus("idle");
    setActiveQuestion("");
    setThinkingStep(0);
    setStreamedReply("");
    setAssistantError("");
    setAnswers([]);
  };

  const hydrateSessionMessages = async (targetSessionId: string) => {
    const messages = await issueApi.aiInsightSessionMessages(targetSessionId);
    const restoredAnswers = messages
      .map(answerFromMessage)
      .filter((item): item is AiChatAnswer => Boolean(item))
      .reverse()
      .slice(0, maxAssistantTurns);
    setAnswers(restoredAnswers);
    if (restoredAnswers.length) {
      setAssistantStatus("done");
      setActiveQuestion(restoredAnswers[0].question);
    } else {
      resetAssistant();
    }
  };

  const createAiSession = async (insightId: string) => {
    const session = await issueApi.aiInsightCreateSession({
      insightId,
      title: "AI 智能洞察对话",
    });
    window.localStorage.setItem(sessionStorageKey, session.sessionId);
    setSessionId(session.sessionId);
    return session.sessionId;
  };

  const restoreAiSession = async (insightId: string) => {
    const savedSessionId = window.localStorage.getItem(sessionStorageKey);
    if (savedSessionId) {
      try {
        setSessionId(savedSessionId);
        await hydrateSessionMessages(savedSessionId);
        return savedSessionId;
      } catch {
        window.localStorage.removeItem(sessionStorageKey);
      }
    }
    const createdSessionId = await createAiSession(insightId);
    resetAssistant();
    return createdSessionId;
  };

  const ensureAiSession = async (insightId: string) => {
    if (sessionId) return sessionId;
    return createAiSession(insightId);
  };

  const loadAiAnalysis = async () => {
    setAiAnalysisLoading(true);
    try {
      const data = await issueApi.aiInsightAiAnalysis();
      setOverview(data);
    } catch (err) {
      setOverview((current) =>
        current
          ? {
              ...current,
              aiError:
                err instanceof Error
                  ? err.message
                  : "AI 分析加载失败，请稍后重试",
            }
          : current,
      );
    } finally {
      setAiAnalysisLoading(false);
    }
  };

  const loadOverview = async () => {
    setLoading(true);
    setError("");
    try {
      const data = await issueApi.aiInsightOverview();
      setOverview(data);
      resetAssistant();
      await restoreAiSession(data.insightId);
      void loadAiAnalysis();
    } catch (err) {
      setError(err instanceof Error ? err.message : "AI 洞察加载失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  const refreshInsight = async () => {
    setRefreshing(true);
    setError("");
    try {
      const data = await issueApi.aiInsightRefresh();
      setOverview(data);
      resetAssistant();
      await createAiSession(data.insightId);
      void loadAiAnalysis();
      message.success("AI 洞察已刷新");
    } catch (err) {
      setError(err instanceof Error ? err.message : "AI 洞察刷新失败，请重试");
    } finally {
      setRefreshing(false);
    }
  };

  const streamAnswer = async (answer: AiChatAnswer, currentOverview: AiInsightOverview) => {
    const fullText = buildStreamText(answer, currentOverview);
    const step = Math.max(4, Math.ceil(fullText.length / 90));
    setAssistantStatus("streaming");
    setStreamedReply("");
    for (let index = step; index <= fullText.length; index += step) {
      setStreamedReply(fullText.slice(0, index));
      await delay(18);
    }
    setStreamedReply(fullText);
    setAnswers((current) => [answer, ...current].slice(0, maxAssistantTurns));
    setAssistantStatus("done");
  };

  const submitQuestion = async (forcedQuestion?: string) => {
    const value = (forcedQuestion ?? question).trim();
    if (!value || !overview || chatLoading) return;
    setActiveQuestion(value);
    setAssistantStatus("thinking");
    setThinkingStep(0);
    setStreamedReply("");
    setAssistantError("");
    setChatLoading(true);
    try {
      const activeSessionId = await ensureAiSession(overview.insightId);
      setQuestion("");
      let hasFinalAnswer = false;
      await issueApi.aiInsightChatStream(
        activeSessionId,
        {
          question: value,
          insightId: overview.insightId,
          context: {
            selectedRisk,
            riskLevel: overview.riskLevel,
            riskRadar: overview.riskRadar,
            summary: overview.summary,
            totalIssues: overview.totalIssues,
            visibleIssues: filteredIssues.map((item) => item.issueId),
            priorityIssues: filteredIssues.map((item) => ({
              issueId: item.issueId,
              issueNo: item.issueNo,
              title: item.title,
              priority: item.priority,
              status: item.status,
              department: item.department,
              owner: item.owner,
              overdueDays: item.overdueDays,
              repeatCount: item.repeatCount,
              impact: item.impact,
              expectedImpact: item.expectedImpact,
              evidenceTags: item.evidenceTags,
            })),
          },
        },
        (event) => {
          if (event.type === "session") {
            setSessionId(event.data.sessionId);
            window.localStorage.setItem(sessionStorageKey, event.data.sessionId);
            return;
          }
          if (event.type === "thinking") {
            const stepIndex = thinkingSteps.indexOf(event.data.step);
            setThinkingStep((current) =>
              stepIndex >= 0 ? Math.max(current, stepIndex) : current,
            );
            return;
          }
          if (event.type === "delta") {
            setAssistantStatus("streaming");
            setStreamedReply((current) => `${current}${event.data.text}`);
            return;
          }
          if (event.type === "answer") {
            hasFinalAnswer = true;
            if (event.data.aiError) message.warning(event.data.aiError);
            setAnswers((current) =>
              [event.data, ...current].slice(0, maxAssistantTurns),
            );
            setAssistantStatus("done");
            return;
          }
          if (event.type === "error") {
            throw new Error(event.data.message || "AI 流式回答失败");
          }
        },
      );
      if (!hasFinalAnswer) {
        setAssistantStatus("done");
      }
    } catch (err) {
      try {
        const answer = isLowValueQuestion(value)
          ? localGuardAnswer(value, overview, filteredIssues)
          : await issueApi.aiInsightChat({
              question: value,
              insightId: overview.insightId,
              context: {
                selectedRisk,
                visibleIssues: filteredIssues.map((item) => item.issueId),
              },
            });
        if (answer.aiError) message.warning(answer.aiError);
        await streamAnswer(answer, overview);
      } catch (fallbackError) {
        const messageText =
          fallbackError instanceof Error
            ? fallbackError.message
            : err instanceof Error
              ? err.message
              : "AI 回答失败，请重试";
        setAssistantError(messageText);
        setAssistantStatus("error");
        message.error(messageText);
      }
    } finally {
      setChatLoading(false);
    }
  };

  const executeAction = async (action: AiPendingAction) => {
    if (!action.actionId) {
      message.error("缺少待确认操作 ID，请重新生成操作草案");
      return;
    }
    setActionLoading(true);
    try {
      const result = await issueApi.aiActionExecute(action.actionId);
      message.success(result.message || "操作已执行");
      setAnswers((current) =>
        current.map((item, index) =>
          index === 0 ? { ...item, pendingAction: null } : item,
        ),
      );
      const data = await issueApi.aiInsightRefresh();
      setOverview(data);
      void loadAiAnalysis();
    } catch (err) {
      message.error(err instanceof Error ? err.message : "操作执行失败，请重试");
    } finally {
      setActionLoading(false);
    }
  };

  const dismissPendingAction = () => {
    setAnswers((current) =>
      current.map((item, index) =>
        index === 0 ? { ...item, pendingAction: null } : item,
      ),
    );
  };

  useEffect(() => {
    if (assistantStatus !== "thinking") return;
    const timer = window.setInterval(() => {
      setThinkingStep((current) =>
        Math.min(current + 1, thinkingSteps.length - 1),
      );
    }, 520);
    return () => window.clearInterval(timer);
  }, [assistantStatus]);

  useEffect(() => {
    const target = assistantThreadRef.current;
    if (!target) return;
    target.scrollTo({ top: target.scrollHeight, behavior: "smooth" });
  }, [assistantStatus, streamedReply, thinkingStep, answers]);

  useEffect(() => {
    loadOverview();
  }, []);

  const matchedIssues = useMemo(() => {
    if (!overview) return [];
    const source = selectedRisk
      ? overview.priorityIssues.filter((issue) => issue.filters?.includes(selectedRisk))
      : overview.priorityIssues;
    return source;
  }, [overview, selectedRisk]);

  const filteredIssues = useMemo(
    () => matchedIssues.slice(0, priorityExpanded ? 6 : 3),
    [matchedIssues, priorityExpanded],
  );

  useEffect(() => {
    setPriorityExpanded(false);
  }, [selectedRisk, overview?.insightId]);

  useEffect(() => {
    if (!overview || !selectedRisk) return;
    const riskExists = overview.riskRadar.some((item) => item.key === selectedRisk);
    const hasMatchedIssues = overview.priorityIssues.some((issue) =>
      issue.filters?.includes(selectedRisk),
    );
    if (!riskExists || !hasMatchedIssues) {
      setSelectedRisk(undefined);
    }
  }, [overview, selectedRisk]);

  const latestAnswer = answers[0];
  const completedHistory =
    assistantStatus === "done" ? answers.slice(1).reverse() : answers.slice().reverse();

  if (loading) {
    return (
      <section className="ai-command-center ai-command-loading">
        <Spin tip="AI 分析中" />
      </section>
    );
  }

  if (!overview) {
    return (
      <section className="ai-command-center ai-command-error">
        <RobotOutlined />
        <b>AI 洞察加载失败</b>
        <p>{error || "请稍后重试"}</p>
        <Button onClick={loadOverview}>重新加载</Button>
      </section>
    );
  }

  return (
    <section className="ai-command-center">
      <header className="ai-command-header">
        <div>
          <span className="ai-command-eyebrow">
            <RobotOutlined /> AI 智能洞察
          </span>
          <h2>基于问题数据的风险判断与行动优先级</h2>
        </div>
        <div className="ai-command-meta">
          <span
            className={`ai-command-stage ai-command-stage-${
              overview.aiStatus || overview.aiAnalysis?.status || "pending"
            }`}
          >
            {aiOverviewStage(overview, aiAnalysisLoading)}
          </span>
          <span>{aiModelLabel(overview)}</span>
          <span>更新于 {formatTime(overview.updatedAt)}</span>
          <Button
            type="link"
            loading={refreshing}
            onClick={refreshInsight}
            icon={<ReloadOutlined />}
          >
            刷新洞察
          </Button>
        </div>
      </header>

      {overview.aiError && !aiAnalysisLoading && (
        <div className="ai-command-alert">
          <WarningOutlined />
          {overview.aiError}
        </div>
      )}

      <div className="ai-command-grid">
        <aside className="ai-radar-panel">
          <div className="ai-panel-title">
            <span>风险雷达</span>
            <b>{overview.riskLevel}</b>
          </div>
          <div className="ai-risk-list">
            {overview.riskRadar.map((item) => (
              <RiskCard
                key={item.key}
                item={item}
                active={selectedRisk === item.key}
                onClick={() =>
                  setSelectedRisk((current) =>
                    current === item.key ? undefined : item.key,
                  )
                }
              />
            ))}
          </div>
          {selectedRisk && (
            <button
              type="button"
              className="ai-clear-filter"
              onClick={() => setSelectedRisk(undefined)}
            >
              清除筛选
            </button>
          )}
        </aside>

        <main className="ai-priority-panel">
          <div className="ai-panel-title">
            <span>本次建议优先级</span>
            <small>
              {selectedRisk
                ? `已按 ${overview.riskRadar.find((item) => item.key === selectedRisk)?.label} 筛选`
                : `基于 ${overview.totalIssues} 个问题分析`}
            </small>
          </div>
          <div className="ai-priority-list">
            {filteredIssues.length ? (
              filteredIssues.map((issue, index) => (
                <PriorityIssueRow
                  key={issue.issueId}
                  issue={issue}
                  index={index}
                  onOpen={() => nav(`/issues/${issue.id}`)}
                />
              ))
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="当前筛选下暂无需要优先处理的问题"
              />
            )}
          </div>
          {matchedIssues.length > 3 && (
            <Button
              type="link"
              className="ai-priority-toggle"
              onClick={() => setPriorityExpanded((current) => !current)}
            >
              {priorityExpanded
                ? "收起优先级列表"
                : `展开更多 ${Math.min(matchedIssues.length, 6) - 3} 条`}
            </Button>
          )}
        </main>

        <aside className="ai-reply-panel">
          <div className="ai-panel-title">
            <span>AI 分析助手</span>
            <small>{statusLabels[assistantStatus]}</small>
          </div>
          <div className="ai-assistant-thread" ref={assistantThreadRef}>
            {assistantStatus === "idle" && (
              <AssistantIdle overview={overview} onAsk={submitQuestion} />
            )}
            {completedHistory.map((answer, index) => (
              <div
                className="ai-completed-turn"
                key={`${answer.generatedAt}-${answer.question}-${index}`}
              >
                <UserQuestionBubble question={answer.question} />
                <AssistantHistorySummary answer={answer} />
              </div>
            ))}
            {assistantStatus !== "idle" && activeQuestion && (
              <UserQuestionBubble question={activeQuestion} />
            )}
            {assistantStatus === "thinking" && (
              <AssistantThinking activeStep={thinkingStep} />
            )}
            {assistantStatus === "streaming" && (
              <AssistantStreaming text={streamedReply} />
            )}
            {assistantStatus === "done" && latestAnswer && (
              <AssistantDone
                answer={latestAnswer}
                overview={overview}
                actionLoading={actionLoading}
                onExecuteAction={executeAction}
                onDismissAction={dismissPendingAction}
                onQuickAsk={submitQuestion}
              />
            )}
            {assistantStatus === "error" && (
              <AssistantError
                message={assistantError}
                onRetry={() => submitQuestion(activeQuestion)}
              />
            )}
          </div>
        </aside>
      </div>

      <div className="ai-command-input">
        <Input
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          onPressEnter={() => submitQuestion()}
          disabled={chatLoading}
          placeholder="继续追问：按部门、复发风险或超期原因分析..."
          prefix={<RobotOutlined />}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          loading={chatLoading}
          onClick={() => submitQuestion()}
        />
      </div>
    </section>
  );
}
