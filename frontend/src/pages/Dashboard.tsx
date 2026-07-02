import {
  ArrowRightOutlined,
  CalendarOutlined,
  ReloadOutlined,
  RobotOutlined,
  StarFilled,
} from "@ant-design/icons";
import { Button, Empty, Select, Skeleton, Spin, message } from "antd";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { issueApi } from "../api";
import StatusTag from "../components/StatusTag";
import type { DashboardData, Issue, TrendPoint } from "../types";

const trendRangeOptions = [
  { value: "8w", label: "近 8 周" },
  { value: "12w", label: "近 12 周" },
  { value: "30d", label: "近 30 天" },
];

const formatTrendTick = (value: unknown) => {
  const text = String(value ?? "");
  const match = text.match(/(?:(\d{4})-)?(\d{1,2})-(\d{1,2})/);
  if (!match) return text;
  return `${Number(match[2])}.${Number(match[3])}`;
};

const buildTrendChartData = (trend: TrendPoint[]) => {
  return trend.map((item) => ({
    ...item,
    新增趋势: item.新增,
    完成趋势: item.完成,
    待处理趋势: item.待处理,
  }));
};

const formatDashboardDateTime = (date: Date) => {
  const weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
  const dateText = `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${weekdays[date.getDay()]}`;
  const timeText = date.toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
  return { dateText, timeText };
};

const greetingByTime = (date: Date) => {
  const hour = date.getHours();
  if (hour < 6) return "夜深了";
  if (hour < 12) return "早上好";
  if (hour < 14) return "中午好";
  if (hour < 18) return "下午好";
  return "晚上好";
};

const parseDateOrNow = (value?: string) => {
  if (!value) return new Date();
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? new Date() : date;
};

const renderLastPointDot =
  (lastDate: string, color: string, radius = 4) =>
  (props: any) => {
    if (props.payload?.date !== lastDate) return null;
    return (
      <circle
        cx={props.cx}
        cy={props.cy}
        r={radius}
        fill={color}
        stroke="#fff"
        strokeWidth={2}
      />
    );
  };

function TrendTooltip({ active, payload, label }: any) {
  if (!active || !payload?.length) return null;
  const raw = payload[0]?.payload;
  return (
    <div className="trend-tooltip">
      <b>{formatTrendTick(label)}</b>
      <span>新增问题：{raw?.新增 ?? 0}</span>
      <span>已完成问题：{raw?.完成 ?? 0}</span>
      <span>待处理问题：{raw?.待处理 ?? 0}</span>
    </div>
  );
}

function TrendXAxisTick({ x, y, payload, lastDate }: any) {
  const isLatest = payload?.value === lastDate;
  return (
    <text
      x={x}
      y={y + 14}
      textAnchor="middle"
      fill={isLatest ? "#5f55ff" : "#6e6e73"}
      fontSize={12}
      fontWeight={isLatest ? 650 : 400}
    >
      {formatTrendTick(payload?.value)}
    </text>
  );
}

function DashboardAiEntry({
  data,
  issues,
  onEnter,
}: {
  data: DashboardData;
  issues: Issue[];
  onEnter: () => void;
}) {
  const urgentIssue = issues[0];
  const highPriorityCount = issues.filter(
    (issue) => issue.priority === "P0" || issue.priority === "P1",
  ).length;
  const activeCount = data.pending + data.processing + data.verifying;
  const riskLevel =
    data.overdue > 0 || data.reopened > 0 || highPriorityCount > 0 || data.pending >= 3
      ? "高风险"
      : activeCount > 0
        ? "中风险"
        : "低风险";

  return (
    <section className="dashboard-ai-entry">
      <div className="dashboard-ai-entry-head">
        <span>
          <RobotOutlined /> AI 智能洞察
        </span>
        <b>{riskLevel}</b>
      </div>
      <div className="dashboard-ai-entry-body">
        <div>
          <small>当前研判</small>
          <p>
            当前仍有 <b>{activeCount}</b> 个问题需要推进，其中{" "}
            <b>{highPriorityCount}</b> 个来自首页关注列表的 P0/P1 问题；
            另有 <b>{data.overdue}</b> 个超期、<b>{data.reopened}</b> 个复发。
          </p>
        </div>
        <div>
          <small>建议优先关注</small>
          <p>
            {urgentIssue
              ? `${urgentIssue.title} · ${urgentIssue.responsibleDepartment || urgentIssue.businessScene || "待确认责任归属"}`
              : "当前暂无需要优先处理的问题。"}
          </p>
        </div>
      </div>
      <Button type="primary" onClick={onEnter}>
        进入 AI 洞察 <ArrowRightOutlined />
      </Button>
    </section>
  );
}

