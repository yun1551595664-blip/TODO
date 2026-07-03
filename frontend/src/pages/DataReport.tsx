import {
  ArrowRightOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  ReloadOutlined,
  SlidersOutlined,
  TableOutlined,
} from "@ant-design/icons";
import { Button, Empty, Progress, Skeleton, message } from "antd";
import dayjs from "dayjs";
import { useEffect, useMemo, useState, type CSSProperties } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { useLocation, useNavigate } from "react-router-dom";
import { issueApi } from "../api";
import StatusTag from "../components/StatusTag";
import type {
  Issue,
  ReportAnalysisData,
  ReportAnalysisTrendPoint,
  ReportData,
  ReportDimensionItem,
} from "../types";

type DataMode = "overview" | "analysis";
type SelectedDimension = {
  dimensionKey: string;
  dimensionLabel: string;
  item: ReportDimensionItem;
};

type IssueSummary = {
  total: number;
  overdue: number;
  reopened: number;
  highPriority: number;
  completed: number;
  slaRate: number;
  overdueRate: number;
  reopenedRate: number;
  averageHandleHours: number;
};

const trendColors = {
  newIssues: "#6c63ff",
  completed: "#22a06b",
  pending: "#9b8cff",
  overdue: "#ff8a1f",
};

const heatColors = ["#f7f6ff", "#ece9ff", "#ddd8ff", "#c7bfff"];

function formatNumber(value: number | string) {
  if (typeof value === "string") return value;
  return new Intl.NumberFormat("zh-CN").format(value);
}

function formatHours(hours: number) {
  if (!hours) return "0 小时";
  return hours >= 48 ? `${(hours / 24).toFixed(1)} 天` : `${hours.toFixed(1)} 小时`;
}

function isCompleted(issue: Issue) {
  return issue.status === "已完成";
}

function isHighPriority(issue: Issue) {
  return issue.priority === "P0" || issue.priority === "P1";
}

function isOverdue(issue: Issue) {
  return (
    !!issue.expectedFinishTime &&
    !issue.actualFinishTime &&
    dayjs(issue.expectedFinishTime).isBefore(dayjs())
  );
}

function issueField(issue: Issue, key: string) {
  if (key === "businessScene") return issue.businessScene || "未分配";
  if (key === "issueType") return issue.issueType || "未分配";
  if (key === "responsibleDepartment") return issue.responsibleDepartment || "未分配";
  if (key === "source") return issue.source || "未分配";
  if (key === "impactScope") return issue.impactScope || "未分配";
  return "未分配";
}

function buildIssueSummary(issues: Issue[]): IssueSummary {
  const total = issues.length;
  const overdue = issues.filter(isOverdue).length;
  const reopened = issues.filter((issue) => issue.reopened).length;
  const highPriority = issues.filter((issue) => isHighPriority(issue) && !isCompleted(issue)).length;
  const completed = issues.filter(isCompleted).length;
  const durations = issues
    .filter((issue) => issue.actualFinishTime && issue.createdAt)
    .map((issue) => dayjs(issue.actualFinishTime).diff(dayjs(issue.createdAt), "hour", true));
  const averageHandleHours = durations.length
    ? durations.reduce((sum, value) => sum + value, 0) / durations.length
    : 0;
  const overdueRate = total ? (overdue / total) * 100 : 0;
  const reopenedRate = total ? (reopened / total) * 100 : 0;
  return {
    total,
    overdue,
    reopened,
    highPriority,
    completed,
    slaRate: total ? Math.max(0, 100 - overdueRate) : 100,
    overdueRate,
    reopenedRate,
    averageHandleHours,
  };
}

