import {
  BarChartOutlined,
  BulbOutlined,
  FieldTimeOutlined,
  LockOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  StarFilled,
  UnorderedListOutlined,
  UserOutlined,
} from "@ant-design/icons";
import { Button, Checkbox, Form, Input, message } from "antd";
import { useEffect, useState } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import { issueApi } from "../api";
import { useAuth } from "../auth";
import type { SsoConfig } from "../types";

type LoginValues = {
  username: string;
  password: string;
};

const capabilities = [
  {
    title: "统一问题台账",
    desc: "集中记录来源、场景、类型与影响",
    icon: <UnorderedListOutlined />,
    className: "feature-ledger",
  },
  {
    title: "进度闭环跟踪",
    desc: "状态流转、责任归属、完成计划",
    icon: <FieldTimeOutlined />,
    className: "feature-flow",
  },
  {
    title: "AI 智能洞察",
    desc: "识别超期、复发与高优先级问题",
    icon: <RobotOutlined />,
    className: "feature-ai",
  },
  {
    title: "数据报表分析",
    desc: "沉淀分布、时长与优化方向",
    icon: <BarChartOutlined />,
    className: "feature-report",
  },
  {
    title: "复盘知识沉淀",
    desc: "形成根因、方案与改进记录",
    icon: <BulbOutlined />,
    className: "feature-review",
  },
];

export default function LoginPage() {
  const { user, login } = useAuth();
  const [loading, setLoading] = useState(false);
  const [ssoLoading, setSsoLoading] = useState(false);
  const [ssoConfig, setSsoConfig] = useState<SsoConfig>();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: { pathname?: string } } | null)?.from
    ?.pathname;

  useEffect(() => {
    issueApi
      .ssoConfig()
      .then(setSsoConfig)
      .catch(() => setSsoConfig(undefined));
  }, []);

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

  const loginWithSso = async () => {
    if (!ssoConfig?.enabled) {
      message.warning("企业 SSO 尚未启用，请先使用账号密码登录");
      return;
    }
    setSsoLoading(true);
    try {
      const result = await issueApi.ssoLogin();
      window.location.href = result.loginUrl;
    } catch (error) {
      message.error(error instanceof Error ? error.message : "SSO 登录失败");
    } finally {
      setSsoLoading(false);
    }
  };

  return (
    <main className="login-page">
      <div className="login-shell">
        <section className="login-hero" aria-label="产品能力介绍">
          <div className="login-logo">
            <StarFilled />
            <span className="notranslate" lang="en" translate="no">
              IssueOps
            </span>
          </div>
          <div className="login-copy">
            <h1>
              <span className="notranslate" lang="en" translate="no">
                IssueOps
              </span>
              产品与业务问题治理平台
            </h1>
            <h2>让异常问题从提交处理走向持续闭环</h2>
            <p>
              统一管理问题来源、影响范围、处理进度、责任归属与复盘沉淀。
            </p>
          </div>
          <div className="login-visual" aria-hidden="true">
            <div className="visual-core">
              <StarFilled />
            </div>
            <div className="visual-ring ring-one" />
            <div className="visual-ring ring-two" />
            <div className="visual-panel panel-back">
              <i />
              <i />
              <i />
            </div>
            <div className="visual-panel panel-front">
              <span />
              <span />
              <span />
            </div>
            {capabilities.map((item) => (
              <div className={`feature-card ${item.className}`} key={item.title}>
                <em>{item.icon}</em>
                <div>
                  <b>{item.title}</b>
                  <small>{item.desc}</small>
                </div>
              </div>
            ))}
          </div>
        </section>

        <section className="login-card" aria-label="登录表单">
          <div className="login-card-mark">
            <StarFilled />
          </div>
          <div className="login-card-heading">
            <h2>
              登录{" "}
              <span className="notranslate" lang="en" translate="no">
                IssueOps
              </span>
            </h2>
          </div>
          <Form layout="vertical" onFinish={submit}>
            <Form.Item
              name="username"
              label="账号"
              rules={[{ required: true, message: "请输入账号" }]}
            >
              <Input
                prefix={<UserOutlined />}
                placeholder="请输入账号"
                size="large"
              />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: "请输入密码" }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="请输入密码"
                size="large"
              />
            </Form.Item>
            <div className="login-options">
              <Checkbox defaultChecked>记住我</Checkbox>
              <span>如需重置密码，请联系管理员</span>
            </div>
            <Button
              type="primary"
              htmlType="submit"
              loading={loading}
              block
              size="large"
            >
              进入工作台
            </Button>
            <Button
              className="login-sso-button"
              icon={<SafetyCertificateOutlined />}
              loading={ssoLoading}
              block
              size="large"
              htmlType="button"
              onClick={loginWithSso}
            >
              {ssoConfig?.providerName || "企业 SSO"} 登录
            </Button>
          </Form>
          <div className="login-footnote">
            <LockOutlined />
            <span>数据安全传输中，全程 SSL 加密保护</span>
          </div>
        </section>
      </div>
    </main>
  );
}
