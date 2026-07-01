import axios from "axios";
import type {
  AuthSession,
  AuthUser,
  AiChatAnswer,
  AiActionExecution,
  AiInsightMessage,
  AiInsightOverview,
  AiInsightSession,
  AiStreamEvent,
  ApiResponse,
  DashboardData,
  DictionaryItem,
  DictionaryType,
  DictionaryUsage,
  Issue,
  IssueAiAnalysis,
  PageData,
  RecurrenceInsight,
  ReportData,
  RetrospectiveDraft,
  RetrospectiveAiSuggestion,
  RetrospectiveOverview,
  TrendPoint,
} from "./types";

const AUTH_STORAGE_KEY = "issueOpsAuth";

function resolveApiBaseUrl() {
  if (import.meta.env.VITE_API_BASE_URL) return import.meta.env.VITE_API_BASE_URL;
  if (typeof window !== "undefined") {
    const host = window.location.hostname;
    if (host === "localhost" || host === "127.0.0.1") {
      return "http://127.0.0.1:8080/api";
    }
  }
  return "/api";
}

const apiBaseUrl = resolveApiBaseUrl();

const http = axios.create({
  baseURL: apiBaseUrl,
  timeout: 70000,
});

function getStoredAuthToken() {
  if (typeof window === "undefined") return "";
  try {
    const raw = window.localStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return "";
    return (JSON.parse(raw) as AuthSession).token || "";
  } catch {
    return "";
  }
}

function apiUrl(path: string) {
  return `${apiBaseUrl}${path}`;
}

http.interceptors.request.use((config) => {
  const token = getStoredAuthToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401 && typeof window !== "undefined") {
      window.localStorage.removeItem(AUTH_STORAGE_KEY);
      window.dispatchEvent(new CustomEvent("issueops:auth-expired"));
    }
    return Promise.reject(
      new Error(error.response?.data?.message || error.message || "请求失败"),
    );
  },
);