function buildTrendFromIssues(issues: Issue[]): ReportAnalysisTrendPoint[] {
  const start = dayjs().subtract(29, "day").startOf("day");
  return Array.from({ length: 30 }, (_, index) => {
    const date = start.add(index, "day");
    const dateText = date.format("YYYY-MM-DD");
    const newIssues = issues.filter((issue) => dayjs(issue.createdAt).isSame(date, "day")).length;
    const completed = issues.filter((issue) =>
      issue.actualFinishTime ? dayjs(issue.actualFinishTime).isSame(date, "day") : false,
    ).length;
    const pending = issues.filter((issue) => {
      const created = dayjs(issue.createdAt);
      if (created.isAfter(date.endOf("day"))) return false;
      return !issue.actualFinishTime || dayjs(issue.actualFinishTime).isAfter(date.endOf("day"));
    }).length;
    const overdue = issues.filter((issue) => {
      if (!issue.expectedFinishTime) return false;
      if (dayjs(issue.expectedFinishTime).isAfter(date.endOf("day"))) return false;
      return !issue.actualFinishTime || dayjs(issue.actualFinishTime).isAfter(date.endOf("day"));
    }).length;
    return { date: dateText, newIssues, completed, pending, overdue };
  });
}

function selectedIssues(issues: Issue[], selected?: SelectedDimension) {
  if (!selected) return issues;
  return issues.filter((issue) => issueField(issue, selected.dimensionKey) === selected.item.name);
}

function heatClass(value: number) {
  if (value >= 45) return 3;
  if (value >= 25) return 2;
  if (value > 0) return 1;
  return 0;
}

function riskClass(level: string) {
  if (level === "高") return "danger";
  if (level === "中") return "warning";
  return "safe";
}

function MiniSparkline({ data }: { data: ReportAnalysisTrendPoint[] }) {
  return (
    <ResponsiveContainer width={86} height={26}>
      <LineChart data={data.slice(-10)}>
        <Line
          type="monotone"
          dataKey="pending"
          stroke="#9b8cff"
          strokeWidth={1.6}
          dot={false}
        />
      </LineChart>
    </ResponsiveContainer>
  );
}

