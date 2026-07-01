import {
  CheckCircleFilled,
  ExclamationCircleFilled,
  ReloadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  WarningOutlined,
} from "@ant-design/icons";
import { Button, Empty, Spin, message } from "antd";
import { useState } from "react";
import { issueApi } from "../api";
import type { RecurrenceInsight } from "../types";

function confidenceTone(value: number) {
  if (value >= 0.66) return "high";
  if (value >= 0.4) return "mid";
  return "low";
}

function Hypotheses({ data }: { data: RecurrenceInsight }) {
  if (!data.rootCauseHypotheses.length) return null;
  return (
    <div className="recurrence-block">
      <h4>根因假设（按可能性排序）</h4>
      <ol className="recurrence-hypotheses">
        {data.rootCauseHypotheses.map((item, index) => (
          <li key={index} className="recurrence-hypothesis">
            <div className="recurrence-hyp-head">
              <span
                className={`recurrence-confidence recurrence-confidence-${confidenceTone(
                  item.confidence,
                )}`}
              >
                {Math.round(item.confidence * 100)}%
              </span>
              <b>{item.hypothesis}</b>
              {item.grounded ? (
                <em className="recurrence-grounded">
                  <CheckCircleFilled /> 证据接地
                </em>
              ) : (
                <em className="recurrence-ungrounded">
                  <WarningOutlined /> 证据存疑
                </em>
              )}
            </div>
            {item.evidence?.length > 0 && (
              <div className="recurrence-evidence">
                {item.evidence.map((ev) => (
                  <span key={ev}>{ev}</span>
                ))}
              </div>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}

function RecurrenceBody({ data }: { data: RecurrenceInsight }) {
  const grounding = data.groundingReport || { validIssueNoCount: 0 };
  const dropped = grounding.droppedCorrelations?.length || 0;
  const fabricated = grounding.fabricatedReferences?.length || 0;

  if (data.analysisMode === "evidence-only") {
    return (
      <div className="recurrence-body">
        <div className="recurrence-alert">
          <WarningOutlined /> {data.recurrenceSummary}
        </div>
      </div>
    );
  }

  return (
    <div className="recurrence-body">
      <p className="recurrence-summary">{data.recurrenceSummary}</p>

      <Hypotheses data={data} />

      {data.whyPreviousFixFailed && data.whyPreviousFixFailed !== "数据不足" && (
        <div className="recurrence-block recurrence-whyfix">
          <h4>为什么上次修复没挡住复发</h4>
          <p>{data.whyPreviousFixFailed}</p>
        </div>
      )}

      {data.correlatedIssues?.length > 0 && (
        <div className="recurrence-block">
          <h4>可能同源的问题</h4>
          <div className="recurrence-correlated">
            {data.correlatedIssues.map((item) => (
              <div key={item.issueNo} className="recurrence-correlated-item">
                <span className="recurrence-issue-no">{item.issueNo}</span>
                <p>{item.relation}</p>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="recurrence-actions">
        {data.systemicFix?.length > 0 && (
          <div className="recurrence-block">
            <h4>根治动作</h4>
            <ol>
              {data.systemicFix.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ol>
          </div>
        )}
        {data.verifyPlan?.length > 0 && (
          <div className="recurrence-block">
            <h4>验证方案</h4>
            <ol>
              {data.verifyPlan.map((item) => (
                <li key={item}>{item}</li>
              ))}
            </ol>
          </div>
        )}
      </div>

      <footer className="recurrence-footer">
        <span className="recurrence-model">
          <SafetyCertificateOutlined /> {data.generatedBy} · {data.model}
        </span>
        <span>
          对账：{grounding.validIssueNoCount} 个在库编号
          {dropped > 0 && ` · 已剔除 ${dropped} 个虚构关联`}
          {fabricated > 0 && ` · 已标记 ${fabricated} 个伪造引用`}
        </span>
        {data.needHumanReview && (
          <span className="recurrence-review">
            <ExclamationCircleFilled /> 待人工复核
          </span>
        )}
      </footer>
    </div>
  );
}

export default function RecurrenceInsightCard({
  issueId,
}: {
  issueId: number;
}) {
  const [data, setData] = useState<RecurrenceInsight>();
  const [loading, setLoading] = useState(false);

  const run = async () => {
    setLoading(true);
    try {
      const result = await issueApi.recurrenceInsight(issueId);
      setData(result);
    } catch (err) {
      message.error(
        err instanceof Error ? err.message : "根因分析失败，请重试",
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <section className="recurrence-card">
      <header className="recurrence-head">
        <div>
          <span className="recurrence-eyebrow">
            <RobotOutlined /> 复发根因 · AI 深度分析
          </span>
          <p>
            读取问题全文、处理时间线与同库问题，归纳“为什么反复”、找出可能同源的问题——
            结论严格基于真实数据，引用错误的编号会被自动剔除。
          </p>
        </div>
        <Button
          type={data ? "default" : "primary"}
          icon={data ? <ReloadOutlined /> : <RobotOutlined />}
          loading={loading}
          onClick={run}
        >
          {data ? "重新分析" : "运行根因分析"}
        </Button>
      </header>

      {loading && !data && (
        <div className="recurrence-loading">
          <Spin tip="AI 正在归纳根因（约 30 秒）" />
        </div>
      )}

      {data ? (
        <RecurrenceBody data={data} />
      ) : (
        !loading && (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="点击右上角运行，对这个复发问题做根因归纳"
          />
        )
      )}
    </section>
  );
}
