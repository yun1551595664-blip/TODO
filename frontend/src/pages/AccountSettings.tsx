import {
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from "@ant-design/icons";
import {
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  message,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import dayjs from "dayjs";
import { useEffect, useMemo, useState } from "react";
import { issueApi } from "../api";
import type {
  Account,
  AccountDataScope,
  AccountPayload,
  AccountRole,
} from "../types";

const roleOptions: { value: AccountRole; label: string; color: string }[] = [
  { value: "ADMIN", label: "管理员", color: "purple" },
  { value: "PRODUCT", label: "产品", color: "blue" },
  { value: "TECH", label: "技术", color: "cyan" },
  { value: "CS", label: "客服", color: "orange" },
  { value: "VIEWER", label: "观察员", color: "default" },
];

const dataScopeOptions: {
  value: AccountDataScope;
  label: string;
  description: string;
}[] = [
  { value: "ALL", label: "全部数据", description: "可查看全公司问题数据" },
  { value: "DEPARTMENT", label: "本部门", description: "责任部门或本人相关问题" },
  { value: "OWN", label: "我创建的", description: "仅查看本人创建的问题" },
  { value: "ASSIGNED", label: "指派给我", description: "仅查看责任人为本人的问题" },
];

type FormValues = AccountPayload;

export default function AccountSettings() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Account>();
  const [form] = Form.useForm<FormValues>();

  const load = async () => {
    setLoading(true);
    try {
      setAccounts(await issueApi.accounts());
    } catch (error) {
      message.error(error instanceof Error ? error.message : "账号列表加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const openCreate = () => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({ role: "VIEWER", dataScope: "DEPARTMENT", enabled: true });
    setModalOpen(true);
  };

  const openEdit = (account: Account) => {
    setEditing(account);
    form.resetFields();
    form.setFieldsValue({
      username: account.username,
      displayName: account.displayName,
      role: account.role,
      department: account.department,
      dataScope: account.dataScope,
      enabled: account.enabled,
      ssoSubject: account.ssoSubject,
    });
    setModalOpen(true);
  };

  const submit = async (values: FormValues) => {
    setSaving(true);
    try {
      const payload = {
        ...values,
        enabled: values.enabled ?? true,
        password: values.password?.trim() || undefined,
      };
      if (editing) {
        await issueApi.accountUpdate(editing.id, payload);
      } else {
        await issueApi.accountCreate(payload);
      }
      message.success(editing ? "账号已更新" : "账号已新增");
      setModalOpen(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const toggleEnabled = async (account: Account) => {
    try {
      await issueApi.accountEnabled(account.id, !account.enabled);
      message.success(account.enabled ? "账号已停用" : "账号已启用");
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "状态更新失败");
    }
  };

  const columns = useMemo<ColumnsType<Account>>(
    () => [
      {
        title: "账号",
        dataIndex: "username",
        width: 150,
        render: (value, record) => (
          <span className="field-name">
            <b>{value}</b>
            {!record.enabled && <Tag>停用</Tag>}
          </span>
        ),
      },
      { title: "显示名", dataIndex: "displayName", width: 150 },
      {
        title: "角色",
        dataIndex: "role",
        width: 120,
        render: (value) => {
          const role = roleOptions.find((item) => item.value === value);
          return <Tag color={role?.color}>{role?.label || value}</Tag>;
        },
      },
      {
        title: "部门",
        dataIndex: "department",
        width: 140,
        render: (value) => value || <span className="muted">未配置</span>,
      },
      {
        title: "数据范围",
        dataIndex: "dataScope",
        width: 130,
        render: (value) => {
          const scope = dataScopeOptions.find((item) => item.value === value);
          return <Tag color={value === "ALL" ? "purple" : "geekblue"}>{scope?.label || value}</Tag>;
        },
      },
      {
        title: "SSO 标识",
        dataIndex: "ssoSubject",
        ellipsis: true,
        render: (value) => value || <span className="muted">未绑定</span>,
      },
      {
        title: "最近登录",
        dataIndex: "lastLoginAt",
        width: 150,
        render: (value) =>
          value ? dayjs(value).format("MM-DD HH:mm") : <span className="muted">--</span>,
      },
      {
        title: "状态",
        dataIndex: "enabled",
        width: 100,
        render: (enabled, record) => (
          <Switch
            size="small"
            checked={enabled}
            checkedChildren="启用"
            unCheckedChildren="停用"
            onChange={() => toggleEnabled(record)}
          />
        ),
      },
      {
        title: "操作",
        width: 90,
        render: (_, record) => (
          <Button
            type="text"
            icon={<EditOutlined />}
            onClick={() => openEdit(record)}
          />
        ),
      },
    ],
    [accounts],
  );

  return (
    <div className="page field-settings-page">
      <div className="list-heading">
        <div>
          <h1>账号管理</h1>
          <p>维护内部账号、角色权限、启停状态和企业 SSO 绑定标识</p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增账号
          </Button>
        </Space>
      </div>

      <div className="settings-surface account-settings-surface">
        <div className="account-security-note">
          <SafetyCertificateOutlined />
          <span>密码使用 PBKDF2 哈希保存；数据范围由后端统一过滤，停用账号会立即失去登录和接口访问能力。</span>
        </div>
        <Table
          rowKey="id"
          size="middle"
          loading={loading}
          columns={columns}
          dataSource={accounts}
          pagination={false}
        />
      </div>

      <Modal
        title={editing ? "编辑账号" : "新增账号"}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        okText="保存"
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ role: "VIEWER", dataScope: "DEPARTMENT", enabled: true }}
          onFinish={submit}
        >
          <Form.Item
            name="username"
            label="账号"
            rules={[{ required: !editing, message: "请输入账号" }]}
          >
            <Input disabled={!!editing} placeholder="例如：ops" />
          </Form.Item>
          <Form.Item
            name="displayName"
            label="显示名称"
            rules={[{ required: true, message: "请输入显示名称" }]}
          >
            <Input placeholder="例如：运营负责人" />
          </Form.Item>
          <Form.Item
            name="password"
            label={editing ? "重置密码" : "初始密码"}
            rules={[
              { required: !editing, message: "请输入初始密码" },
              { min: 8, message: "密码至少 8 位" },
            ]}
          >
            <Input.Password
              placeholder={editing ? "不填写则不修改密码" : "至少 8 位"}
            />
          </Form.Item>
          <Form.Item
            name="role"
            label="角色"
            rules={[{ required: true, message: "请选择角色" }]}
          >
            <Select options={roleOptions.map(({ value, label }) => ({ value, label }))} />
          </Form.Item>
          <Form.Item
            name="department"
            label="所属部门"
            rules={[{ required: true, message: "请输入所属部门" }]}
          >
            <Input placeholder="例如：产品部、技术部、客服部" />
          </Form.Item>
          <Form.Item
            name="dataScope"
            label="数据范围"
            rules={[{ required: true, message: "请选择数据范围" }]}
          >
            <Select
              options={dataScopeOptions.map(({ value, label, description }) => ({
                value,
                label: `${label} · ${description}`,
              }))}
            />
          </Form.Item>
          <Form.Item name="ssoSubject" label="SSO 绑定标识">
            <Input placeholder="可选，例如企业身份唯一 ID" />
          </Form.Item>
          <Form.Item name="enabled" label="是否启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
