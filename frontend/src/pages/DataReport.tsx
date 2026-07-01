import {
  ArrowRightOutlined,
  BulbOutlined,
  ClockCircleOutlined,
  CopyOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { Button, Empty, Progress, Skeleton, message } from "antd";
import { useEffect, useState } from "react";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { useNavigate } from "react-router-dom";
import { issueApi } from "../api";
import StatusTag from "../components/StatusTag";
import type { ReportData } from "../types";

const colors = ["#6c63ff", "#22a06b", "#ffb020", "#9b8cff", "#c7c7cc"];

export default function DataReport() {
  const [data, setData] = useState<ReportData>();
  const [refreshing, setRefreshing] = useState(false);
  const navigate = useNavigate();

  const load = async () => {
    setRefreshing(true);
    try {
      setData(await issueApi.report());
    } catch (error) {
      message.error(error instanceof Error ? error.message : "数据加载失败");
    } finally {
      setRefreshing(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  if (!data)
    return (
      <div className="page">
        <Skeleton active />
      </div>
    );

  const totalIssues = data.typeDistribution.reduce(
    (total, item) => total + item.value,
    0,
  );
  const topMax = Math.max(1, ...data.topIssues.map((item) => item.value));
  const departmentMax = Math.max(
    1,
    ...data.departmentDistribution.map((item) => item.value),
  );
  const averageHandleTime =
    data.averageHandleHours >= 48
      ? `${(data.averageHandleHours / 24).toFixed(1)} 天`
      : `${data.averageHandleHours} 小时`;

  const metrics = [
    {
      label: "问题总量",
      value: totalIssues,
      note: "当前统计范围",
      icon: <DatabaseOutlined />,
      tone: "primary",
    },
    {
      label: "超期问题",
      value: data.overdueIssues.length,
      note: "需要优先处理",
      icon: <WarningOutlined />,
      tone: "danger",
    },
    {
      label: "重复问题",
      value: data.duplicateCount,
      note: "含复发与重复簇",
      icon: <CopyOutlined />,
      tone: "warning",
    },
    {
      label: "平均处理时长",
      value: averageHandleTime,
      note: "基于已完成问题",
      icon: <ClockCircleOutlined />,
      tone: "neutral",
    },
  ];

  return (
    <div className="page data-report-page">
      <div className="list-heading report-heading">
        <div>
          <h1>数据报表</h1>
          <p>查看问题分布、处理效率与风险明细，辅助产品和业务决策</p>
        </div>
        <div className="report-actions">
          <span>全量数据</span>
          <Button
            icon={<ReloadOutlined />}
            loading={refreshing}
            onClick={load}
          >
            刷新数据
          </Button>
        </div>
      </div>

      <section className="report-kpis" aria-label="数据概况">
        {metrics.map((metric) => (
          <article className="report-kpi" key={metric.label}>
            <div className={`report-kpi-icon ${metric.tone}`}>{metric.icon}</div>
            <div>
              <small>{metric.label}</small>
              <b>{metric.value}</b>
              <span>{metric.note}</span>
            </div>
          </article>
        ))}
      </section>

      <section className="report-grid report-grid-primary">
        <article className="report-card">
          <header className="report-card-header">
            <div>
              <h2>问题主题分布</h2>
              <p>按相似问题聚类，观察当前业务主题构成</p>
            </div>
            <span>{data.topIssues.length} 个问题簇</span>
          </header>
          <div className="ranking report-ranking">
            {data.topIssues.map((item, index) => (
              <div className="rank" key={item.name}>
                <b>{String(index + 1).padStart(2, "0")}</b>
                <span title={item.name}>{item.name}</span>
                <i>
                  <em style={{ width: `${(item.value / topMax) * 100}%` }} />
                </i>
                <strong>{item.value} 个</strong>
              </div>
            ))}
          </div>
        </article>

        <article className="report-card">
          <header className="report-card-header">
            <div>
              <h2>责任部门分布</h2>
              <p>按问题数量排序，观察当前责任负载</p>
            </div>
          </header>
          <div className="department-list">
            {data.departmentDistribution.map((item) => (
              <div className="department" key={item.name}>
                <span>{item.name}</span>
                <Progress
                  percent={(item.value / departmentMax) * 100}
                  showInfo={false}
                  strokeColor="#6c63ff"
                />
                <b>{item.value}</b>
              </div>
            ))}
          </div>
        </article>
      </section>

      <section className="report-grid report-grid-secondary">
        <article className="report-card">
          <header className="report-card-header">
            <div>
              <h2>问题类型构成</h2>
              <p>查看各类问题在总体中的数量与占比</p>
            </div>
          </header>
          <div className="pie-wrap report-pie">
            <div className="report-donut">
              <ResponsiveContainer width="100%" height={220}>
                <PieChart>
                  <Pie
                    data={data.typeDistribution}
                    dataKey="value"
                    nameKey="name"
                    innerRadius={58}
                    outerRadius={84}
                    paddingAngle={2}
                  >
                    {data.typeDistribution.map((item, index) => (
                      <Cell
                        key={item.name}
                        fill={colors[index % colors.length]}
                      />
                    ))}
                  </Pie>
                  <Tooltip />
                </PieChart>
              </ResponsiveContainer>
              <div className="donut-total">
                <b>{totalIssues}</b>
                <span>全部问题</span>
              </div>
            </div>
            <div className="type-legend">
              {data.typeDistribution.map((item, index) => (
                <p key={item.name}>
                  <i style={{ background: colors[index % colors.length] }} />
                  <span>{item.name}</span>
                  <b>{item.value}</b>
                  <small>
                    {totalIssues
                      ? Math.round((item.value / totalIssues) * 100)
                      : 0}
                    %
                  </small>
                </p>
              ))}
            </div>
          </div>
        </article>

        <article className="report-card risk-card">
          <header className="report-card-header">
            <div>
              <h2>风险问题</h2>
              <p>已超过预计完成时间，点击可进入问题详情</p>
            </div>
            <span>{data.overdueIssues.length} 个待推进</span>
          </header>
          <div className="risk-list">
            {data.overdueIssues.length ? (
              data.overdueIssues.map((issue) => (
                <button
                  type="button"
                  className="overdue-item"
                  key={issue.id}
                  onClick={() => navigate(`/issues/${issue.id}`)}
                >
                  <span>
                    <b>{issue.title}</b>
                    <small>
                      {issue.responsibleDepartment} · {issue.responsiblePerson}
                    </small>
                  </span>
                  <StatusTag status={issue.status} />
                  <ArrowRightOutlined />
                </button>
              ))
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无风险问题" />
            )}
          </div>
        </article>
      </section>

      <section className="report-card suggestion-section">
        <header className="report-card-header">
          <div>
            <h2>建议采取的行动</h2>
            <p>基于当前问题类型、责任分布与处理效率生成</p>
          </div>
        </header>
        <div className="suggestions-grid">
          {data.suggestions.map((suggestion, index) => (
            <article className="suggestion-card" key={suggestion.title}>
              <div className="suggestion-number">
                {String(index + 1).padStart(2, "0")}
              </div>
              <BulbOutlined />
              <h3>{suggestion.title}</h3>
              <p>{suggestion.description}</p>
              <footer>
                <span>{suggestion.owner}</span>
                <b>{suggestion.expectedImpact}</b>
              </footer>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}
