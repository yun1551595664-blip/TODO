import {
  ArrowLeftOutlined,
  EditOutlined,
  ExclamationCircleOutlined,
  LinkOutlined,
  PlusOutlined,
  RobotOutlined,
} from "@ant-design/icons";
import {
  Button,
  Divider,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Timeline,
  message,
} from "antd";
import dayjs from "dayjs";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { issueApi } from "../api";
import { useAuth } from "../auth";
import RecurrenceInsightCard from "../components/RecurrenceInsightCard";
import StatusTag from "../components/StatusTag";
import type { AuditLog, Issue, IssueAiAnalysis } from "../types";
const statuses = ["待处理", "处理中", "待验证", "已完成"];
const auditActionLabels: Record<string, string> = {
  CREATE_ISSUE: "新增问题",
  UPDATE_ISSUE: "编辑问题",
  DELETE_ISSUE: "删除问题",
  CHANGE_STATUS: "状态变更",
  MARK_REOPENED: "标记复发",
  CLEAR_REOPENED: "取消复发",
  ADD_ISSUE_LOG: "新增处理记录",
  AI_ACTION_EXECUTE: "AI 执行动作",
};

function auditActionLabel(actionType: string) {
  return auditActionLabels[actionType] || actionType;
}

function auditSourceLabel(source?: string) {
  return source === "AI" ? "AI 执行" : "人工操作";
}

function auditTimelineColor(log: AuditLog) {
  if (log.source === "AI") return "#6c63ff";
  if (log.actionType === "DELETE_ISSUE") return "#ff4d4f";
  return "#a1a1a6";
}

