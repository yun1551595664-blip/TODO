import {
  DeleteOutlined,
  EyeOutlined,
  LinkOutlined,
  PlusOutlined,
  SearchOutlined,
} from "@ant-design/icons";
import {
  Button,
  DatePicker,
  Input,
  Popconfirm,
  Select,
  Space,
  Table,
  message,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import dayjs from "dayjs";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { issueApi } from "../api";
import { useAuth } from "../auth";
import StatusTag from "../components/StatusTag";
import { useDictionaryOptions } from "../hooks/useDictionaryOptions";
import type { Issue, PageData } from "../types";
const opts = (x: string[]) => x.map((v) => ({ label: v, value: v }));
export default function IssueList() {
  const nav = useNavigate();
  const { hasPermission } = useAuth();
  const [data, setData] = useState<PageData<Issue>>({
    content: [],
    totalElements: 0,
    totalPages: 0,
    number: 0,
    size: 10,
  });
  const [params, setParams] = useState<Record<string, any>>({
    page: 0,
    size: 10,
  });
  const [loading, setLoading] = useState(false);
  const { options: dictionaryOptions, loading: dictionaryLoading } =
    useDictionaryOptions(false);
  const load = async () => {
    setLoading(true);
    try {
      setData(await issueApi.list(params));
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    load();
  }, [JSON.stringify(params)]);
  const columns: ColumnsType<Issue> = [
    {
      title: "问题编号",
      dataIndex: "issueNo",
      width: 140,
      fixed: "left",
      render: (v) => <span className="issue-no">{v}</span>,
    },
    {
      title: "问题标题",
      dataIndex: "title",
      width: 220,
      fixed: "left",
      ellipsis: true,
      render: (v, r) => <a onClick={() => nav(`/issues/${r.id}`)}>{v}</a>,
    },
    { title: "来源", dataIndex: "source", width: 90 },
    { title: "业务场景", dataIndex: "businessScene", width: 100 },
    { title: "问题类型", dataIndex: "issueType", width: 100 },
    { title: "影响范围", dataIndex: "impactScope", width: 100 },
    {
      title: "优先级",
      dataIndex: "priority",
      width: 70,
      render: (v) => (
        <span>
          <i className={`priority ${v}`} />
          {v}
        </span>
      ),
    },
    {
      title: "状态",
      dataIndex: "status",
      width: 90,
      render: (v) => <StatusTag status={v} />,
    },
    { title: "责任部门", dataIndex: "responsibleDepartment", width: 100 },
    { title: "负责人", dataIndex: "responsiblePerson", width: 80 },
    {
      title: "TAPD",
      dataIndex: "tapdUrl",
      width: 70,
      render: (v) =>
        v ? (
          <Button
            type="link"
            size="small"
            icon={<LinkOutlined />}
            href={v}
            target="_blank"
          />
        ) : (
          "-"
        ),
    },
    {
      title: "创建时间",
      dataIndex: "createdAt",
      width: 140,
      render: (v) => (v ? dayjs(v).format("YYYY-MM-DD HH:mm") : "-"),
    },
    {
      title: "预计完成",
      dataIndex: "expectedFinishTime",
      width: 140,
      render: (v) => (v ? dayjs(v).format("YYYY-MM-DD HH:mm") : "-"),
    },
    {
      title: "实际完成",
      dataIndex: "actualFinishTime",
      width: 140,
      render: (v) => (v ? dayjs(v).format("YYYY-MM-DD HH:mm") : "-"),
    },
    {
      title: "复发",
      dataIndex: "reopened",
      width: 60,
      render: (v) => (v ? <span className="danger">是</span> : "否"),
    },
    {
      title: "操作",
      fixed: "right",
      width: 90,
      render: (_, r) => (
        <Space>
          <Button
            type="text"
            icon={<EyeOutlined />}
            onClick={() => nav(`/issues/${r.id}`)}
          />
          {hasPermission("issue:delete") && (
            <Popconfirm
              title="确认删除该问题？"
              onConfirm={() => issueApi.remove(r.id).then(load)}
            >
              <Button type="text" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ];
  return (
    <div className="page">
      <div className="list-heading">
        <div>
          <h1>问题台账</h1>
          <p>统一跟踪异常来源、影响、责任与闭环结果</p>
        </div>
        {hasPermission("issue:create") && (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => nav("/issues/new")}
          >
            新增问题
          </Button>
        )}
      </div>
      <div className="filter-bar">
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索编号或标题"
          value={params.keyword}
          onChange={(e) =>
            setParams({ ...params, page: 0, keyword: e.target.value })
          }
        />
        <Select
          allowClear
          loading={dictionaryLoading}
          placeholder="问题来源"
          options={dictionaryOptions.sources}
          onChange={(v) => setParams({ ...params, page: 0, source: v })}
        />
        <Select
          allowClear
          showSearch
          loading={dictionaryLoading}
          placeholder="业务场景"
          options={dictionaryOptions.businessScenes}
          onChange={(v) =>
            setParams({ ...params, page: 0, businessScene: v })
          }
        />
        <Select
          allowClear
          loading={dictionaryLoading}
          placeholder="问题类型"
          options={dictionaryOptions.issueTypes}
          onChange={(v) => setParams({ ...params, page: 0, issueType: v })}
        />
        <Select
          allowClear
          loading={dictionaryLoading}
          placeholder="影响范围"
          options={dictionaryOptions.impactScopes}
          onChange={(v) => setParams({ ...params, page: 0, impactScope: v })}
        />
        <Select
          allowClear
          placeholder="当前状态"
          options={opts(["待处理", "处理中", "待验证", "已完成"])}
          onChange={(v) => setParams({ ...params, page: 0, status: v })}
        />
        <Select
          allowClear
          placeholder="优先级"
          options={opts(["P0", "P1", "P2", "P3"])}
          onChange={(v) => setParams({ ...params, page: 0, priority: v })}
        />
        <Select
          allowClear
          placeholder="责任部门"
          options={opts(["产品部", "技术部", "数据部", "交易平台"])}
          onChange={(v) =>
            setParams({ ...params, page: 0, responsibleDepartment: v })
          }
        />
        <Select
          allowClear
          placeholder="是否超期"
          options={opts(["true", "false"]).map((o, i) => ({
            ...o,
            label: i ? "未超期" : "已超期",
          }))}
          onChange={(v) => setParams({ ...params, page: 0, overdue: v })}
        />
        <Select
          allowClear
          placeholder="是否复发"
          options={[
            { label: "是", value: "true" },
            { label: "否", value: "false" },
          ]}
          onChange={(v) => setParams({ ...params, page: 0, reopened: v })}
        />
        <DatePicker.RangePicker
          onChange={(dates) =>
            setParams({
              ...params,
              page: 0,
              createdStart: dates?.[0]?.format("YYYY-MM-DD"),
              createdEnd: dates?.[1]?.format("YYYY-MM-DD"),
            })
          }
        />
      </div>
      <div className="table-surface">
        <Table
          rowKey="id"
          size="middle"
          loading={loading}
          columns={columns}
          dataSource={data.content}
          scroll={{ x: 1840 }}
          pagination={{
            current: data.number + 1,
            pageSize: data.size,
            total: data.totalElements,
            showSizeChanger: true,
            showTotal: (t) => `共 ${t} 条问题`,
            onChange: (page, size) =>
              setParams({ ...params, page: page - 1, size }),
          }}
        />
      </div>
    </div>
  );
}
