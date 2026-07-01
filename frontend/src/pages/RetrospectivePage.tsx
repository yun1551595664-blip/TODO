import {
  ArrowRightOutlined,
  CheckCircleFilled,
  CheckCircleOutlined,
  ClockCircleOutlined,
  ClusterOutlined,
  FileDoneOutlined,
  FilterOutlined,
  InboxOutlined,
  PlusCircleOutlined,
  ReloadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  SendOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import {
  Button,
  Empty,
  Input,
  Modal,
  Progress,
  Select,
  Skeleton,
  Tag,
  message,
} from "antd";
import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { issueApi } from "../api";
import StatusTag from "../components/StatusTag";
import type {
  RetrospectiveAiSuggestion,
  RetrospectiveDraft,
  RetrospectiveOverview,
  RetrospectiveQueueItem,
} from "../types";

const pipelineIcons = [
  CheckCircleFilled,
  InboxOutlined,
  FileDoneOutlined,
  SafetyCertificateOutlined,
  ClusterOutlined,
];

const priorityOrder = ["P0", "P1", "P2", "P3"];

function priorityTone(priority: string) {
  if (priority === "P0") return "danger";
  if (priority === "P1") return "warning";
  if (priority === "P2") return "middle";
  return "muted";
}

function retroTone(status: string) {
  if (status === "待归因") return "purple";
  if (status === "待验证") return "blue";
  if (status === "待沉淀") return "orange";
  return "green";
}

function formatPercent(value: number) {
  return value > 0 ? `↑ ${value}%` : value < 0 ? `↓ ${Math.abs(value)}%` : "持平";
}

function queueGroups(items: RetrospectiveQueueItem[]) {
  const grouped = new Map<string, RetrospectiveQueueItem[]>();
  for (const item of items) {
    const key = item.priority || "P2";
    grouped.set(key, [...(grouped.get(key) || []), item]);
  }
  return Array.from(grouped.entries()).sort(
    ([a], [b]) => priorityOrder.indexOf(a) - priorityOrder.indexOf(b),
  );
}

function statusGroups(items: RetrospectiveQueueItem[]) {
  const order = ["待归因", "待验证", "待沉淀", "已沉淀"];
  const grouped = new Map<string, RetrospectiveQueueItem[]>();
  for (const item of items) {
    const key = item.retrospectiveStatus || "待归因";
    grouped.set(key, [...(grouped.get(key) || []), item]);
  }
  return Array.from(grouped.entries()).sort(
    ([a], [b]) => order.indexOf(a) - order.indexOf(b),
  );
}

export default function RetrospectivePage() {
  const navigate = useNavigate();
  const [data, setData] = useState<RetrospectiveOverview>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [aiLoading, setAiLoading] = useState(false);
  const [keyword, setKeyword] = useState("");
  const [groupMode, setGroupMode] = useState<"priority" | "status">("priority");
  const [selectedId, setSelectedId] = useState<number>();
  const [draftLoading, setDraftLoading] = useState(false);
  const [draft, setDraft] = useState<RetrospectiveDraft>();

  const load = async (silent = false) => {
    silent ? setRefreshing(true) : setLoading(true);
    try {
      const overview = await issueApi.retrospectiveOverview();
      setData(overview);
      setSelectedId((current) => current || overview.reviewQueue[0]?.id);
      void loadAiSuggestion();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "复盘数据加载失败");
    } finally {
      silent ? setRefreshing(false) : setLoading(false);
    }
  };

  const loadAiSuggestion = async () => {
    setAiLoading(true);
    try {
      const suggestion = await issueApi.retrospectiveAiSuggestion();
      setData((current) =>
        current
          ? {
              ...current,
              aiSuggestion: suggestion,
              modelInfo: {
                provider: suggestion.generatedBy || current.modelInfo.provider,
                model: suggestion.model || current.modelInfo.model,
              },
            }
          : current,
      );
    } catch (error) {
      const fallback: RetrospectiveAiSuggestion = {
        available: false,
        applied: false,
        generatedBy: "none",
        model: "",
        error:
          error instanceof Error
            ? error.message
            : "AI 复盘建议加载失败，请稍后重试。",
      };
      setData((current) =>
        current ? { ...current, aiSuggestion: fallback } : current,
      );
    } finally {
      setAiLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const filteredQueue = useMemo(() => {
    if (!data) return [];
    const kw = keyword.trim().toLowerCase();
    if (!kw) return data.reviewQueue;
    return data.reviewQueue.filter((item) =>
      [
        item.issueNo,
        item.title,
        item.owner,
        item.department,
        item.rootCauseTag,
        item.reviewReason,
      ]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(kw)),
    );
  }, [data, keyword]);

  const selectedIssue =
    filteredQueue.find((item) => item.id === selectedId) || filteredQueue[0];

  const displayGroups =
    groupMode === "priority"
      ? queueGroups(filteredQueue)
      : statusGroups(filteredQueue);

  const generateDraft = async () => {
    if (!selectedIssue) {
      message.warning("请先选择一个待复盘问题");
      return;
    }
    setDraftLoading(true);
    try {
      const result = await issueApi.retrospectiveDraft({ issueId: selectedIssue.id });
      if (!result.available) {
        message.error(result.error || "AI 复盘草稿生成失败");
        setDraft(result);
        return;
      }
      setDraft(result);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "AI 复盘草稿生成失败");
    } finally {
      setDraftLoading(false);
    }
  };

  if (loading || !data) {
    return (
      <div className="page retrospective-page">
        <Skeleton active />
      </div>
    );
  }

  const ai = data.aiSuggestion;

  return (
    <div className="page retrospective-page">
      <div className="retro-heading">
        <div>
          <h1>复盘沉淀</h1>
          <p>从根因到动作，把问题治理结果沉淀为组织能力</p>
        </div>
        <div className="retro-toolbar">
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索问题、负责人、标签"
            allowClear
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
          />
          <Button
            icon={<FilterOutlined />}
            onClick={() =>
              setGroupMode((value) => (value === "priority" ? "status" : "priority"))
            }
          >
            筛选
          </Button>
          <Select
            value={groupMode}
            onChange={setGroupMode}
            options={[
              { label: "按优先级分组", value: "priority" },
              { label: "按复盘状态分组", value: "status" },
            ]}
          />
          <Button
            icon={<ReloadOutlined />}
            loading={refreshing}
            onClick={() => void load(true)}
          >
            刷新
          </Button>
        </div>
      </div>

      <section className="retro-pipeline">
        {data.pipeline.steps.map((step, index) => {
          const Icon = pipelineIcons[index] || CheckCircleOutlined;
          return (
            <article className="retro-step" key={step.label}>
              <div className={index === 0 ? "retro-step-icon active" : "retro-step-icon"}>
                <Icon />
              </div>
              <div>
                <b>{step.label}</b>
                <span>{step.description}</span>
                <strong>{step.value}</strong>
              </div>
              {index < data.pipeline.steps.length - 1 && <ArrowRightOutlined />}
            </article>
          );
        })}
      </section>

      <section className="retro-layout">
        <article className="retro-panel retro-queue-panel">
          <header className="retro-panel-head">
            <div>
              <h2>本周待复盘队列</h2>
              <span>{filteredQueue.length} 条</span>
            </div>
            <small>规则计算</small>
          </header>

          <div className="retro-queue">
            {filteredQueue.length ? (
              displayGroups.map(([priority, items]) => (
                <div className="retro-queue-group" key={priority}>
                  <div className="retro-group-title">
                    <i className={`retro-dot ${priorityTone(priority)}`} />
                    <b>{priority}</b>
                    <span>
                      {groupMode === "status"
                        ? "复盘状态"
                        : priority === "P0"
                        ? "最高优先级"
                        : priority === "P1"
                          ? "高优先级"
                          : priority === "P2"
                            ? "中优先级"
                            : "低优先级"}{" "}
                      ({items.length})
                    </span>
                  </div>
                  {items.map((item) => (
                    <button
                      type="button"
                      className={
                        selectedIssue?.id === item.id
                          ? "retro-queue-row active"
                          : "retro-queue-row"
                      }
                      key={item.id}
                      onClick={() => setSelectedId(item.id)}
                    >
                      <Tag className={`priority-tag ${priorityTone(item.priority)}`}>
                        {item.priority}
                      </Tag>
                      <div className="retro-row-main">
                        <b>{item.title}</b>
                        <span>{item.reviewReason}</span>
                      </div>
                      <div className="retro-row-owner">
                        <span>{item.owner}</span>
                        <small>{item.deadline}</small>
                      </div>
                      <StatusTag status={item.status} size="compact" />
                      <Tag className={`retro-status ${retroTone(item.retrospectiveStatus)}`}>
                        {item.retrospectiveStatus}
                      </Tag>
                    </button>
                  ))}
                </div>
              ))
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无匹配的复盘问题" />
            )}
          </div>

          <button className="retro-link" type="button" onClick={() => navigate("/issues")}>
            查看全部问题 <ArrowRightOutlined />
          </button>
        </article>

        <div className="retro-center-stack">
          <article className="retro-panel">
            <header className="retro-panel-head">
              <div>
                <h2>根因聚类</h2>
                <span>{data.period}</span>
              </div>
              <button type="button">查看全部</button>
            </header>
            <div className="retro-clusters">
              {data.causeClusters.map((cluster) => (
                <article className="retro-cluster-card" key={cluster.name}>
                  <ClusterOutlined />
                  <b>{cluster.name}</b>
                  <strong>{cluster.count}</strong>
                  <span>占比 {cluster.share}%</span>
                  <small className={cluster.changePercent >= 0 ? "up" : "down"}>
                    {formatPercent(cluster.changePercent)}
                  </small>
                </article>
              ))}
            </div>
            {selectedIssue && (
              <div className="retro-selected-evidence">
                <b>{selectedIssue.title}</b>
                <span>{selectedIssue.rootCauseTag}</span>
                <p>{selectedIssue.impact}</p>
                <Button type="link" onClick={() => navigate(`/issues/${selectedIssue.id}`)}>
                  进入问题详情 <ArrowRightOutlined />
                </Button>
              </div>
            )}
          </article>

          <article className="retro-panel retro-action-panel">
            <header className="retro-panel-head">
              <div>
                <h2>行动闭环</h2>
                <span>预防动作落地</span>
              </div>
              <button type="button">查看全部</button>
            </header>
            <div className="retro-action-summary">
              <span>
                待落地 <b>{data.actionClosure.pending}</b>
              </span>
              <span>
                进行中 <b>{data.actionClosure.inProgress}</b>
              </span>
              <span>
                已完成 <b>{data.actionClosure.completed}</b>
              </span>
              <span>
                完成率 <b>{data.actionClosure.completionRate}%</b>
                <Progress
                  percent={data.actionClosure.completionRate}
                  showInfo={false}
                  strokeColor="#6c63ff"
                />
              </span>
            </div>
            <div className="retro-actions">
              {data.actionClosure.actions.map((action) => (
                <button
                  type="button"
                  className="retro-action-row"
                  key={`${action.sourceIssueNo}-${action.title}`}
                  onClick={() => navigate(`/issues/${action.sourceIssueId}`)}
                >
                  <ClockCircleOutlined />
                  <span>
                    <b>{action.title}</b>
                    <small>
                      {action.owner} · {action.deadline} · {action.sourceIssueNo}
                    </small>
                  </span>
                  <Progress
                    percent={action.progress}
                    showInfo={false}
                    strokeColor="#6c63ff"
                  />
                  <em>{action.progress}%</em>
                </button>
              ))}
            </div>
            <button className="retro-link" type="button">
              新增预防动作 <PlusCircleOutlined />
            </button>
          </article>
        </div>

        <aside className="retro-panel retro-ai-panel">
          <header className="retro-ai-title">
            <span>
              <RobotOutlined /> AI 复盘建议
            </span>
            <small>{aiLoading ? "AI 分析中" : ai.applied ? `${ai.generatedBy} · ${ai.model}` : "等待真实 AI"}</small>
          </header>

          {aiLoading ? (
            <div className="retro-ai-unavailable">
              <RobotOutlined />
              <b>AI 正在分析复盘数据</b>
              <p>页面数据已经加载完成，AI 建议会在右侧独立更新，不再阻塞整个复盘沉淀模块。</p>
            </div>
          ) : ai.applied ? (
            <div className="retro-ai-content">
              <section>
                <h3>建议优先复盘</h3>
                <p>{ai.summary}</p>
              </section>
              <section>
                <h3>关键证据</h3>
                {(ai.evidence || []).map((item) => (
                  <p className="retro-ai-check" key={item}>
                    <CheckCircleOutlined /> {item}
                  </p>
                ))}
              </section>
              <section>
                <h3>下一步建议</h3>
                <ol>
                  {(ai.nextActions || []).map((item) => (
                    <li key={item}>{item}</li>
                  ))}
                </ol>
              </section>
            </div>
          ) : (
            <div className="retro-ai-unavailable">
              <RobotOutlined />
              <b>AI 复盘建议未生成</b>
              <p>{ai.error || "需要后端大模型配置可用后，才会展示真实 AI 分析。"}</p>
            </div>
          )}

          <Button
            type="primary"
            icon={<ThunderboltOutlined />}
            loading={draftLoading}
            onClick={generateDraft}
          >
            生成复盘草稿
          </Button>
          <Button icon={<SendOutlined />} onClick={() => navigate("/ai-insights")}>
            进入 AI 对话
          </Button>
        </aside>
      </section>

      <Modal
        title="AI 复盘草稿"
        open={!!draft}
        onCancel={() => setDraft(undefined)}
        footer={[
          <Button key="close" onClick={() => setDraft(undefined)}>
            关闭
          </Button>,
          draft?.issueId ? (
            <Button
              key="detail"
              type="primary"
              onClick={() => navigate(`/issues/${draft.issueId}`)}
            >
              查看问题详情
            </Button>
          ) : null,
        ]}
        width={760}
      >
        {draft?.available ? (
          <div className="retro-draft">
            <h3>
              {draft.issueNo} · {draft.title}
            </h3>
            <section>
              <b>根因草稿</b>
              <p>{draft.rootCauseDraft || "当前证据不足，需要补充"}</p>
            </section>
            <section>
              <b>修复复盘</b>
              <p>{draft.fixReview || "当前证据不足，需要补充"}</p>
            </section>
            <section>
              <b>验证结论</b>
              <p>{draft.verificationConclusion || "当前证据不足，需要补充"}</p>
            </section>
            <section>
              <b>预防动作</b>
              <ul>{(draft.preventionActions || []).map((item) => <li key={item}>{item}</li>)}</ul>
            </section>
            <section>
              <b>可复用 Playbook</b>
              <ul>{(draft.reusePlaybook || []).map((item) => <li key={item}>{item}</li>)}</ul>
            </section>
          </div>
        ) : (
          <div className="retro-ai-unavailable modal-state">
            <RobotOutlined />
            <b>无法生成真实 AI 草稿</b>
            <p>{draft?.error}</p>
          </div>
        )}
      </Modal>
    </div>
  );
}