async function streamAiInsightChat(
  sessionId: string,
  data: { question: string; insightId?: string; context?: unknown },
  onEvent: (event: AiStreamEvent) => void,
) {
  const response = await fetch(
    apiUrl(`/ai-insights/sessions/${sessionId}/chat/stream`),
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...(getStoredAuthToken()
          ? { Authorization: `Bearer ${getStoredAuthToken()}` }
          : {}),
      },
      body: JSON.stringify(data),
    },
  );
  if (!response.ok || !response.body) {
    throw new Error(`AI 流式请求失败：${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  const flushEvent = (raw: string) => {
    const lines = raw.split(/\r?\n/);
    const eventName =
      lines
        .find((line) => line.startsWith("event:"))
        ?.replace(/^event:\s*/, "")
        .trim() || "message";
    const dataText = lines
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.replace(/^data:\s?/, ""))
      .join("\n")
      .trim();
    if (!dataText) return;
    let parsedData: unknown;
    try {
      parsedData = JSON.parse(dataText);
    } catch (error) {
      if (eventName !== "delta") throw error;
      parsedData = { text: dataText };
    }
    onEvent({
      type: eventName as AiStreamEvent["type"],
      data: parsedData,
    } as AiStreamEvent);
  };

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
    const chunks = buffer.split(/\r?\n\r?\n/);
    buffer = chunks.pop() || "";
    chunks.forEach(flushEvent);
    if (done) break;
  }
  if (buffer.trim()) flushEvent(buffer);
}

export const issueApi = {
  login: (data: { username: string; password: string }) =>
    http
      .post<unknown, ApiResponse<AuthSession>>("/auth/login", data)
      .then((response) => response.data),

  me: () =>
    http
      .get<unknown, ApiResponse<AuthUser>>("/auth/me")
      .then((response) => response.data),

  list: (params: Record<string, unknown>) =>
    http
      .get<unknown, ApiResponse<PageData<Issue>>>("/issues", { params })
      .then((response) => response.data),

  get: (id: string | number) =>
    http
      .get<unknown, ApiResponse<Issue>>(`/issues/${id}`)
      .then((response) => response.data),

  create: (data: Partial<Issue>) =>
    http
      .post<unknown, ApiResponse<Issue>>("/issues", data)
      .then((response) => response.data),

  update: (id: number, data: Partial<Issue>) =>
    http
      .put<unknown, ApiResponse<Issue>>(`/issues/${id}`, data)
      .then((response) => response.data),

  remove: (id: number) => http.delete(`/issues/${id}`),

  status: (
    id: number,
    data: { status: string; operator: string; content?: string },
  ) =>
    http
      .patch<unknown, ApiResponse<Issue>>(`/issues/${id}/status`, data)
      .then((response) => response.data),

  reopened: (
    id: number,
    data: { reopened: boolean; reason?: string; operator: string },
  ) =>
    http
      .patch<unknown, ApiResponse<Issue>>(`/issues/${id}/reopened`, data)
      .then((response) => response.data),

  log: (
    id: number,
    data: { actionType: string; content: string; operator: string },
  ) => http.post(`/issues/${id}/logs`, data),

  dashboard: () =>
    http
      .get<unknown, ApiResponse<DashboardData>>("/dashboard/statistics")
      .then((response) => response.data),

  trend: (range = "8w") =>
    http
      .get<unknown, ApiResponse<TrendPoint[]>>("/dashboard/trend", {
        params: { range },
      })
      .then((response) => response.data),

  aiInsightOverview: () =>
    http
      .get<unknown, ApiResponse<AiInsightOverview>>("/ai-insights/overview")
      .then((response) => response.data),

  aiInsightRefresh: () =>
    http
      .post<unknown, ApiResponse<AiInsightOverview>>("/ai-insights/refresh")
      .then((response) => response.data),

  aiInsightAiAnalysis: () =>
    http
      .get<unknown, ApiResponse<AiInsightOverview>>("/ai-insights/ai-analysis")
      .then((response) => response.data),

  aiInsightChat: (data: {
    question: string;
    insightId?: string;
    context?: unknown;
  }) =>
    http
      .post<unknown, ApiResponse<AiChatAnswer>>("/ai-insights/chat", data)
      .then((response) => response.data),

  aiInsightCreateSession: (data: { insightId?: string; title?: string }) =>
    http
      .post<unknown, ApiResponse<AiInsightSession>>("/ai-insights/sessions", data)
      .then((response) => response.data),

  aiInsightSessionMessages: (sessionId: string) =>
    http
      .get<unknown, ApiResponse<AiInsightMessage[]>>(
        `/ai-insights/sessions/${sessionId}/messages`,
      )
      .then((response) => response.data),

  aiInsightChatStream: (
    sessionId: string,
    data: { question: string; insightId?: string; context?: unknown },
    onEvent: (event: AiStreamEvent) => void,
  ) => streamAiInsightChat(sessionId, data, onEvent),

  aiActionExecute: (actionId: string) =>
    http
      .post<unknown, ApiResponse<AiActionExecution>>(
        "/ai-insights/actions/execute",
        { actionId },
      )
      .then((response) => response.data),

  report: () =>
    http
      .get<unknown, ApiResponse<ReportData>>("/reports/overview")
      .then((response) => response.data),

  retrospectiveOverview: () =>
    http
      .get<unknown, ApiResponse<RetrospectiveOverview>>(
        "/retrospectives/overview",
      )
      .then((response) => response.data),

  retrospectiveAiSuggestion: () =>
    http
      .get<unknown, ApiResponse<RetrospectiveAiSuggestion>>(
        "/retrospectives/ai-suggestion",
      )
      .then((response) => response.data),

  retrospectiveDraft: (data: { issueId?: number }) =>
    http
      .post<unknown, ApiResponse<RetrospectiveDraft>>(
        "/retrospectives/draft",
        data,
      )
      .then((response) => response.data),

  ai: (id: number, type: string) =>
    http
      .post<
        unknown,
        ApiResponse<IssueAiAnalysis>
      >(`/issues/${id}/ai/${type}`)
      .then((response) => response.data),

  recurrenceInsight: (id: number) =>
    http
      .get<
        unknown,
        ApiResponse<RecurrenceInsight>
      >(`/ai-insights/recurrence/${id}`)
      .then((response) => response.data),

  dictionaryList: (params: { type: DictionaryType; enabledOnly?: boolean }) =>
    http
      .get<unknown, ApiResponse<DictionaryItem[]>>("/dictionaries", {
        params,
      })
      .then((response) => response.data),

  dictionaries: (params?: { enabledOnly?: boolean }) =>
    http
      .get<unknown, ApiResponse<Record<DictionaryType, DictionaryItem[]>>>(
        "/dictionaries/grouped",
        { params: { enabledOnly: params?.enabledOnly } },
      )
      .then((response) => response.data),

  dictionaryCreate: (
    data: Partial<DictionaryItem> & { dictType: DictionaryType; name: string },
  ) =>
    http
      .post<unknown, ApiResponse<DictionaryItem>>("/dictionaries", data)
      .then((response) => response.data),

  dictionaryUpdate: (
    id: number,
    data: Partial<DictionaryItem> & { dictType: DictionaryType; name: string },
  ) =>
    http
      .put<unknown, ApiResponse<DictionaryItem>>(`/dictionaries/${id}`, data)
      .then((response) => response.data),

  dictionaryEnabled: (id: number, enabled: boolean) =>
    http
      .patch<unknown, ApiResponse<DictionaryItem>>(`/dictionaries/${id}/enabled`, {
        enabled,
      })
      .then((response) => response.data),

  dictionaryUsage: (id: number) =>
    http
      .get<unknown, ApiResponse<DictionaryUsage>>(`/dictionaries/${id}/usage`)
      .then((response) => response.data),

  dictionaryRemove: (id: number) => http.delete(`/dictionaries/${id}`),
};
