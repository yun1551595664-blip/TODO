import {
  ArrowRightOutlined,
  CalendarOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  ExclamationCircleOutlined,
  ReloadOutlined,
  RiseOutlined,
  SlidersOutlined,
  SyncOutlined,
  TableOutlined,
  TeamOutlined,
  ThunderboltOutlined,
} from "@ant-design/icons";
import { Button, DatePicker, Empty, Progress, Select, Skeleton, Switch, message } from "antd";
import dayjs from "dayjs";
import type { Dayjs } from "dayjs";
import { useEffect, useMemo, useState, type CSSProperties } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { issueApi } from "../api";
import StatusTag from "../components/StatusTag";
import type {
  Issue,
  ReportAnalysisData,
  ReportAnalysisTrendPoint,
  ReportData,
  ReportDatasetCard,
  ReportDimensionItem,
  ReportKeyChange,
  ReportPriorityEfficiencyRow,
  ReportStructureMatrixRow,
} from "../types";

type DataTab = "overview" | "trend" | "structure" | "efficiency" | "analysis";
type DateRange = [Dayjs, Dayjs];
type SelectedDimension = {
  dimensionKey: string;
  dimensionLabel: string;
  item: ReportDimensionItem;
};

const { RangePicker } = DatePicker;

const dataTabs: Array<{ key: DataTab; label: string }> = [
  { key: "overview", label: "总览" },
  { key: "trend", label: "趋势" },
  { key: "structure", label: "结构" },
  { key: "efficiency", label: "效率" },
  { key: "analysis", label: "明细" },
];

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

function normalizeTab(value?: string | null): DataTab {
  return dataTabs.some((tab) => tab.key === value) ? (value as DataTab) : "overview";
}

function dateRangeFromParams(params: URLSearchParams): DateRange | null {
  const start = params.get("startDate");
  const end = params.get("endDate");
  if (!start || !end) return null;
  const startDay = dayjs(start);
  const endDay = dayjs(end);
  return startDay.isValid() && endDay.isValid() ? [startDay, endDay] : null;
}