export default function Dashboard() {
  const [data, setData] = useState<DashboardData>();
  const [trend, setTrend] = useState<TrendPoint[]>([]);
  const [issues, setIssues] = useState<Issue[]>([]);
  const [pageLoading, setPageLoading] = useState(true);
  const [trendLoading, setTrendLoading] = useState(false);
  const [pageError, setPageError] = useState("");
  const [trendError, setTrendError] = useState("");
  const [trendRange, setTrendRange] = useState("8w");
  const [now, setNow] = useState(() => new Date());
  const [lastUpdatedAt, setLastUpdatedAt] = useState(() => new Date());
  const nav = useNavigate();

  const loadTrend = async (range = trendRange) => {
    setTrendLoading(true);
    setTrendError("");
    try {
      const nextTrend = await issueApi.trend(range);
      setTrend(nextTrend);
    } catch (error) {
      setTrend([]);
      const errorMessage =
        error instanceof Error ? error.message : "趋势数据加载失败";
      setTrendError(errorMessage);
      message.error(errorMessage);
    } finally {
      setTrendLoading(false);
    }
  };

  const loadPage = async () => {
    setPageLoading(true);
    setPageError("");
    setTrendError("");
    try {
      const [statistics, issuePage, trendData] = await Promise.all([
        issueApi.dashboard(),
        issueApi.list({ page: 0, size: 6 }),
        issueApi.trend(trendRange),
      ]);
      setData(statistics);
      setIssues(issuePage.content);
      setTrend(trendData);
      const current = new Date();
      setNow(current);
      setLastUpdatedAt(parseDateOrNow(statistics.updatedAt));
    } catch (error) {
      const current = new Date();
      const errorMessage =
        error instanceof Error ? error.message : "首页数据加载失败";
      setData(undefined);
      setTrend([]);
      setIssues([]);
      setNow(current);
      setLastUpdatedAt(current);
      setPageError(errorMessage);
      message.error(errorMessage);
    } finally {
      setPageLoading(false);
    }
  };

  const handleTrendRangeChange = (range: string) => {
    setTrendRange(range);
    loadTrend(range);
  };

  useEffect(() => {
    loadPage();
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  if (pageLoading) {
    return (
      <div className="page">
        <Skeleton active />
      </div>
    );
  }

  if (!data) {
    return (
      <div className="page dashboard">
        <div className="page-heading">
          <div>
            <h1>
              {greetingByTime(now)}，照远 <StarFilled className="sparkle" />
            </h1>
            <p>产品与业务问题进度管理 · 全局概览</p>
          </div>
          <div className="data-time">
            <ReloadOutlined onClick={loadPage} />
          </div>
        </div>
        <div className="empty-state-panel">
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={pageError || "首页数据暂不可用"}
          />
          <Button type="primary" onClick={loadPage}>
            重新加载
          </Button>
        </div>
      </div>
    );
  }

  const d = data;
  const metrics = [
    { label: "问题总数", value: d.total, note: "全部问题" },
    { label: "待处理", value: d.pending, note: "等待分派" },
    { label: "处理中", value: d.processing, note: "正在推进" },
    { label: "待验证", value: d.verifying, note: "等待确认" },
    { label: "已完成", value: d.completed, note: "验证闭环" },
    { label: "已复发", value: d.reopened, note: "需要复盘" },
    { label: "超期问题", value: d.overdue, note: "需要升级" },
    { label: "本月新增", value: d.monthlyNew, note: "本月创建" },
    { label: "本月完成", value: d.monthlyCompleted, note: "本月闭环" },
  ];
  const trendChartData = buildTrendChartData(trend);
  const lastTrendDate = trendChartData.at(-1)?.date;
  const trendTickInterval = trendRange === "30d" ? 4 : trendRange === "12w" ? 1 : 0;
  const { dateText, timeText } = formatDashboardDateTime(lastUpdatedAt);
  const greeting = greetingByTime(now);

  return (
    <div className="page dashboard">
      <div className="page-heading">
        <div>
          <h1>
            {greeting}，照远 <StarFilled className="sparkle" />
          </h1>
          <p>产品与业务问题进度管理 · 全局概览</p>
          <div className="summary">
            本月新增 <b>{d.monthlyNew}</b> 个问题，已完成{" "}
            <strong>{d.monthlyCompleted}</strong> 个，当前有{" "}
            <b>{d.processing}</b> 个问题正在推进。
          </div>
        </div>
        <div className="data-time">
          <CalendarOutlined /> {dateText} <i /> 数据更新于 {timeText}{" "}
          <ReloadOutlined onClick={loadPage} />
        </div>
      </div>

      <section className="metric-strip metric-strip-nine">
        {metrics.map((metric) => (
          <div className="metric" key={metric.label}>
            <span>{metric.label}</span>
            <b>{metric.value}</b>
            <small>{metric.note}</small>
          </div>
        ))}
      </section>

      <section className="dashboard-grid">
        <div className="chart-section">
          <div className="section-title">
            <h2>问题趋势</h2>
            <Select
              className="trend-range-select"
              variant="borderless"
              size="small"
              value={trendRange}
              options={trendRangeOptions}
              onChange={handleTrendRangeChange}
              popupMatchSelectWidth={false}
              aria-label="选择问题趋势时间范围"
            />
          </div>
          <div className="legend">
            <span className="legend-item">
              <i className="dot primary" />
              新增问题
            </span>
            <span className="legend-item">
              <i className="line-dashed" />
              已完成问题
            </span>
            <span className="legend-item">
              <i className="area-swatch" />
              待处理问题
            </span>
          </div>
          <Spin spinning={trendLoading} size="small">
            {trendChartData.length ? (
              <ResponsiveContainer width="100%" height={190}>
                <ComposedChart
                  data={trendChartData}
                  margin={{ top: 14, right: 12, bottom: 0, left: -18 }}
                >
                  <defs>
                    <linearGradient
                      id="trendNewGradient"
                      x1="0"
                      x2="0"
                      y1="0"
                      y2="1"
                    >
                      <stop offset="0%" stopColor="#6c63ff" stopOpacity={0.24} />
                      <stop offset="72%" stopColor="#6c63ff" stopOpacity={0.07} />
                      <stop offset="100%" stopColor="#6c63ff" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient
                      id="trendPendingGradient"
                      x1="0"
                      x2="0"
                      y1="0"
                      y2="1"
                    >
                      <stop offset="0%" stopColor="#c9c5ff" stopOpacity={0.24} />
                      <stop offset="100%" stopColor="#c9c5ff" stopOpacity={0.04} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke="#f0f0f4" vertical={false} />
                  <XAxis
                    dataKey="date"
                    axisLine={false}
                    tickLine={false}
                    tickFormatter={formatTrendTick}
                    tickMargin={10}
                    interval={trendTickInterval}
                    minTickGap={8}
                    tick={(props) => (
                      <TrendXAxisTick {...props} lastDate={lastTrendDate} />
                    )}
                  />
                  <YAxis
                    axisLine={false}
                    tickLine={false}
                    allowDecimals={false}
                    domain={[
                      0,
                      (dataMax: number) => Math.max(5, Math.ceil(dataMax * 1.2)),
                    ]}
                    tick={{ fill: "#6e6e73", fontSize: 12 }}
                    tickMargin={8}
                  />
                  <Tooltip content={<TrendTooltip />} cursor={false} />
                  {lastTrendDate && (
                    <ReferenceLine
                      x={lastTrendDate}
                      stroke="#d7d5e5"
                      strokeDasharray="3 3"
                    />
                  )}
                  <Area
                    type="monotone"
                    dataKey="待处理趋势"
                    name="待处理问题"
                    stroke="none"
                    fill="url(#trendPendingGradient)"
                    isAnimationActive={false}
                  />
                  <Line
                    type="monotone"
                    dataKey="完成趋势"
                    name="已完成问题"
                    stroke="#938aff"
                    fill="transparent"
                    strokeDasharray="5 5"
                    strokeWidth={2.8}
                    strokeLinecap="round"
                    dot={
                      lastTrendDate
                        ? renderLastPointDot(lastTrendDate, "#938aff", 3.8)
                        : false
                    }
                    activeDot={{ r: 4, stroke: "#fff", strokeWidth: 2 }}
                    isAnimationActive={false}
                  />
                  <Line
                    type="monotone"
                    dataKey="新增趋势"
                    name="新增问题"
                    stroke="#5f55ff"
                    strokeWidth={2.6}
                    strokeLinecap="round"
                    dot={
                      lastTrendDate
                        ? renderLastPointDot(lastTrendDate, "#5f55ff", 4.4)
                        : false
                    }
                    activeDot={{ r: 5, stroke: "#fff", strokeWidth: 2 }}
                    isAnimationActive={false}
                  />
                </ComposedChart>
              </ResponsiveContainer>
            ) : (
              <div className="chart-empty">
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={trendError || "暂无趋势数据"}
                />
              </div>
            )}
          </Spin>
        </div>

        <div className="attention">
          <div className="section-title">
            <h2>需要关注的问题 ({issues.length || 0})</h2>
            <Button type="link" onClick={() => nav("/issues")}>
              查看全部 <ArrowRightOutlined />
            </Button>
          </div>
          <div className="attention-head">
            <span>优先级</span>
            <span>问题标题</span>
            <span>所属业务</span>
            <span>当前状态</span>
            <span>持续时长</span>
          </div>
          {issues.length ? (
            issues.map((issue) => (
              <div
                className="attention-row"
                key={issue.id}
                onClick={() => nav(`/issues/${issue.id}`)}
              >
                <span>
                  <i className={`priority ${issue.priority}`} />
                  {issue.priority}
                </span>
                <b>{issue.title}</b>
                <span>{issue.businessScene || "-"}</span>
                <StatusTag status={issue.status} size="compact" />
                <span>
                  {Math.max(
                    1,
                    Math.floor(
                      (Date.now() - new Date(issue.createdAt).getTime()) /
                        86400000,
                    ),
                  )}
                  天
                </span>
              </div>
            ))
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
          )}
        </div>
      </section>

      <DashboardAiEntry data={d} issues={issues} onEnter={() => nav("/ai-insights")} />
    </div>
  );
}
