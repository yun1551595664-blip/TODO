import { ArrowLeftOutlined, SaveOutlined } from "@ant-design/icons";
import { Button, DatePicker, Form, Input, Select, message } from "antd";
import dayjs from "dayjs";
import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { issueApi } from "../api";
import { useAuth } from "../auth";
import { useDictionaryOptions } from "../hooks/useDictionaryOptions";
const { TextArea } = Input;
const options = (a: string[]) => a.map((value) => ({ value, label: value }));
export default function IssueForm() {
  const { id } = useParams();
  const nav = useNavigate();
  const { user, hasPermission } = useAuth();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const { options: dictionaryOptions, loading: dictionaryLoading } =
    useDictionaryOptions(true);
  const canSubmit = id
    ? hasPermission("issue:edit")
    : hasPermission("issue:create");
  useEffect(() => {
    if (id)
      issueApi.get(id).then((i) =>
        form.setFieldsValue({
          ...i,
          expectedFinishTime: i.expectedFinishTime
            ? dayjs(i.expectedFinishTime)
            : null,
        }),
      );
  }, [id]);
  useEffect(() => {
    if (!id && user?.displayName) {
      form.setFieldValue("createdBy", user.displayName);
    }
  }, [form, id, user?.displayName]);
  if (!canSubmit) {
    return (
      <div className="page forbidden-page">
        <h1>无权操作</h1>
        <p>当前账号没有{id ? "编辑问题" : "新增问题"}权限。</p>
      </div>
    );
  }
  const submit = async (v: any) => {
    setLoading(true);
    try {
      const body = {
        ...v,
        expectedFinishTime: v.expectedFinishTime?.format("YYYY-MM-DDTHH:mm:ss"),
      };
      const issue = id
        ? await issueApi.update(Number(id), body)
        : await issueApi.create(body);
      message.success(id ? "问题已更新" : "问题已创建");
      nav(`/issues/${issue.id}`);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  };
  return (
    <div className="page narrow-page">
      <div className="form-heading">
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => nav(-1)}
        />
        <div>
          <h1>{id ? "编辑问题" : "新增问题"}</h1>
          <p>记录完整上下文，便于责任人快速定位并闭环</p>
        </div>
      </div>
      <Form
        form={form}
        layout="vertical"
        initialValues={{
          priority: "P2",
          status: "待处理",
          createdBy: user?.displayName || "当前用户",
        }}
        onFinish={submit}
      >
        <div className="form-surface">
          <h2>基础信息</h2>
          <Form.Item
            name="title"
            label="问题标题"
            rules={[{ required: true, message: "请输入问题标题" }]}
          >
            <Input placeholder="用一句话描述问题和影响" />
          </Form.Item>
          <Form.Item name="description" label="问题描述">
            <TextArea rows={4} />
          </Form.Item>
          <div className="form-grid">
            <Form.Item name="source" label="问题来源">
              <Select
                loading={dictionaryLoading}
                options={dictionaryOptions.sources}
              />
            </Form.Item>
            <Form.Item name="businessScene" label="业务场景">
              <Select
                showSearch
                loading={dictionaryLoading}
                options={dictionaryOptions.businessScenes}
              />
            </Form.Item>
            <Form.Item name="issueType" label="问题类型">
              <Select
                loading={dictionaryLoading}
                options={dictionaryOptions.issueTypes}
              />
            </Form.Item>
            <Form.Item name="impactScope" label="影响范围">
              <Select
                loading={dictionaryLoading}
                options={dictionaryOptions.impactScopes}
              />
            </Form.Item>
          </div>
          <Form.Item name="customerImpact" label="客户影响说明">
            <TextArea rows={3} />
          </Form.Item>
          <Form.Item name="reproduceSteps" label="复现步骤">
            <TextArea
              rows={4}
              placeholder={"1. 进入页面\n2. 执行操作\n3. 观察结果"}
            />
          </Form.Item>
          <h2>责任与计划</h2>
          <div className="form-grid">
            <Form.Item name="priority" label="优先级">
              <Select options={options(["P0", "P1", "P2", "P3"])} />
            </Form.Item>
            <Form.Item name="responsibleDepartment" label="责任部门">
              <Select
                options={options([
                  "产品部",
                  "技术部",
                  "数据部",
                  "交易平台",
                  "运营部",
                ])}
              />
            </Form.Item>
            <Form.Item name="responsiblePerson" label="责任人">
              <Input />
            </Form.Item>
            <Form.Item name="expectedFinishTime" label="预计完成时间">
              <DatePicker showTime style={{ width: "100%" }} />
            </Form.Item>
          </div>
          <Form.Item name="tapdUrl" label="TAPD 链接">
            <Input placeholder="https://tapd..." />
          </Form.Item>
          <Form.Item name="attachmentUrl" label="附件地址">
            <Input placeholder="第一版使用文本地址" />
          </Form.Item>
          <div className="form-actions">
            <Button onClick={() => nav(-1)}>取消</Button>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              icon={<SaveOutlined />}
            >
              保存问题
            </Button>
          </div>
        </div>
      </Form>
    </div>
  );
}
