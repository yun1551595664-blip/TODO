import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  message,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { useEffect, useMemo, useState } from "react";
import { issueApi } from "../api";
import {
  dictionaryTypeLabels,
  useDictionaryOptions,
} from "../hooks/useDictionaryOptions";
import type { DictionaryItem, DictionaryType } from "../types";

const dictionaryTypes = Object.keys(dictionaryTypeLabels) as DictionaryType[];

type FormValues = {
  code?: string;
  name: string;
  description?: string;
  sortOrder?: number;
  enabled?: boolean;
};

export default function FieldSettings() {
  const [activeType, setActiveType] = useState<DictionaryType>("ISSUE_SOURCE");
  const { items, loading, reload } = useDictionaryOptions(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<DictionaryItem>();
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<FormValues>();

  const currentItems = items[activeType] || [];

  const openCreate = () => {
    setEditing(undefined);
    form.resetFields();
    form.setFieldsValue({ enabled: true, sortOrder: nextSortOrder(currentItems) });
    setModalOpen(true);
  };

  const openEdit = (item: DictionaryItem) => {
    setEditing(item);
    form.resetFields();
    form.setFieldsValue({
      code: item.code,
      name: item.name,
      description: item.description,
      sortOrder: item.sortOrder,
      enabled: item.enabled,
    });
    setModalOpen(true);
  };

  const submit = async (values: FormValues) => {
    setSaving(true);
    try {
      const payload = {
        ...values,
        dictType: editing?.dictType || activeType,
        enabled: values.enabled ?? true,
        name: values.name,
      };
      if (editing) {
        await issueApi.dictionaryUpdate(editing.id, payload);
      } else {
        await issueApi.dictionaryCreate(payload);
      }
      message.success(editing ? "字段选项已更新" : "字段选项已新增");
      setModalOpen(false);
      await reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const toggleEnabled = async (item: DictionaryItem) => {
    try {
      await issueApi.dictionaryEnabled(item.id, !item.enabled);
      message.success(item.enabled ? "已停用" : "已启用");
      await reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "状态更新失败");
    }
  };

  const remove = async (item: DictionaryItem) => {
    try {
      await issueApi.dictionaryRemove(item.id);
      message.success("字段选项已删除");
      await reload();
    } catch (error) {
      message.error(error instanceof Error ? error.message : "删除失败");
    }
  };

  const columns = useMemo<ColumnsType<DictionaryItem>>(
    () => [
      {
        title: "名称",
        dataIndex: "name",
        width: 180,
        render: (value, record) => (
          <span className="field-name">
            <b>{value}</b>
            {record.systemBuiltin && <Tag color="purple">内置</Tag>}
          </span>
        ),
      },
      { title: "编码", dataIndex: "code", width: 180 },
      {
        title: "描述",
        dataIndex: "description",
        ellipsis: true,
        render: (value) => value || "-",
      },
      {
        title: "排序",
        dataIndex: "sortOrder",
        width: 90,
      },
      {
        title: "状态",
        dataIndex: "enabled",
        width: 110,
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
        title: "引用",
        width: 90,
        render: (_, record) => <UsageBadge item={record} />,
      },
      {
        title: "操作",
        width: 150,
        render: (_, record) => (
          <Space>
            <Button
              type="text"
              icon={<EditOutlined />}
              onClick={() => openEdit(record)}
            />
            <Popconfirm
              title="确认删除该字段选项？"
              description="已被问题引用的选项不能删除，只能停用。"
              onConfirm={() => remove(record)}
            >
              <Button type="text" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        ),
      },
    ],
    [items],
  );

  return (
    <div className="page field-settings-page">
      <div className="list-heading">
        <div>
          <h1>字段配置</h1>
          <p>维护问题来源、业务场景、问题类型和影响范围，历史引用不受停用影响</p>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={reload}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            新增选项
          </Button>
        </Space>
      </div>

      <div className="settings-surface">
        <Tabs
          activeKey={activeType}
          onChange={(key) => setActiveType(key as DictionaryType)}
          items={dictionaryTypes.map((type) => ({
            key: type,
            label: dictionaryTypeLabels[type],
            children: (
              <Table
                rowKey="id"
                size="middle"
                loading={loading}
                columns={columns}
                dataSource={items[type] || []}
                pagination={false}
              />
            ),
          }))}
        />
      </div>

      <Modal
        title={editing ? "编辑字段选项" : `新增${dictionaryTypeLabels[activeType]}`}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saving}
        okText="保存"
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ enabled: true }}
          onFinish={submit}
        >
          <Form.Item
            name="name"
            label="展示名称"
            rules={[{ required: true, message: "请输入展示名称" }]}
          >
            <Input placeholder="例如：订单支付" />
          </Form.Item>
          <Form.Item name="code" label="稳定编码">
            <Input placeholder="不填则自动生成；例如 ORDER_PAYMENT" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="说明该选项适用范围" />
          </Form.Item>
          <div className="field-settings-form-grid">
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} step={10} style={{ width: "100%" }} />
            </Form.Item>
            <Form.Item name="enabled" label="是否启用" valuePropName="checked">
              <Switch checkedChildren="启用" unCheckedChildren="停用" />
            </Form.Item>
          </div>
        </Form>
      </Modal>
    </div>
  );
}

function UsageBadge({ item }: { item: DictionaryItem }) {
  const [count, setCount] = useState<number>();

  useEffect(() => {
    let active = true;
    issueApi
      .dictionaryUsage(item.id)
      .then((usage) => {
        if (active) setCount(usage.issueCount);
      })
      .catch(() => {
        if (active) setCount(undefined);
      });
    return () => {
      active = false;
    };
  }, [item.id]);

  if (count === undefined) return <span className="muted">--</span>;
  return count > 0 ? <Tag color="orange">{count} 条</Tag> : <Tag>0 条</Tag>;
}

function nextSortOrder(items: DictionaryItem[]) {
  return Math.max(0, ...items.map((item) => item.sortOrder || 0)) + 10;
}