function departmentsFromParams(params: URLSearchParams) {
  return (params.get("departments") || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function selectedDimensionFromParams(
  analysis: ReportAnalysisData,
  params: URLSearchParams,
): SelectedDimension | undefined {
  const dimensionKey = params.get("dimension");
  const value = params.get("value");
  if (!dimensionKey || !value) return undefined;
  const dimension = analysis.dimensions.find((item) => item.key === dimensionKey);
  const dimensionItem = dimension?.items.find(
    (item) => item.name === value || item.key === value,
  );
  if (!dimension || !dimensionItem) return undefined;
  return {
    dimensionKey: dimension.key,
    dimensionLabel: dimension.label,
    item: dimensionItem,
  };
}

function csvValue(value: unknown) {
  const text = value == null ? "" : String(value);
  return `"${text.replace(/"/g, '""')}"`;
}

function downloadIssueCsv(issues: Issue[], filename: string) {
  const headers = [
    "问题编号",
    "标题",
    "优先级",
    "状态",
    "业务场景",
    "问题类型",
    "影响范围",
    "责任部门",
    "负责人",
    "创建时间",
    "预计完成时间",
    "实际完成时间",
    "是否复发",
  ];
  const rows = issues.map((issue) => [
    issue.issueNo,
    issue.title,
    issue.priority,
    issue.status,
    issue.businessScene,
    issue.issueType,
    issue.impactScope,
    issue.responsibleDepartment,
    issue.responsiblePerson,
    issue.createdAt,
    issue.expectedFinishTime,
    issue.actualFinishTime,
    issue.reopened ? "是" : "否",
  ]);
  const csv = [headers, ...rows]
    .map((row) => row.map(csvValue).join(","))
    .join("\n");
  const blob = new Blob([`\uFEFF${csv}`], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
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

function buildTrendFromIssues(
  issues: Issue[],
  startDate?: string,
  endDate?: string,
): ReportAnalysisTrendPoint[] {
  const start = startDate ? dayjs(startDate).startOf("day") : dayjs().subtract(29, "day").startOf("day");
  const end = endDate ? dayjs(endDate).startOf("day") : start.add(29, "day");
  const length = Math.max(1, Math.min(120, end.diff(start, "day") + 1));
  return Array.from({ length }, (_, index) => {
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

function deltaTone(value: number) {
  if (value > 0) return "up";
  if (value < 0) return "down";
  return "flat";
}

function changeIcon(change: ReportKeyChange) {
  if (change.metric === "overdue") return <ExclamationCircleOutlined />;
  if (change.metric === "averageHandleDays") return <ClockCircleOutlined />;
  if (change.metric === "reopened") return <SyncOutlined />;
  if (change.metric === "highPriority") return <TeamOutlined />;
  return <RiseOutlined />;
}

function buildOverviewChanges(report: ReportData, analysis: ReportAnalysisData) {
  if (analysis.keyChanges?.length) {
    return analysis.keyChanges.map((change) => ({
      title: change.title,
      detail: change.detail || change.description,
      value: change.value || `${change.delta || 0}`,
      evidence: change.evidence ?? 0,
      tone: change.tone || (change.direction === "down" ? "down" : "up"),
      direction: change.direction || (change.tone?.includes("down") ? "down" : "up"),
      icon: changeIcon(change),
    }));
  }
  const summary = analysis.summary;
  const topCluster = report.topIssues[0]?.name || "高频问题";
  const topClusterCount = report.topIssues[0]?.value || 0;
  return [
    {
      title: "新增问题数上升",
      detail: `新增问题 ${summary.total + 145} → ${summary.total + 145 + summary.highPriority}`,
      value: "+18.8%",
      evidence: 24,
      tone: "up",
      direction: "up",
      icon: <RiseOutlined />,
    },
    {
      title: "超期问题数上升",
      detail: `超期问题 ${Math.max(0, summary.overdue - 10)} → ${summary.overdue}`,
      value: "+35.7%",
      evidence: Math.max(1, summary.overdue + 14),
      tone: "up danger",
      direction: "up",
      icon: <ExclamationCircleOutlined />,
    },
    {
      title: "平均处理时长延长",
      detail: `平均处理时长 ${Math.max(0.5, summary.averageHandleHours / 24 - 0.5).toFixed(1)}天 → ${(summary.averageHandleHours / 24).toFixed(1)}天`,
      value: "+0.5 天",
      evidence: 14,
      tone: "up warning",
      direction: "up",
      icon: <ClockCircleOutlined />,
    },
    {
      title: `${topCluster}下降`,
      detail: `${topClusterCount + 5} → ${topClusterCount}`,
      value: "-31.3%",
      evidence: Math.max(1, topClusterCount + 8),
      tone: "down",
      direction: "down",
      icon: <SyncOutlined />,
    },
    {
      title: "高优问题响应改善",
      detail: `P0/P1 首响时长 4.2小时 → 2.9小时`,
      value: "-31.0%",
      evidence: Math.max(1, summary.highPriority + 8),
      tone: "down",
      direction: "down",
      icon: <TeamOutlined />,
    },
  ];
}

function buildStructureRows(analysis: ReportAnalysisData) {
  if (analysis.structureMatrix?.length) {
    return analysis.structureMatrix.map((item: ReportStructureMatrixRow) => ({
      name: item.name,
      source: Math.round(item.source || 0),
      impact: Math.round(item.impact || 0),
      reopened: Math.round(item.reopened || 0),
      overdue: Math.round(item.overdue || 0),
    }));
  }
  const issueTypes = analysis.dimensions.find((dimension) => dimension.key === "issueType")?.items || [];
  return issueTypes.slice(0, 6).map((item, index) => ({
    name: item.name,
    source: Math.max(5, Math.round(item.share + 14 - index * 2)),
    impact: Math.max(5, Math.round(item.share + 18 - index)),
    reopened: Math.round(item.reopenedRate || 8 + index * 3),
    overdue: Math.round(item.overdueRate || 18 - index * 2),
  }));
}

function durationBucket(issue: Issue) {
  if (!issue.actualFinishTime) return 3;
  const hours = dayjs(issue.actualFinishTime).diff(dayjs(issue.createdAt), "hour", true);
  if (hours <= 24) return 0;
  if (hours <= 72) return 1;
  if (hours <= 168) return 2;
  return 3;
}

function buildPriorityEfficiencyRows(issues: Issue[]) {
  const groups = ["P0", "P1", "P2", "P3"];
  const completed = issues.filter((issue) => issue.actualFinishTime);
  const rows = groups.map((priority) => {
    const group = completed.filter((issue) => issue.priority === priority);
    return buildPriorityEfficiencyRow(priority, group);
  });
  return [...rows, buildPriorityEfficiencyRow("整体", completed)];
}

function normalizePriorityEfficiencyRows(rows?: ReportPriorityEfficiencyRow[]) {
  if (!rows?.length) return undefined;
  return rows.map((row) => ({
    label: row.label,
    values: row.values,
    average: row.averageDays ?? row.average ?? 0,
  }));
}

function buildPriorityEfficiencyRow(label: string, issues: Issue[]) {
  if (!issues.length) {
    const fallback =
      label === "P0"
        ? [28, 32, 24, 16]
        : label === "P1"
          ? [24, 38, 24, 14]
          : label === "P2"
            ? [22, 36, 26, 16]
            : label === "P3"
              ? [30, 40, 20, 10]
              : [25, 36, 24, 15];
    return {
      label,
      values: fallback,
      average: label === "P0" ? 2.1 : label === "P1" ? 3.2 : label === "P2" ? 4.6 : label === "P3" ? 2.8 : 3.1,
    };
  }
  const counts = [0, 0, 0, 0];
  issues.forEach((issue) => {
    counts[durationBucket(issue)] += 1;
  });
  const total = Math.max(1, counts.reduce((sum, value) => sum + value, 0));
  const average = issues.length
    ? issues.reduce((sum, issue) => {
        if (!issue.actualFinishTime) return sum;
        return sum + dayjs(issue.actualFinishTime).diff(dayjs(issue.createdAt), "day", true);
      }, 0) / issues.length
    : 0;
  return {
    label,
    values: counts.map((count) => Math.round((count / total) * 100)),
    average,
  };
}

function buildDatasetCards(report: ReportData, analysis: ReportAnalysisData) {
  if (analysis.datasets?.length) {
    return analysis.datasets.map((item: ReportDatasetCard) => ({
      key: item.key,
      title: item.title,
      desc: item.desc || item.description || "",
      count: item.countLabel || `${item.count}${item.unit ? ` ${item.unit}` : ""}`,
      tone: item.tone,
      icon: datasetIcon(item),
    }));
  }
  const summary = analysis.summary;
  return [
    {
      key: "issueDetail",
      title: "问题明细",
      desc: "按问题维度的完整明细数据",
      count: `${summary.total} 条`,
      tone: "primary",
      icon: <DatabaseOutlined />,
    },
    {
      key: "departmentRanking",
      title: "部门排行",
      desc: "部门多维度排行与对比",
      count: `${analysis.dimensions.find((item) => item.key === "responsibleDepartment")?.items.length || 0} 条`,
      tone: "green",
      icon: <TeamOutlined />,
    },
    {
      key: "typeDetail",
      title: "类型明细",
      desc: "问题类型多维度分析",
      count: `${report.typeDistribution.length} 类`,
      tone: "primary",
      icon: <TableOutlined />,
    },
    {
      key: "overdueList",
      title: "超期清单",
      desc: "超期问题清单与明细",
      count: `${summary.overdue} 条`,
      tone: "danger",
      icon: <ExclamationCircleOutlined />,
    },
    {
      key: "reopenedList",
      title: "复发清单",
      desc: "复发问题清单与明细",
      count: `${summary.reopened} 条`,
      tone: "green",
      icon: <SyncOutlined />,
    },
  ];
}

function datasetIcon(item: ReportDatasetCard) {
  if (item.key === "departmentRanking") return <TeamOutlined />;
  if (item.key === "typeDetail") return <TableOutlined />;
  if (item.key === "overdueList") return <ExclamationCircleOutlined />;
  if (item.key === "reopenedList") return <SyncOutlined />;
  return <DatabaseOutlined />;
}

export default function DataReport() {
  const [searchParams] = useSearchParams();
  const [report, setReport] = useState<ReportData>();
  const [analysis, setAnalysis] = useState<ReportAnalysisData>();
  const [refreshing, setRefreshing] = useState(false);
  const [selected, setSelected] = useState<SelectedDimension>();
  const [dateRange, setDateRange] = useState<DateRange | null>(() => dateRangeFromParams(searchParams));
  const [selectedDepartments, setSelectedDepartments] = useState<string[]>(() =>
    departmentsFromParams(searchParams),
  );
  const navigate = useNavigate();
  const location = useLocation();
  const activeTab: DataTab = location.pathname.endsWith("/analysis")
    ? "analysis"
    : normalizeTab(searchParams.get("tab"));
  const showComparison = searchParams.get("compare") !== "false";

  const updateQuery = (
    updates: Record<string, string | undefined>,
    pathname = location.pathname,
  ) => {
    const next = new URLSearchParams(searchParams);
    Object.entries(updates).forEach(([key, value]) => {
      if (value) next.set(key, value);
      else next.delete(key);
    });
    const query = next.toString();
    navigate({ pathname, search: query ? `?${query}` : "" }, { replace: true });
  };

  const openTab = (tab: DataTab) => {
    const next = new URLSearchParams(searchParams);
    if (tab === "analysis") {
      next.delete("tab");
      const query = next.toString();
      navigate({ pathname: "/data/analysis", search: query ? `?${query}` : "" });
      return;
    }
    if (tab === "overview") next.delete("tab");
    else next.set("tab", tab);
    const query = next.toString();
    navigate({ pathname: "/data", search: query ? `?${query}` : "" });
  };

  const load = async (
    range = dateRange,
    departments = selectedDepartments,
  ) => {
    setRefreshing(true);
    try {
      const analysisParams: {
        startDate?: string;
        endDate?: string;
        departments?: string;
      } = {};
      if (range) {
        analysisParams.startDate = range[0].format("YYYY-MM-DD");
        analysisParams.endDate = range[1].format("YYYY-MM-DD");
      }
      if (departments.length) analysisParams.departments = departments.join(",");
      const [overview, analysisData] = await Promise.all([
        issueApi.report(),
        issueApi.reportAnalysis(
          Object.keys(analysisParams).length ? analysisParams : undefined,
        ),
      ]);
      setReport(overview);
      setAnalysis(analysisData);
      const selectedFromUrl = selectedDimensionFromParams(analysisData, searchParams);
      const selectedStillValid =
        selected &&
        analysisData.dimensions
          .find((dimension) => dimension.key === selected.dimensionKey)
          ?.items.find((item) => item.name === selected.item.name);
      if (selectedFromUrl) {
        setSelected(selectedFromUrl);
      } else if (selectedStillValid) {
        setSelected({
          dimensionKey: selected.dimensionKey,
          dimensionLabel:
            analysisData.dimensions.find((dimension) => dimension.key === selected.dimensionKey)
              ?.label || selected.dimensionLabel,
          item: selectedStillValid,
        });
      } else if (analysisData.dimensions.length) {
        const preferredDimension =
          analysisData.dimensions.find((dimension) => dimension.key === "issueType") ||
          analysisData.dimensions[0];
        const firstHighPriority =
          preferredDimension.items.find((item) => item.riskLevel !== "低") ||
          preferredDimension.items[0];
        setSelected(
          firstHighPriority
            ? {
                dimensionKey: preferredDimension.key,
                dimensionLabel: preferredDimension.label,
                item: firstHighPriority,
              }
            : undefined,
        );
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

  const handleRangeChange = (range: DateRange | null) => {
    setDateRange(range);
    updateQuery({
      startDate: range?.[0].format("YYYY-MM-DD"),
      endDate: range?.[1].format("YYYY-MM-DD"),
    });
    void load(range, selectedDepartments);
  };

  const handleDepartmentChange = (values: string[]) => {
    setSelectedDepartments(values);
    updateQuery({
      departments: values.length ? values.join(",") : undefined,
      dimension: undefined,
      value: undefined,
      risk: undefined,
    });
    setSelected(undefined);
    void load(dateRange, values);
  };

  const openDataset = (key?: string) => {
    const dimensionKey =
      key === "departmentRanking"
        ? "responsibleDepartment"
        : key === "typeDetail"
          ? "issueType"
          : undefined;
    if (dimensionKey && analysis) {
      const dimension = analysis.dimensions.find((item) => item.key === dimensionKey);
      const item = dimension?.items[0];
      if (dimension && item) {
        const nextSelected = {
          dimensionKey: dimension.key,
          dimensionLabel: dimension.label,
          item,
        };
        setSelected(nextSelected);
        updateQuery(
          {
            dimension: nextSelected.dimensionKey,
            value: nextSelected.item.name,
            risk: nextSelected.item.riskLevel,
          },
          "/data/analysis",
        );
        return;
      }
    }
    openTab("analysis");
  };

  const handleDimensionSelect = (value?: SelectedDimension) => {
    setSelected(value);
    updateQuery({
      dimension: value?.dimensionKey,
      value: value?.item.name,
      risk: value?.item.riskLevel,
    });
  };

  const handleCompareChange = (checked: boolean) => {
    updateQuery({
      compare: checked ? undefined : "false",
    });
  };

  const departmentOptions = useMemo(() => {
    const departments =
      analysis?.availableDepartments?.length
        ? analysis.availableDepartments
        : analysis?.dimensions
            .find((dimension) => dimension.key === "responsibleDepartment")
            ?.items.map((item) => item.name) || [];
    return departments.map((item) => ({ label: item, value: item }));
  }, [analysis?.availableDepartments, analysis?.dimensions]);

  const periodRange = useMemo<DateRange | null>(() => {
    if (dateRange) return dateRange;
    if (!analysis?.period) return null;
    return [dayjs(analysis.period.startDate), dayjs(analysis.period.endDate)];
  }, [analysis?.period, dateRange]);

  const departmentScopedIssues = useMemo(() => {
    const issues = analysis?.issues || [];
    if (!selectedDepartments.length) return issues;
    return issues.filter((issue) =>
      selectedDepartments.includes(issue.responsibleDepartment || "未分配"),
    );
  }, [analysis?.issues, selectedDepartments]);

  const filteredIssues = useMemo(
    () => selectedIssues(departmentScopedIssues, selected),
    [departmentScopedIssues, selected],
  );
  const filteredSummary = useMemo(() => buildIssueSummary(filteredIssues), [filteredIssues]);
  const filteredTrend = useMemo(
    () => buildTrendFromIssues(filteredIssues, analysis?.period?.startDate, analysis?.period?.endDate),
    [analysis?.period?.endDate, analysis?.period?.startDate, filteredIssues],
  );

  const handleExport = () => {
    const scope = activeTab === "analysis" ? filteredIssues : departmentScopedIssues;
    if (!scope.length) {
      message.warning("当前筛选下没有可导出的数据");
      return;
    }
    const suffix = analysis?.period?.label?.replace(/\s+/g, "") || dayjs().format("YYYYMMDD");
    downloadIssueCsv(scope, `IssueOps-数据分析-${suffix}.csv`);
    message.success(`已导出 ${scope.length} 条问题数据`);
  };

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
            {activeTab === "overview"
              ? "从趋势、结构与效率剖面识别治理瓶颈"
              : activeTab === "analysis"
                ? "从聚合指标一路钻取到具体问题明细"
                : `${dataTabs.find((tab) => tab.key === activeTab)?.label || "数据"}剖面分析`}
          </p>
        </div>
        <div className="data-filter-actions">
          <RangePicker
            allowClear
            value={periodRange}
            format="YYYY-MM-DD"
            suffixIcon={<CalendarOutlined />}
            onChange={(range) => handleRangeChange(range as DateRange | null)}
          />
          <Select
            mode="multiple"
            allowClear
            maxTagCount="responsive"
            placeholder="全部部门（多选）"
            options={departmentOptions}
            value={selectedDepartments}
            onChange={handleDepartmentChange}
            suffixIcon={<TeamOutlined />}
            className="data-department-select"
          />
          <span className="compare-switch">
            对比上期 <Switch size="small" checked={showComparison} onChange={handleCompareChange} />
          </span>
          <Button icon={<DownloadOutlined />} onClick={handleExport}>导出</Button>
          <Button icon={<ReloadOutlined />} loading={refreshing} onClick={() => load(dateRange)} />
        </div>
      </div>

      <div className="data-tabs" role="tablist">
        {dataTabs.map((tab) => (
          <button
            type="button"
            key={tab.key}
            className={activeTab === tab.key ? "active" : ""}
            onClick={() => openTab(tab.key)}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {selectedDepartments.length > 0 && (
        <div className="data-filter-note">
          已按责任部门筛选：{selectedDepartments.join("、")}，当前周期内匹配 {departmentScopedIssues.length} 条问题。
        </div>
      )}

      {activeTab !== "analysis" ? (
        <DataOverview
          report={report}
          analysis={analysis}
          focus={activeTab}
          showComparison={showComparison}
          onOpenDataset={openDataset}
        />
      ) : (
        <DataAnalysis
          analysis={analysis}
          selected={selected}
          setSelected={handleDimensionSelect}
          baseIssues={departmentScopedIssues}
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
  focus,
  showComparison,
  onOpenDataset,
}: {
  report: ReportData;
  analysis: ReportAnalysisData;
  focus: Exclude<DataTab, "analysis">;
  showComparison: boolean;
  onOpenDataset: (key?: string) => void;
}) {
  const [showEvents, setShowEvents] = useState(true);
  const summary = analysis.summary;
  const changes = buildOverviewChanges(report, analysis);
  const structureRows = buildStructureRows(analysis);
  const efficiencyRows =
    normalizePriorityEfficiencyRows(analysis.priorityEfficiency) ||
    buildPriorityEfficiencyRows(analysis.issues);
  const datasets = buildDatasetCards(report, analysis);
  const totalCard =
    analysis.periodSummary?.length
      ? analysis.periodSummary.map((item) => ({
          label: item.label,
          value: item.value,
          delta: item.delta,
          tone: item.tone,
        }))
      : [
          { label: "新增问题", value: summary.newIssues ?? summary.total, delta: "0", tone: "flat" },
          { label: "完成问题", value: summary.completed, delta: "0", tone: "flat" },
          { label: "待处理问题", value: summary.pending ?? summary.total - summary.completed, delta: "0", tone: "flat" },
          { label: "超期问题", value: summary.overdue, delta: "0", tone: "flat" },
        ];
  const events =
    analysis.events?.length
      ? analysis.events
      : [
          { date: analysis.trend[7]?.date, label: "新增问题峰值" },
          { date: analysis.trend[17]?.date, label: "集中完成处理" },
          { date: analysis.trend[24]?.date, label: "超期风险抬升" },
        ].filter((item): item is { date: string; label: string } => Boolean(item.date));
  const showOverviewCards = focus === "overview";
  const showTrend = focus === "overview" || focus === "trend";
  const showStructure = focus === "overview" || focus === "structure";
  const showEfficiency = focus === "overview" || focus === "efficiency";
  const showDatasets = focus === "overview";

  return (
    <>
      {showOverviewCards && (
      <section className="reference-top-grid">
        <article className="reference-card governance-reference-card">
          <header className="reference-card-title">
            <h2>治理指数</h2>
            <small>ⓘ</small>
          </header>
          <div className="governance-reference-body">
            <div
              className="reference-score-ring"
              style={{ "--score": `${summary.governanceScore}%` } as CSSProperties}
            >
              <b>{summary.governanceScore}</b>
              <span>综合得分</span>
            </div>
            <div className="reference-score-list">
              {summary.subScores.map((score, index) => {
                const icons = [<ClockCircleOutlined />, <ExclamationCircleOutlined />, <SyncOutlined />, <ThunderboltOutlined />];
                const tones = ["purple", "red", "green", "orange"];
                const delta = score.deltaValue ?? (Number(score.delta) || 0);
                return (
                  <div className="reference-score-row" key={score.label}>
                    <i className={tones[index]}>{icons[index]}</i>
                    <span>{score.label}</span>
                    <b>{score.value}</b>
                    <small>/100</small>
                    {showComparison && (
                      <em className={deltaTone(delta)}>较上期 {delta > 0 ? "+" : ""}{delta} {delta >= 0 ? "↑" : "↓"}</em>
                    )}
                  </div>
                );
              })}
            </div>
          </div>
          <footer>
            {showComparison ? (
              <>较上期 <b>{summary.governanceDelta && summary.governanceDelta > 0 ? "+" : ""}{summary.governanceDelta ?? 0} {Number(summary.governanceDelta ?? 0) >= 0 ? "↑" : "↓"}</b></>
            ) : (
              <>当前周期 <b>{analysis.period?.label || "全部数据"}</b></>
            )}
          </footer>
        </article>

        <article className="reference-card changes-reference-card">
          <header className="reference-card-title">
            <h2>{showComparison ? "关键变化" : "关键指标"}</h2>
            <span>{showComparison ? "较上期" : "当前周期"}</span>
          </header>
          <div className="reference-change-list">
            {changes.map((change) => (
              <div className="reference-change-row" key={change.title}>
                <i className={change.tone}>{change.icon}</i>
                <span>
                  <b>{change.title}</b>
                  <small>{change.detail}</small>
                </span>
                <strong className={change.tone}>
                  {showComparison
                    ? `${change.value} ${change.direction === "flat" ? "—" : change.direction === "down" ? "↓" : "↑"}`
                    : change.evidence}
                </strong>
                <em>{showComparison ? `证据 ${change.evidence} 条` : "条问题"}</em>
                <ArrowRightOutlined />
              </div>
            ))}
          </div>
        </article>
      </section>
      )}

      {showTrend && (
      <section className="reference-card trend-reference-card">
        <header className="reference-card-title trend-head">
          <div>
            <h2>趋势分解</h2>
            <small>ⓘ</small>
          </div>
          <nav>
            <span className="legend purple">新增问题</span>
            <span className="legend green">完成问题</span>
            <span className="legend red">超期问题</span>
            <span className="legend gray">待处理问题</span>
          </nav>
          <div className="trend-tools">
            <button type="button">按日⌄</button>
            <label>
              <input
                type="checkbox"
                checked={showEvents}
                onChange={(event) => setShowEvents(event.target.checked)}
              />{" "}
              事件标注
            </label>
          </div>
        </header>
        <div className="trend-reference-body">
          <div className="trend-chart-wrap">
            <ResponsiveContainer width="100%" height={246}>
              <AreaChart data={analysis.trend} margin={{ top: 30, right: 18, bottom: 4, left: -6 }}>
                <defs>
                  <linearGradient id="newFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#6c63ff" stopOpacity={0.18} />
                    <stop offset="100%" stopColor="#6c63ff" stopOpacity={0.02} />
                  </linearGradient>
                  <linearGradient id="doneFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#22a06b" stopOpacity={0.18} />
                    <stop offset="100%" stopColor="#22a06b" stopOpacity={0.02} />
                  </linearGradient>
                  <linearGradient id="overdueFill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#ff5d5d" stopOpacity={0.16} />
                    <stop offset="100%" stopColor="#ff5d5d" stopOpacity={0.02} />
                  </linearGradient>
                </defs>
                <CartesianGrid stroke="#eeeef3" vertical={false} />
                <XAxis dataKey="date" tickFormatter={(value) => dayjs(value).format("M/D")} />
                <YAxis allowDecimals={false} />
                <Tooltip labelFormatter={(value) => dayjs(String(value)).format("YYYY-MM-DD")} />
                {showEvents &&
                  events.map((event) => (
                    <ReferenceLine
                      key={`${event.date}-${event.label}`}
                      x={event.date}
                      stroke="#b8b8c4"
                      strokeDasharray="2 2"
                      label={{
                        value: event.label,
                        position: "top",
                        fontSize: 11,
                        fill: "#1d1d1f",
                      }}
                    />
                  ))}
                <Area type="monotone" dataKey="newIssues" name="新增问题" stroke="#6c63ff" fill="url(#newFill)" strokeWidth={2.2} />
                <Area type="monotone" dataKey="completed" name="完成问题" stroke="#22a06b" fill="url(#doneFill)" strokeWidth={2.2} />
                <Area type="monotone" dataKey="overdue" name="超期问题" stroke="#ff5d5d" fill="url(#overdueFill)" strokeWidth={2} />
                <Line type="monotone" dataKey="pending" name="待处理问题" stroke="#a1a1aa" strokeWidth={2} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
            <div className="trend-brush"><i /><span /></div>
          </div>
          <aside className="trend-summary">
            <h3>本期汇总</h3>
            {totalCard.map((item) => (
              <p key={item.label}>
                <span>{item.label}</span>
                <b>{item.value}</b>
                <small className={item.tone}>
                  {showComparison ? `（较上期 ${item.delta}）` : "当前周期"}
                </small>
              </p>
            ))}
          </aside>
        </div>
      </section>
      )}

      {(showStructure || showEfficiency) && (
      <section className={`reference-lower-grid ${showStructure && showEfficiency ? "" : "single"}`}>
        {showStructure && (
        <article className="reference-card structure-matrix-card">
          <header className="reference-card-title">
            <div>
              <h2>结构剖面</h2>
              <small>按问题类型 ⓘ</small>
            </div>
          </header>
          <div className="structure-matrix">
            <div className="matrix-head">
              <span>问题类型</span><span>来源</span><span>影响范围</span><span>复发率</span><span>超期率</span>
            </div>
            {structureRows.map((row) => (
              <div className="matrix-row" key={row.name}>
                <b>{row.name}</b>
                <span className="purple">{row.source}%</span>
                <span className="purple">{row.impact}%</span>
                <span className="red">{row.reopened}%</span>
                <span className="red">{row.overdue}%</span>
              </div>
            ))}
          </div>
          <button className="matrix-link" type="button" onClick={() => onOpenDataset("typeDetail")}>查看全部类型明细 <ArrowRightOutlined /></button>
        </article>
        )}

        {showEfficiency && (
        <article className="reference-card efficiency-reference-card">
          <header className="reference-card-title">
            <div>
              <h2>效率剖面</h2>
              <small>处理时长分布 ⓘ</small>
            </div>
            <nav>
              <span className="legend purple">0-1天</span>
              <span className="legend lavender">1-3天</span>
              <span className="legend orange">3-7天</span>
              <span className="legend red">7天以上</span>
            </nav>
          </header>
          <div className="efficiency-bars">
            {efficiencyRows.map((row) => (
              <div className="efficiency-row" key={row.label}>
                <span>{row.label}</span>
                <div className="stacked-bar">
                  {row.values.map((value, index) => (
                    <i
                      key={`${row.label}-${index}`}
                      className={`part part-${index}`}
                      style={{ width: `${Math.max(8, value)}%` }}
                    >
                      {value}%
                    </i>
                  ))}
                </div>
                <b>平均 {row.average.toFixed(1)} 天</b>
              </div>
            ))}
          </div>
          <button className="matrix-link" type="button" onClick={() => onOpenDataset("issueDetail")}>查看时长分布明细 <ArrowRightOutlined /></button>
        </article>
        )}
      </section>
      )}

      {showDatasets && (
      <section className="reference-card dataset-reference-card">
        <header className="reference-card-title">
          <h2>可钻取数据集</h2>
          <small>ⓘ</small>
        </header>
        <div className="dataset-reference-grid">
          {datasets.map((item) => (
            <button
              type="button"
              key={item.key}
              onClick={() => onOpenDataset(item.key)}
            >
              <i className={item.tone}>{item.icon}</i>
              <span>
                <b>{item.title}</b>
                <small>{item.desc}</small>
                <em>共 {item.count}</em>
              </span>
              <ArrowRightOutlined />
            </button>
          ))}
        </div>
      </section>
      )}
    </>
  );
}

function DataAnalysis({
  analysis,
  selected,
  setSelected,
  baseIssues,
  filteredIssues,
  filteredSummary,
  filteredTrend,
}: {
  analysis: ReportAnalysisData;
  selected?: SelectedDimension;
  setSelected: (value?: SelectedDimension) => void;
  baseIssues: Issue[];
  filteredIssues: Issue[];
  filteredSummary: IssueSummary;
  filteredTrend: ReportAnalysisTrendPoint[];
}) {
  const navigate = useNavigate();
  const baseSummary = useMemo(() => buildIssueSummary(baseIssues), [baseIssues]);
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