export default function DataReport() {
  const [report, setReport] = useState<ReportData>();
  const [analysis, setAnalysis] = useState<ReportAnalysisData>();
  const [refreshing, setRefreshing] = useState(false);
  const [selected, setSelected] = useState<SelectedDimension>();
  const navigate = useNavigate();
  const location = useLocation();
  const mode: DataMode = location.pathname.endsWith("/analysis") ? "analysis" : "overview";

  const load = async () => {
    setRefreshing(true);
    try {
      const [overview, analysisData] = await Promise.all([
        issueApi.report(),
        issueApi.reportAnalysis(),
      ]);
      setReport(overview);
      setAnalysis(analysisData);
      if (!selected && analysisData.dimensions.length) {
        const firstHighPriority =
          analysisData.dimensions
            .find((dimension) => dimension.key === "issueType")
            ?.items.find((item) => item.riskLevel !== "低") ||
          analysisData.dimensions[0].items[0];
        if (firstHighPriority) {
          setSelected({
            dimensionKey: analysisData.dimensions.find((dimension) =>
              dimension.items.some((item) => item.key === firstHighPriority.key),
            )?.key || analysisData.dimensions[0].key,
            dimensionLabel:
              analysisData.dimensions.find((dimension) =>
                dimension.items.some((item) => item.key === firstHighPriority.key),
              )?.label || analysisData.dimensions[0].label,
            item: firstHighPriority,
          });
        }
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : "数据加载失败");
    } finally {
      setRefreshing(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const filteredIssues = useMemo(
    () => selectedIssues(analysis?.issues || [], selected),
    [analysis?.issues, selected],
  );
  const filteredSummary = useMemo(() => buildIssueSummary(filteredIssues), [filteredIssues]);
  const filteredTrend = useMemo(() => buildTrendFromIssues(filteredIssues), [filteredIssues]);

  if (!report || !analysis)
    return (
      <div className="page data-report-page">
        <Skeleton active paragraph={{ rows: 10 }} />
      </div>
    );

  return (
    <div className="page data-report-page data-module-page">
      <div className="list-heading report-heading data-module-heading">
        <div>
          <h1>数据分析</h1>
          <p>
            {mode === "overview"
              ? "从趋势、结构与效率剖面识别治理瓶颈"
              : "从聚合指标一路钻取到具体问题明细"}
          </p>
        </div>
        <div className="report-actions">
          <span>近 30 天</span>
          <span>全部部门</span>
          <span>全部类型</span>
          <Button icon={<DownloadOutlined />}>导出</Button>
          <Button icon={<ReloadOutlined />} loading={refreshing} onClick={load}>
            刷新
          </Button>
        </div>
      </div>

      <div className="data-tabs" role="tablist">
        <button
          type="button"
          className={mode === "overview" ? "active" : ""}
          onClick={() => navigate("/data")}
        >
          数据总览
        </button>
        <button
          type="button"
          className={mode === "analysis" ? "active" : ""}
          onClick={() => navigate("/data/analysis")}
        >
          数据分析
        </button>
      </div>

      {mode === "overview" ? (
        <DataOverview report={report} analysis={analysis} />
      ) : (
        <DataAnalysis
          analysis={analysis}
          selected={selected}
          setSelected={setSelected}
          filteredIssues={filteredIssues}
          filteredSummary={filteredSummary}
          filteredTrend={filteredTrend}
        />
      )}
    </div>
  );
}

function DataOverview({
  report,
  analysis,
}: {
  report: ReportData;
  analysis: ReportAnalysisData;
}) {
  const summary = analysis.summary;
  const issueTypeDimension = analysis.dimensions.find((item) => item.key === "issueType");
  const departmentDimension = analysis.dimensions.find(
    (item) => item.key === "responsibleDepartment",
  );
  const efficiencyMax = Math.max(1, ...analysis.efficiencyBuckets.map((item) => item.total));
  const kpis = [
    { label: "问题总数", value: summary.total, delta: "当前可见数据" },
    { label: "已完成", value: summary.completed, delta: "闭环效率样本" },
    { label: "待处理", value: summary.total - summary.completed, delta: "当前库存" },
    { label: "超期率", value: `${summary.overdueRate.toFixed(1)}%`, delta: `${summary.overdue} 个超期` },
    { label: "复发率", value: `${summary.reopenedRate.toFixed(1)}%`, delta: `${summary.reopened} 个复发` },
    { label: "平均处理", value: formatHours(summary.averageHandleHours), delta: "已完成问题" },
  ];

  return (
    <>
      <section className="data-overview-hero">
        <article className="governance-score-card">
          <div
            className="score-ring"
            style={{ "--score": `${summary.governanceScore}%` } as CSSProperties}
          >
            <b>{summary.governanceScore}</b>
            <span>治理指数</span>
          </div>
          <div className="score-breakdown">
            <h2>治理剖面</h2>
            <p>综合闭环效率、超期控制、复发控制和高优先级响应计算。</p>
            {summary.subScores.map((score) => (
              <div className="score-row" key={score.label}>
                <span>{score.label}</span>
                <Progress percent={score.value} showInfo={false} strokeColor="#6c63ff" />
                <b>{score.value}</b>
                <small>{score.delta}</small>
              </div>
            ))}
          </div>
        </article>

        <article className="report-card key-change-card">
          <header className="report-card-header compact">
            <div>
              <h2>关键变化</h2>
              <p>基于当前可见问题数据自动计算</p>
            </div>
          </header>
          <div className="key-change-list">
            {analysis.keyChanges.map((change, index) => (
              <div className={`key-change ${change.tone}`} key={change.title}>
                <b>{String(index + 1).padStart(2, "0")}</b>
                <span>
                  <strong>{change.title}</strong>
                  <small>{change.description}</small>
                </span>
              </div>
            ))}
          </div>
        </article>
      </section>

      <section className="data-kpi-strip">
        {kpis.map((metric) => (
          <article key={metric.label}>
            <span>{metric.label}</span>
            <b>{formatNumber(metric.value)}</b>
            <small>{metric.delta}</small>
            <MiniSparkline data={analysis.trend} />
          </article>
        ))}
      </section>

      <section className="report-card data-chart-card">
        <header className="report-card-header">
          <div>
            <h2>趋势分解</h2>
            <p>问题流入、闭环、库存和超期风险的 30 天变化</p>
          </div>
          <span>近 30 天</span>
        </header>
        <ResponsiveContainer width="100%" height={300}>
          <AreaChart data={analysis.trend}>
            <defs>
              <linearGradient id="pendingFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#6c63ff" stopOpacity={0.18} />
                <stop offset="95%" stopColor="#6c63ff" stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid stroke="#eeeef3" vertical={false} />
            <XAxis dataKey="date" tickFormatter={(value) => dayjs(value).format("M.D")} />
            <YAxis allowDecimals={false} />
            <Tooltip labelFormatter={(value) => dayjs(String(value)).format("YYYY-MM-DD")} />
            <Area
              type="monotone"
              dataKey="pending"
              name="待处理库存"
              stroke={trendColors.pending}
              fill="url(#pendingFill)"
              strokeWidth={2}
            />
            <Line
              type="monotone"
              dataKey="newIssues"
              name="新增"
              stroke={trendColors.newIssues}
              strokeWidth={2}
              dot={false}
            />
            <Line
              type="monotone"
              dataKey="completed"
              name="完成"
              stroke={trendColors.completed}
              strokeWidth={2}
              dot={false}
            />
            <Line
              type="monotone"
              dataKey="overdue"
              name="超期"
              stroke={trendColors.overdue}
              strokeWidth={2}
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      </section>

      <section className="data-overview-grid">
        <article className="report-card">
          <header className="report-card-header compact">
            <div>
              <h2>结构剖面</h2>
              <p>问题类型与责任部门的集中度</p>
            </div>
          </header>
          <div className="structure-split">
            <DimensionBars title="问题类型" items={issueTypeDimension?.items || []} />
            <DimensionBars title="责任部门" items={departmentDimension?.items || []} />
          </div>
        </article>
        <article className="report-card">
          <header className="report-card-header compact">
            <div>
              <h2>效率剖面</h2>
              <p>已完成问题按处理时长分布</p>
            </div>
          </header>
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={analysis.efficiencyBuckets}>
              <CartesianGrid stroke="#eeeef3" vertical={false} />
              <XAxis dataKey="label" />
              <YAxis allowDecimals={false} />
              <Tooltip />
              <Bar dataKey="normal" name="普通问题" stackId="a" fill="#c7c2ff" radius={[6, 6, 0, 0]} />
              <Bar dataKey="highPriority" name="P0/P1" stackId="a" fill="#ff8a1f" radius={[6, 6, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
          <div className="dataset-links">
            <span>问题明细 {summary.total}</span>
            <span>超期清单 {summary.overdue}</span>
            <span>复发清单 {summary.reopened}</span>
          </div>
        </article>
      </section>

      <section className="report-card suggestion-section">
        <header className="report-card-header compact">
          <div>
            <h2>管理建议</h2>
            <p>基于当前类型、责任分布和处理效率生成，不替代复盘沉淀</p>
          </div>
        </header>
        <div className="data-suggestion-row">
          {report.suggestions.map((suggestion) => (
            <article key={suggestion.title}>
              <b>{suggestion.title}</b>
              <p>{suggestion.description}</p>
              <span>{suggestion.owner}</span>
            </article>
          ))}
        </div>
      </section>
    </>
  );
}

function DataAnalysis({
  analysis,
  selected,
  setSelected,
  filteredIssues,
  filteredSummary,
  filteredTrend,
}: {
  analysis: ReportAnalysisData;
  selected?: SelectedDimension;
  setSelected: (value?: SelectedDimension) => void;
  filteredIssues: Issue[];
  filteredSummary: IssueSummary;
  filteredTrend: ReportAnalysisTrendPoint[];
}) {
  const navigate = useNavigate();
  const baseSummary = analysis.summary;
  const diffCards = [
    {
      label: "超期率差异",
      value: `${(filteredSummary.overdueRate - baseSummary.overdueRate).toFixed(1)}pp`,
      tone: filteredSummary.overdueRate > baseSummary.overdueRate ? "danger" : "safe",
    },
    {
      label: "复发率差异",
      value: `${(filteredSummary.reopenedRate - baseSummary.reopenedRate).toFixed(1)}pp`,
      tone: filteredSummary.reopenedRate > baseSummary.reopenedRate ? "danger" : "safe",
    },
    {
      label: "平均处理差异",
      value: formatHours(Math.abs(filteredSummary.averageHandleHours - baseSummary.averageHandleHours)),
      tone:
        filteredSummary.averageHandleHours > baseSummary.averageHandleHours
          ? "warning"
          : "safe",
    },
  ];
  const kpis = [
    { label: "总问题", value: baseSummary.total, note: "全部可见" },
    { label: "当前筛选", value: filteredSummary.total, note: selected?.item.name || "全部问题" },
    { label: "超期", value: filteredSummary.overdue, note: `${filteredSummary.overdueRate.toFixed(1)}%` },
    { label: "复发", value: filteredSummary.reopened, note: `${filteredSummary.reopenedRate.toFixed(1)}%` },
    { label: "P0/P1", value: filteredSummary.highPriority, note: "未完成高优先级" },
    { label: "SLA 达成率", value: `${filteredSummary.slaRate.toFixed(1)}%`, note: "当前筛选" },
  ];

  return (
    <>
      <section className="analysis-path">
        <div>
          <span>分析路径</span>
          <b>全部问题</b>
          {selected && (
            <>
              <ArrowRightOutlined />
              <b>{selected.dimensionLabel}</b>
              <ArrowRightOutlined />
              <strong>{selected.item.name}</strong>
            </>
          )}
        </div>
        <Button size="small" onClick={() => setSelected(undefined)}>
          重置筛选
        </Button>
      </section>

      <section className="data-kpi-strip compact">
        {kpis.map((metric) => (
          <article key={metric.label}>
            <span>{metric.label}</span>
            <b>{formatNumber(metric.value)}</b>
            <small>{metric.note}</small>
          </article>
        ))}
      </section>

      <section className="analysis-workspace">
        <aside className="dimension-tree">
          <header>
            <SlidersOutlined />
            <div>
              <h2>维度树</h2>
              <p>点击任一维度进入钻取分析</p>
            </div>
          </header>
          {analysis.dimensions.map((dimension) => {
            const max = Math.max(1, ...dimension.items.map((item) => item.value));
            return (
              <div className="dimension-group" key={dimension.key}>
                <h3>{dimension.label}</h3>
                {dimension.items.slice(0, 6).map((item) => {
                  const active =
                    selected?.dimensionKey === dimension.key &&
                    selected.item.name === item.name;
                  return (
                    <button
                      type="button"
                      className={active ? "dimension-item active" : "dimension-item"}
                      key={`${dimension.key}-${item.name}`}
                      onClick={() =>
                        setSelected({
                          dimensionKey: dimension.key,
                          dimensionLabel: dimension.label,
                          item,
                        })
                      }
                    >
                      <span>
                        <b>{item.name}</b>
                        <small>{item.share.toFixed(1)}% · 超期 {item.overdueCount}</small>
                      </span>
                      <i>
                        <em style={{ width: `${(item.value / max) * 100}%` }} />
                      </i>
                      <strong>{item.value}</strong>
                      <mark className={riskClass(item.riskLevel)}>{item.riskLevel}</mark>
                    </button>
                  );
                })}
              </div>
            );
          })}
        </aside>

        <main className="analysis-main-panel">
          <article className="report-card selected-trend-card">
            <header className="report-card-header compact">
              <div>
                <h2>趋势与明细</h2>
                <p>{selected ? `当前筛选：${selected.item.name}` : "当前筛选：全部问题"}</p>
              </div>
              <span>{filteredIssues.length} 条问题</span>
            </header>
            <ResponsiveContainer width="100%" height={250}>
              <AreaChart data={filteredTrend}>
                <defs>
                  <linearGradient id="selectedPendingFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#6c63ff" stopOpacity={0.18} />
                    <stop offset="95%" stopColor="#6c63ff" stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#eeeef3" vertical={false} />
                <XAxis dataKey="date" tickFormatter={(value) => dayjs(value).format("M.D")} />
                <YAxis allowDecimals={false} />
                <Tooltip labelFormatter={(value) => dayjs(String(value)).format("YYYY-MM-DD")} />
                <Area
                  type="monotone"
                  dataKey="pending"
                  name="待处理库存"
                  stroke={trendColors.pending}
                  fill="url(#selectedPendingFill)"
                  strokeWidth={2}
                />
                <Line
                  type="monotone"
                  dataKey="newIssues"
                  name="新增"
                  stroke={trendColors.newIssues}
                  strokeWidth={2}
                  dot={false}
                />
                <Line
                  type="monotone"
                  dataKey="completed"
                  name="完成"
                  stroke={trendColors.completed}
                  strokeWidth={2}
                  dot={false}
                />
                <Line
                  type="monotone"
                  dataKey="overdue"
                  name="超期"
                  stroke={trendColors.overdue}
                  strokeWidth={2}
                  dot={false}
                />
              </AreaChart>
            </ResponsiveContainer>
          </article>

          <div className="diff-card-row">
            {diffCards.map((card) => (
              <article className={`diff-card ${card.tone}`} key={card.label}>
                <span>{card.label}</span>
                <b>{card.value}</b>
                <small>较全量数据</small>
              </article>
            ))}
          </div>

          <article className="report-card detail-table-card">
            <header className="report-card-header compact">
              <div>
                <h2>问题明细</h2>
                <p>用于从分析结果回到具体问题</p>
              </div>
              <TableOutlined />
            </header>
            {filteredIssues.length ? (
              <div className="analysis-table">
                <div className="analysis-table-head">
                  <span>编号</span>
                  <span>标题</span>
                  <span>优先级</span>
                  <span>状态</span>
                  <span>部门</span>
                  <span>负责人</span>
                  <span>风险</span>
                </div>
                {filteredIssues.slice(0, 12).map((issue) => (
                  <button
                    type="button"
                    className="analysis-table-row"
                    key={issue.id}
                    onClick={() => navigate(`/issues/${issue.id}`)}
                  >
                    <span>{issue.issueNo}</span>
                    <strong>{issue.title}</strong>
                    <span className={`priority priority-${issue.priority?.toLowerCase()}`}>
                      {issue.priority}
                    </span>
                    <StatusTag status={issue.status} />
                    <span>{issue.responsibleDepartment || "-"}</span>
                    <span>{issue.responsiblePerson || "-"}</span>
                    <span>{isOverdue(issue) ? "超期" : issue.reopened ? "复发" : "正常"}</span>
                  </button>
                ))}
              </div>
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无匹配问题" />
            )}
          </article>
        </main>

        <aside className="data-inspector">
          <header>
            <DatabaseOutlined />
            <div>
              <h2>数据口径</h2>
              <p>当前页面的核心计算规则</p>
            </div>
          </header>
          <ul>
            {analysis.metricDefinitions.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
          <div className="inspector-foot">
            <span>数据更新</span>
            <b>{dayjs(analysis.updatedAt).format("YYYY-MM-DD HH:mm")}</b>
          </div>
        </aside>
      </section>
    </>
  );
}

function DimensionBars({
  title,
  items,
}: {
  title: string;
  items: ReportDimensionItem[];
}) {
  const max = Math.max(1, ...items.map((item) => item.value));
  return (
    <div className="dimension-bars">
      <h3>{title}</h3>
      {items.slice(0, 6).map((item) => (
        <div className="dimension-bar" key={item.name}>
          <span>{item.name}</span>
          <i>
            <em
              style={{
                width: `${(item.value / max) * 100}%`,
                background: heatColors[heatClass(item.overdueRate)],
              }}
            />
          </i>
          <b>{item.value}</b>
          <small>{item.overdueRate.toFixed(1)}%</small>
        </div>
      ))}
    </div>
  );
}