export default function IssueDetail() {
  const { id } = useParams();
  const nav = useNavigate();
  const { user, hasPermission } = useAuth();
  const [issue, setIssue] = useState<Issue>();
  const [audits, setAudits] = useState<AuditLog[]>([]);
  const [statusOpen, setStatusOpen] = useState(false);
  const [logOpen, setLogOpen] = useState(false);
  const [reopenedOpen, setReopenedOpen] = useState(false);
  const [reopenedReason, setReopenedReason] = useState("");
  const [nextStatus, setNextStatus] = useState("处理中");
  const [content, setContent] = useState("");
  const [operator, setOperator] = useState(user?.displayName || "");
  const [aiResult, setAiResult] = useState<IssueAiAnalysis>();
  const [aiLoadingType, setAiLoadingType] = useState("");
  const load = async () => {
    try {
      const issueData = await issueApi.get(Number(id));
      setIssue(issueData);
      try {
        setAudits(await issueApi.audits(Number(id)));
      } catch (error) {
        setAudits([]);
        message.warning(
          error instanceof Error ? error.message : "操作日志加载失败",
        );
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : "问题详情加载失败");
    }
  };
  useEffect(() => {
    void load();
  }, [id]);
  useEffect(() => {
    if (!operator && user?.displayName) setOperator(user.displayName);
  }, [user?.displayName, operator]);
  if (!issue) return <div className="page">加载中…</div>;
  const change = () =>
    issueApi
      .status(issue.id, { status: nextStatus, operator, content })
      .then(() => {
        message.success("状态已更新并写入处理记录");
        setStatusOpen(false);
        load();
      });
  const addLog = () =>
    issueApi
      .log(issue.id, { actionType: "处理记录", content, operator })
      .then(() => {
        message.success("处理记录已添加");
        setLogOpen(false);
        setContent("");
        load();
      });
  const toggleReopened = () =>
    issueApi
      .reopened(issue.id, {
        reopened: !issue.reopened,
        reason: reopenedReason,
        operator,
      })
      .then(() => {
        message.success(
          issue.reopened ? "已取消复发标记" : "已标记复发并写入处理记录",
        );
        setReopenedOpen(false);
        setReopenedReason("");
        load();
      });
  const ai = async (type: string) => {
    setAiLoadingType(type);
    try {
      const result = await issueApi.ai(issue.id, type);
      setAiResult(result);
      if (result.aiError) message.warning(result.aiError);
    } catch (err) {
      message.error(err instanceof Error ? err.message : "AI 分析失败，请重试");
    } finally {
      setAiLoadingType("");
    }
  };
  return (
    <div className="page detail-page">
      <div className="detail-toolbar">
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => nav("/issues")}
        >
          返回台账
        </Button>
        <Space>
          {hasPermission("issue:edit") && (
            <Button
              icon={<EditOutlined />}
              onClick={() => nav(`/issues/${issue.id}/edit`)}
            >
              编辑
            </Button>
          )}
          {hasPermission("issue:log") && (
            <Button icon={<PlusOutlined />} onClick={() => setLogOpen(true)}>
              新增处理记录
            </Button>
          )}
          {hasPermission("issue:status") && (
            <Button
              danger={!issue.reopened}
              icon={<ExclamationCircleOutlined />}
              onClick={() => {
                setReopenedReason(issue.reopenedReason || "");
                setReopenedOpen(true);
              }}
            >
              {issue.reopened ? "取消复发" : "标记复发"}
            </Button>
          )}
          {hasPermission("issue:status") && (
            <Button type="primary" onClick={() => setStatusOpen(true)}>
              更新状态
            </Button>
          )}
        </Space>
      </div>
      <div className="detail-layout">
        <article className="issue-document">
          <div className="issue-kicker">
            {issue.issueNo} · {issue.businessScene}
          </div>
          <h1>{issue.title}</h1>
          <div className="meta-row">
            <div>
              <span>优先级</span>
              <b className="danger">{issue.priority}</b>
            </div>
            <div>
              <span>当前状态</span>
              <StatusTag status={issue.status} size="prominent" />
            </div>
            <div>
              <span>责任部门</span>
              <b>{issue.responsibleDepartment || "-"}</b>
            </div>
            <div>
              <span>负责人</span>
              <b>{issue.responsiblePerson || "-"}</b>
            </div>
            <div>
              <span>创建时间</span>
              <b>{dayjs(issue.createdAt).format("YYYY-MM-DD HH:mm")}</b>
            </div>
          </div>
          <DocumentSection n="01" title="问题描述" text={issue.description} />
          <DocumentSection
            n="02"
            title="客户影响"
            text={issue.customerImpact}
          />
          <DocumentSection
            n="03"
            title="复现步骤"
            text={issue.reproduceSteps}
          />
          <DocumentSection
            n="04"
            title="原因分析"
            text={issue.rootCause || "待补充原因分析。"}
          />
          <DocumentSection
            n="05"
            title="修复方案"
            text={issue.fixSolution || "待补充修复方案。"}
          />
          <DocumentSection
            n="06"
            title="验证结果"
            text={issue.verifyResult || "等待修复完成后验证。"}
          />
        </article>
        <aside className="detail-inspector">
          <section>
            <h3>处理进度</h3>
            <Timeline
              items={(issue.logs || []).map((l) => ({
                color: l.actionType === "状态变更" ? "#6c63ff" : "#a1a1a6",
                children: (
                  <div>
                    <b>{l.actionType}</b>
                    <small>
                      {dayjs(l.createdAt).format("MM-DD HH:mm")} · {l.operator}
                    </small>
                    <p>{l.content}</p>
                  </div>
                ),
              }))}
            />
          </section>
          <Divider />
          <section>
            <h3>操作日志</h3>
            {audits.length ? (
              <Timeline
                className="audit-timeline"
                items={audits.map((log) => ({
                  color: auditTimelineColor(log),
                  children: (
                    <div>
                      <b>{auditActionLabel(log.actionType)}</b>
                      <small>
                        {dayjs(log.createdAt).format("MM-DD HH:mm")} ·{" "}
                        {log.operatorName || "system"}
                      </small>
                      <p>
                        <em className={`audit-source ${log.source === "AI" ? "ai" : ""}`}>
                          {auditSourceLabel(log.source)}
                        </em>
                        {log.operatorRole && <span>{log.operatorRole}</span>}
                        {log.aiActionId && <span>AI 动作 {log.aiActionId}</span>}
                      </p>
                    </div>
                  ),
                }))}
              />
            ) : (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="暂无操作日志"
              />
            )}
          </section>
          <Divider />
          <section>
            <h3>关联信息</h3>
            {issue.tapdUrl ? (
              <a href={issue.tapdUrl} target="_blank">
                <LinkOutlined /> 打开 TAPD 需求/缺陷
              </a>
            ) : (
              <span className="muted">暂无 TAPD 链接</span>
            )}
            <p>
              预计完成：
              {issue.expectedFinishTime
                ? dayjs(issue.expectedFinishTime).format("YYYY-MM-DD HH:mm")
                : "未设置"}
            </p>
            {issue.reopened && (
              <div className="reopened">
                <b>复发信号</b>
                <p>
                  {issue.reopenedReason || "该问题已被标记为复发，请复核根因。"}
                </p>
              </div>
            )}
          </section>
          <Divider />
          <section>
            <h3>
              AI 助理 <small>真实分析</small>
            </h3>
            <p className="ai-helper-note">
              基于当前问题详情生成草稿和判断结果；AI 不直接改写数据，确认后再写入处理记录或详情字段。
            </p>
            <div className="ai-buttons">
              <Button
                icon={<RobotOutlined />}
                loading={aiLoadingType === "root-cause"}
                onClick={() => ai("root-cause")}
              >
                生成归因草稿
              </Button>
              <Button
                icon={<RobotOutlined />}
                loading={aiLoadingType === "suggestion"}
                onClick={() => ai("suggestion")}
              >
                生成处理建议
              </Button>
              <Button
                icon={<RobotOutlined />}
                loading={aiLoadingType === "duplicate"}
                onClick={() => ai("duplicate")}
              >
                判断重复/同源
              </Button>
            </div>
            {issue.reopened && (
              <p className="ai-helper-note">
                该问题已标记复发，下方可运行更完整的复发根因深度分析。
              </p>
            )}
          </section>
        </aside>
      </div>
      {issue.reopened && <RecurrenceInsightCard issueId={issue.id} />}
      <Modal
        title="更新问题状态"
        open={statusOpen}
        onCancel={() => setStatusOpen(false)}
        onOk={change}
      >
        <Select
          style={{ width: "100%", marginBottom: 16 }}
          value={nextStatus}
          onChange={setNextStatus}
          options={statuses.map((value) => ({ value, label: value }))}
        />
        <Input
          value={operator}
          onChange={(e) => setOperator(e.target.value)}
          placeholder="操作人"
          style={{ marginBottom: 16 }}
        />
        <Input.TextArea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder="说明本次状态变更"
          rows={4}
        />
      </Modal>
      <Modal
        title="新增处理记录"
        open={logOpen}
        onCancel={() => setLogOpen(false)}
        onOk={addLog}
      >
        <Input
          value={operator}
          onChange={(e) => setOperator(e.target.value)}
          placeholder="操作人"
          style={{ marginBottom: 16 }}
        />
        <Input.TextArea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder="记录定位过程、处理结论或下一步计划"
          rows={5}
        />
      </Modal>
      <Modal
        title={issue.reopened ? "取消复发标记" : "标记为复发问题"}
        open={reopenedOpen}
        onCancel={() => setReopenedOpen(false)}
        onOk={toggleReopened}
        okText={issue.reopened ? "确认取消" : "确认标记"}
        okButtonProps={{ danger: !issue.reopened }}
      >
        <Input
          value={operator}
          onChange={(e) => setOperator(e.target.value)}
          placeholder="操作人"
          style={{ marginBottom: 16 }}
        />
        {!issue.reopened && (
          <Input.TextArea
            value={reopenedReason}
            onChange={(e) => setReopenedReason(e.target.value)}
            placeholder="说明复发场景、时间和影响"
            rows={4}
          />
        )}
      </Modal>
      <Modal
        title={aiResult?.title || "AI 分析结果"}
        open={!!aiResult}
        width={680}
        footer={null}
        onCancel={() => setAiResult(undefined)}
      >
        {aiResult && <IssueAiResult data={aiResult} />}
      </Modal>
    </div>
  );
}

