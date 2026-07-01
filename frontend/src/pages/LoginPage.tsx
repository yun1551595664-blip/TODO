import { LockOutlined, UserOutlined } from "@ant-design/icons";
import { Button, Form, Input, message } from "antd";
import { useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";

type LoginValues = {
  username: string;
  password: string;
};

export default function LoginPage() {
  const { user, login } = useAuth();
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string } } | null)?.from
    ?.pathname;

  if (user) return <Navigate to={from || "/"} replace />;

  const submit = async (values: LoginValues) => {
    setLoading(true);
    try {
      await login(values.username, values.password);
      message.success("已登录");
      navigate(from || "/", { replace: true });
    } catch (error) {
      message.error(error instanceof Error ? error.message : "登录失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="login-page">
      <section className="login-card">
        <div className="login-brand">
          <span>IssueOps</span>
          <b>产品与业务问题治理平台</b>
          <p>登录后进入内部看板，按角色控制问题流转、字段配置和 AI 操作。</p>
        </div>
        <Form
          layout="vertical"
          initialValues={{ username: "admin", password: "admin123" }}
          onFinish={submit}
        >
          <Form.Item
            name="username"
            label="账号"
            rules={[{ required: true, message: "请输入账号" }]}
          >
            <Input prefix={<UserOutlined />} placeholder="admin" size="large" />
          </Form.Item>
          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: "请输入密码" }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              placeholder="admin123"
              size="large"
            />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            loading={loading}
            block
            size="large"
          >
            登录系统
          </Button>
        </Form>
        <div className="login-demo">
          <span>演示账号</span>
          <p>管理员 admin/admin123 · 产品 product/product123 · 技术 tech/tech123</p>
        </div>
      </section>
    </main>
  );
}
