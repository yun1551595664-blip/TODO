import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from "@ant-design/icons";
import {
  Button,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
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
  DepartmentConfig,
  DepartmentPayload,
  RoleConfig,
  RolePayload,
} from "../types";

const roleColorMap: Record<string, string> = {
  ADMIN: "purple",
  PRODUCT: "blue",
  TECH: "cyan",
  CS: "orange",
  VIEWER: "default",
};

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

type AccountFormValues = AccountPayload;
type RoleFormValues = RolePayload;
type DepartmentFormValues = DepartmentPayload;

export default function AccountSettings() {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [roles, setRoles] = useState<RoleConfig[]>([]);
  const [departments, setDepartments] = useState<DepartmentConfig[]>([]);
  const [permissionLabels, setPermissionLabels] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [roleSaving, setRoleSaving] = useState(false);
  const [departmentSaving, setDepartmentSaving] = useState(false);
  const [accountModalOpen, setAccountModalOpen] = useState(false);
  const [roleModalOpen, setRoleModalOpen] = useState(false);
  const [departmentModalOpen, setDepartmentModalOpen] = useState(false);
  const [editingAccount, setEditingAccount] = useState<Account>();
  const [editingRole, setEditingRole] = useState<RoleConfig>();
  const [editingDepartment, setEditingDepartment] = useState<DepartmentConfig>();
  const [accountForm] = Form.useForm<AccountFormValues>();
  const [roleForm] = Form.useForm<RoleFormValues>();
  const [departmentForm] = Form.useForm<DepartmentFormValues>();

  const roleMap = useMemo(
    () => new Map(roles.map((role) => [role.code, role])),
    [roles],
  );

  const roleSelectOptions = useMemo(() => {
    const currentRole = editingAccount?.role;
    return roles
      .filter((role) => role.enabled || role.code === currentRole)
      .map((role) => ({
        value: role.code,
        label: `${role.name}（${role.code}）${role.enabled ? "" : " - 已停用"}`,
      }));
  }, [editingAccount?.role, roles]);

  const permissionOptions = useMemo(
    () =>
      Object.entries(permissionLabels).map(([value, label]) => ({
        value,
        label: `${label}（${value}）`,
      })),
    [permissionLabels],
  );

  const departmentOptions = useMemo(
    () =>
      departments
        .filter((department) => department.enabled)
        .map((department) => ({
          value: department.name,
          label: department.name,
        })),
    [departments],
  );

  const load = async () => {
    setLoading(true);
    try {
      const [accountData, roleData, departmentData, permissions] = await Promise.all([
        issueApi.accounts(),
        issueApi.roles(),
        issueApi.departments(),
        issueApi.rolePermissions(),
      ]);
      setAccounts(accountData);
      setRoles(roleData);
      setDepartments(departmentData);
      setPermissionLabels(permissions);
    } catch (error) {
      message.error(error instanceof Error ? error.message : "账号配置加载失败");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const defaultAccountRole = () =>
    roles.find((role) => role.enabled && role.code === "VIEWER") ||
    roles.find((role) => role.enabled);

  const openCreateAccount = () => {
    const role = defaultAccountRole();
    setEditingAccount(undefined);
    accountForm.resetFields();
    accountForm.setFieldsValue({
      role: role?.code || "VIEWER",
      department: role?.defaultDepartment,
      dataScope: role?.defaultDataScope || "DEPARTMENT",
      enabled: true,
    });
    setAccountModalOpen(true);
  };

  const openEditAccount = (account: Account) => {
    setEditingAccount(account);
    accountForm.resetFields();
    accountForm.setFieldsValue({
      username: account.username,
      displayName: account.displayName,
      role: account.role,
      department: account.department,
      dataScope: account.dataScope,
      enabled: account.enabled,
      ssoSubject: account.ssoSubject,
    });
    setAccountModalOpen(true);
  };

  const onAccountRoleChange = (roleCode: string) => {
    const role = roleMap.get(roleCode);
    if (!role) return;
    accountForm.setFieldsValue({
      department: role.defaultDepartment,
      dataScope: role.defaultDataScope,
    });
  };

  const submitAccount = async (values: AccountFormValues) => {
    setSaving(true);
    try {
      const payload = {
        ...values,
        enabled: values.enabled ?? true,
        password: values.password?.trim() || undefined,
      };
      if (editingAccount) {
        await issueApi.accountUpdate(editingAccount.id, payload);
      } else {
        await issueApi.accountCreate(payload);
      }
      message.success(editingAccount ? "账号已更新" : "账号已新增");
      setAccountModalOpen(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const toggleAccountEnabled = async (account: Account) => {
    try {
      await issueApi.accountEnabled(account.id, !account.enabled);
      message.success(account.enabled ? "账号已停用" : "账号已启用");
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "状态更新失败");
    }
  };

  const openCreateRole = () => {
    setEditingRole(undefined);
    roleForm.resetFields();
    roleForm.setFieldsValue({
      permissions: [],
      defaultDataScope: "DEPARTMENT",
      enabled: true,
      sortOrder: nextSortOrder(roles),
    });
    setRoleModalOpen(true);
  };

  const openEditRole = (role: RoleConfig) => {
    setEditingRole(role);
    roleForm.resetFields();
    roleForm.setFieldsValue({
      code: role.code,
      name: role.name,
      description: role.description,
      permissions: role.permissions,
      defaultDataScope: role.defaultDataScope,
      defaultDepartment: role.defaultDepartment,
      enabled: role.enabled,
      sortOrder: role.sortOrder,
    });
    setRoleModalOpen(true);
  };

  const submitRole = async (values: RoleFormValues) => {
    setRoleSaving(true);
    try {
      const payload = {
        ...values,
        code: editingRole?.code || values.code?.trim().toUpperCase(),
        enabled: values.enabled ?? true,
        permissions: values.permissions || [],
      };
      if (editingRole) {
        await issueApi.roleUpdate(editingRole.id, payload);
      } else {
        await issueApi.roleCreate(payload);
      }
      message.success(editingRole ? "角色已更新" : "角色已新增");
      setRoleModalOpen(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "角色保存失败");
    } finally {
      setRoleSaving(false);
    }
  };

  const toggleRoleEnabled = async (role: RoleConfig) => {
    try {
      await issueApi.roleEnabled(role.id, !role.enabled);
      message.success(role.enabled ? "角色已停用" : "角色已启用");
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "角色状态更新失败");
    }
  };

  const removeRole = async (role: RoleConfig) => {
    try {
      await issueApi.roleRemove(role.id);
      message.success("角色已删除");
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "角色删除失败");
    }
  };

  const openCreateDepartment = () => {
    setEditingDepartment(undefined);
    departmentForm.resetFields();
    departmentForm.setFieldsValue({
      enabled: true,
      sortOrder: nextDepartmentSortOrder(departments),
    });
    setDepartmentModalOpen(true);
  };

  const openEditDepartment = (department: DepartmentConfig) => {
    setEditingDepartment(department);
    departmentForm.resetFields();
    departmentForm.setFieldsValue({
      code: department.code,
      name: department.name,
      parentCode: department.parentCode,
      enabled: department.enabled,
      sortOrder: department.sortOrder,
    });
    setDepartmentModalOpen(true);
  };

  const submitDepartment = async (values: DepartmentFormValues) => {
    setDepartmentSaving(true);
    try {
      const payload = {
        ...values,
        code: editingDepartment?.code || values.code?.trim().toUpperCase(),
        enabled: values.enabled ?? true,
      };
      if (editingDepartment) {
        await issueApi.departmentUpdate(editingDepartment.id, payload);
      } else {
        await issueApi.departmentCreate(payload);
      }
      message.success(editingDepartment ? "部门已更新" : "部门已新增");
      setDepartmentModalOpen(false);
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "部门保存失败");
    } finally {
      setDepartmentSaving(false);
    }
  };

  const toggleDepartmentEnabled = async (department: DepartmentConfig) => {
    try {
      await issueApi.departmentEnabled(department.id, !department.enabled);
      message.success(department.enabled ? "部门已停用" : "部门已启用");
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "部门状态更新失败");
    }
  };

  const removeDepartment = async (department: DepartmentConfig) => {
    try {
      await issueApi.departmentRemove(department.id);
      message.success("部门已删除");
      await load();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "部门删除失败");
    }
  };

  const accountColumns = useMemo<ColumnsType<Account>>(
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
      { title: "显示名称", dataIndex: "displayName", width: 150 },
      {
        title: "角色",
        dataIndex: "role",
        width: 150,
        render: (value) => {
          const role = roleMap.get(value);
          return (
            <Tag color={roleColor(value)}>
              {role ? `${role.name} · ${role.code}` : value}
            </Tag>
          );
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
            onChange={() => toggleAccountEnabled(record)}
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
            onClick={() => openEditAccount(record)}
          />
        ),
      },
    ],
    [roleMap],
  );

  const roleColumns = useMemo<ColumnsType<RoleConfig>>(
    () => [
      {
        title: "角色",
        dataIndex: "name",
        width: 220,
        render: (value, record) => (
          <span className="field-name">
            <b>{value}</b>
            <Tag color={roleColor(record.code)}>{record.code}</Tag>
            {record.systemBuiltin && <Tag color="purple">内置</Tag>}
          </span>
        ),
      },
      {
        title: "权限",
        dataIndex: "permissions",
        render: (permissions: string[]) => (
          <Space size={[4, 4]} wrap>
            {permissions.length === 0 ? (
              <span className="muted">只读</span>
            ) : (
              permissions.map((permission) => (
                <Tag key={permission}>{permissionLabels[permission] || permission}</Tag>
              ))
            )}
          </Space>
        ),
      },
      {
        title: "默认范围",
        dataIndex: "defaultDataScope",
        width: 130,
        render: (value) => {
          const scope = dataScopeOptions.find((item) => item.value === value);
          return scope?.label || value;
        },
      },
      {
        title: "默认部门",
        dataIndex: "defaultDepartment",
        width: 130,
        render: (value) => value || <span className="muted">未配置</span>,
      },
      {
        title: "账号数",
        dataIndex: "accountCount",
        width: 90,
      },
      {
        title: "状态",
        dataIndex: "enabled",
        width: 100,
        render: (enabled, record) => (
          <Switch
            size="small"
            checked={enabled}
            disabled={record.systemBuiltin || record.accountCount > 0}
            checkedChildren="启用"
            unCheckedChildren="停用"
            onChange={() => toggleRoleEnabled(record)}
          />
        ),
      },
      {
        title: "操作",
        width: 130,
        render: (_, record) => (
          <Space>
            <Button type="text" icon={<EditOutlined />} onClick={() => openEditRole(record)} />
            <Popconfirm
              title="确认删除该角色？"
              description="已有账号使用或内置系统角色不能删除。"
              onConfirm={() => removeRole(record)}
              disabled={record.systemBuiltin || record.accountCount > 0}
            >
              <Button
                type="text"
                danger
                disabled={record.systemBuiltin || record.accountCount > 0}
                icon={<DeleteOutlined />}
              />
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [permissionLabels],
  );

  const departmentColumns = useMemo<ColumnsType<DepartmentConfig>>(
    () => [
      {
        title: "部门",
        dataIndex: "name",
        width: 220,
        render: (value, record) => (
          <span className="field-name">
            <b>{value}</b>
            <Tag color={record.source === "SYNC" ? "blue" : "default"}>
              {record.source || "MANUAL"}
            </Tag>
            {!record.enabled && <Tag>停用</Tag>}
          </span>
        ),
      },
      { title: "编码", dataIndex: "code", width: 150 },
      {
        title: "上级编码",
        dataIndex: "parentCode",
        width: 150,
        render: (value) => value || <span className="muted">无</span>,
      },
      {
        title: "账号引用",
        dataIndex: "accountCount",
        width: 100,
        render: (value) => value || 0,
      },
      {
        title: "角色引用",
        dataIndex: "roleCount",
        width: 100,
        render: (value) => value || 0,
      },
      { title: "排序", dataIndex: "sortOrder", width: 90 },
      {
        title: "状态",
        dataIndex: "enabled",
        width: 100,
        render: (enabled, record) => {
          const inUse = (record.accountCount || 0) + (record.roleCount || 0) > 0;
          return (
            <Switch
              size="small"
              checked={enabled}
              disabled={enabled && inUse}
              checkedChildren="启用"
              unCheckedChildren="停用"
              onChange={() => toggleDepartmentEnabled(record)}
            />
          );
        },
      },
      {
        title: "操作",
        width: 130,
        render: (_, record) => {
          const inUse = (record.accountCount || 0) + (record.roleCount || 0) > 0;
          return (
            <Space>
              <Button
                type="text"
                icon={<EditOutlined />}
                onClick={() => openEditDepartment(record)}
              />
              <Popconfirm
                title="确认删除该部门？"
                description="已有账号或角色引用的部门不能删除。"
                onConfirm={() => removeDepartment(record)}
                disabled={inUse}
              >
                <Button
                  type="text"
                  danger
                  disabled={inUse}
                  icon={<DeleteOutlined />}
                />
              </Popconfirm>
            </Space>
          );
        },
      },
    ],
    [],
  );

  return (
    <div className="page field-settings-page">
      <div className="list-heading">
        <div>
          <h1>账号管理</h1>
          <p>维护内部账号、角色权限、部门、数据范围、启停状态和企业 SSO 绑定标识</p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={load}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateAccount}>
            新增账号
          </Button>
        </Space>
      </div>

      <div className="settings-surface account-settings-surface">
        <div className="account-security-note">
          <SafetyCertificateOutlined />
          <span>
            密码使用 PBKDF2 哈希保存；角色权限由后端统一校验；数据范围由后端统一过滤。
          </span>
        </div>
        <Tabs
          items={[
            {
              key: "accounts",
              label: "账号列表",
              children: (
                <Table
                  rowKey="id"
                  size="middle"
                  loading={loading}
                  columns={accountColumns}
                  dataSource={accounts}
                  pagination={false}
                />
              ),
            },
            {
              key: "roles",
              label: "角色配置",
              children: (
                <div className="role-config-panel">
                  <div className="role-config-toolbar">
                    <span className="muted">
                      角色会影响账号可执行的操作；内置角色可调整权限和默认值，但不能删除或停用。
                    </span>
                    <Button type="primary" icon={<PlusOutlined />} onClick={openCreateRole}>
                      新增角色
                    </Button>
                  </div>
                  <Table
                    rowKey="id"
                    size="middle"
                    loading={loading}
                    columns={roleColumns}
                    dataSource={roles}
                    pagination={false}
                  />
                </div>
              ),
            },
            {
              key: "departments",
              label: "部门配置",
              children: (
                <div className="role-config-panel">
                  <div className="role-config-toolbar">
                    <span className="muted">
                      部门用于账号归属、角色默认部门和数据范围过滤；已被引用的部门不能停用或删除。
                    </span>
                    <Button type="primary" icon={<PlusOutlined />} onClick={openCreateDepartment}>
                      新增部门
                    </Button>
                  </div>
                  <Table
                    rowKey="id"
                    size="middle"
                    loading={loading}
                    columns={departmentColumns}
                    dataSource={departments}
                    pagination={false}
                  />
                </div>
              ),
            },
          ]}
        />
      </div>

      <Modal
        title={editingAccount ? "编辑账号" : "新增账号"}
        open={accountModalOpen}
        onCancel={() => setAccountModalOpen(false)}
        onOk={() => accountForm.submit()}
        confirmLoading={saving}
        okText="保存"
      >
        <Form
          form={accountForm}
          layout="vertical"
          initialValues={{ role: "VIEWER", dataScope: "DEPARTMENT", enabled: true }}
          onFinish={submitAccount}
        >
          <Form.Item
            name="username"
            label="账号"
            rules={[{ required: !editingAccount, message: "请输入账号" }]}
          >
            <Input disabled={!!editingAccount} placeholder="例如：ops" />
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
            label={editingAccount ? "重置密码" : "初始密码"}
            rules={[
              { required: !editingAccount, message: "请输入初始密码" },
              { min: 8, message: "密码至少 8 位" },
            ]}
          >
            <Input.Password placeholder={editingAccount ? "不填写则不修改密码" : "至少 8 位"} />
          </Form.Item>
          <Form.Item
            name="role"
            label="角色"
            rules={[{ required: true, message: "请选择角色" }]}
          >
            <Select options={roleSelectOptions} onChange={onAccountRoleChange} />
          </Form.Item>
          <Form.Item
            name="department"
            label="所属部门"
            rules={[{ required: true, message: "请输入所属部门" }]}
          >
            <Select
              showSearch
              options={departmentOptions}
              placeholder="请选择部门"
              optionFilterProp="label"
            />
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

      <Modal
        title={editingRole ? "编辑角色" : "新增角色"}
        open={roleModalOpen}
        onCancel={() => setRoleModalOpen(false)}
        onOk={() => roleForm.submit()}
        confirmLoading={roleSaving}
        okText="保存"
        width={720}
      >
        <Form
          form={roleForm}
          layout="vertical"
          initialValues={{ permissions: [], defaultDataScope: "DEPARTMENT", enabled: true }}
          onFinish={submitRole}
        >
          <div className="field-settings-form-grid">
            <Form.Item
              name="code"
              label="角色编码"
              rules={[{ required: !editingRole, message: "请输入角色编码" }]}
            >
              <Input
                disabled={!!editingRole}
                placeholder="例如：OPS_MANAGER"
              />
            </Form.Item>
            <Form.Item
              name="name"
              label="角色名称"
              rules={[{ required: true, message: "请输入角色名称" }]}
            >
              <Input placeholder="例如：运营负责人" />
            </Form.Item>
          </div>
          <Form.Item name="description" label="角色说明">
            <Input.TextArea rows={2} placeholder="说明该角色适用对象和职责边界" />
          </Form.Item>
          <Form.Item name="permissions" label="权限点">
            <Checkbox.Group className="role-permission-grid" options={permissionOptions} />
          </Form.Item>
          <div className="field-settings-form-grid">
            <Form.Item
              name="defaultDataScope"
              label="默认数据范围"
              rules={[{ required: true, message: "请选择默认数据范围" }]}
            >
              <Select
                options={dataScopeOptions.map(({ value, label }) => ({
                  value,
                  label,
                }))}
              />
            </Form.Item>
            <Form.Item name="defaultDepartment" label="默认部门">
              <Select
                allowClear
                showSearch
                options={departmentOptions}
                placeholder="选择该角色的默认部门"
                optionFilterProp="label"
              />
            </Form.Item>
          </div>
          <div className="field-settings-form-grid">
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} step={10} style={{ width: "100%" }} />
            </Form.Item>
            <Form.Item name="enabled" label="是否启用" valuePropName="checked">
              <Switch
                disabled={!!editingRole?.systemBuiltin}
                checkedChildren="启用"
                unCheckedChildren="停用"
              />
            </Form.Item>
          </div>
        </Form>
      </Modal>

      <Modal
        title={editingDepartment ? "编辑部门" : "新增部门"}
        open={departmentModalOpen}
        onCancel={() => setDepartmentModalOpen(false)}
        onOk={() => departmentForm.submit()}
        confirmLoading={departmentSaving}
        okText="保存"
        width={640}
      >
        <Form
          form={departmentForm}
          layout="vertical"
          initialValues={{ enabled: true }}
          onFinish={submitDepartment}
        >
          <div className="field-settings-form-grid">
            <Form.Item
              name="code"
              label="部门编码"
              rules={[{ required: !editingDepartment, message: "请输入部门编码" }]}
            >
              <Input
                disabled={!!editingDepartment}
                placeholder="例如：OPS"
              />
            </Form.Item>
            <Form.Item
              name="name"
              label="部门名称"
              rules={[{ required: true, message: "请输入部门名称" }]}
            >
              <Input placeholder="例如：运营部" />
            </Form.Item>
          </div>
          <div className="field-settings-form-grid">
            <Form.Item name="parentCode" label="上级部门">
              <Select
                allowClear
                showSearch
                options={departments
                  .filter((department) => department.id !== editingDepartment?.id)
                  .map((department) => ({
                    value: department.code,
                    label: `${department.name} · ${department.code}`,
                  }))}
                placeholder="可选"
                optionFilterProp="label"
              />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} step={10} style={{ width: "100%" }} />
            </Form.Item>
          </div>
          <Form.Item name="enabled" label="是否启用" valuePropName="checked">
            <Switch checkedChildren="启用" unCheckedChildren="停用" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}

function roleColor(code: string) {
  return roleColorMap[code] || "geekblue";
}

function nextSortOrder(roles: RoleConfig[]) {
  return roles.reduce((max, role) => Math.max(max, role.sortOrder || 0), 0) + 10;
}

function nextDepartmentSortOrder(departments: DepartmentConfig[]) {
  return departments.reduce((max, department) => Math.max(max, department.sortOrder || 0), 0) + 10;
}