function IssueAiResult({ data }: { data: IssueAiAnalysis }) {
  return (
    <div className="issue-ai-result">
      <header>
        <span>
          {data.generatedBy} · {data.model}
        </span>
        <b>{data.issueNo}</b>
      </header>
      <section>
        <h4>分析结论</h4>
        <p>{data.summary}</p>
      </section>
      {data.evidence?.length > 0 && (
        <section>
          <h4>关键依据</h4>
          <ul>
            {data.evidence.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </section>
      )}
      {data.draft?.length > 0 && (
        <section>
          <h4>可用草稿</h4>
          <div className="issue-ai-draft">
            {data.draft.map((item) => (
              <p key={item}>{item}</p>
            ))}
          </div>
        </section>
      )}
      {data.suggestedActions?.length > 0 && (
        <section>
          <h4>建议动作</h4>
          <ol>
            {data.suggestedActions.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ol>
        </section>
      )}
      {data.relatedIssues?.length > 0 && (
        <section>
          <h4>相关问题</h4>
          <div className="issue-ai-related">
            {data.relatedIssues.map((item) => (
              <span key={item.issueNo}>
                {item.issueNo} · {item.title}
              </span>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function DocumentSection({
  n,
  title,
  text,
}: {
  n: string;
  title: string;
  text?: string;
}) {
  return (
    <section className="doc-section">
      <span>{n}</span>
      <div>
        <h2>{title}</h2>
        <p>{text || "暂无内容"}</p>
      </div>
    </section>
  );
}
