import {
  AppstoreOutlined,
  BulbOutlined,
  FileTextOutlined,
  LogoutOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  RobotOutlined,
  SettingOutlined,
  StarFilled,
  SwapOutlined,
} from "@ant-design/icons";
import { Button, Tooltip } from "antd";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../auth";
const items = [
  ["/", "概览", AppstoreOutlined],
  ["/issues", "问题", QuestionCircleOutlined],
  ["/ai-insights", "AI 洞察", RobotOutlined],
  ["/data", "数据", FileTextOutlined],
  ["/retrospective", "复盘沉淀", BulbOutlined],
] as const;
export default function AppLayout() {
  const nav = useNavigate();
  const loc = useLocation();
  const { user, logout, hasPermission } = useAuth();
  return (
    <div className="app-shell">
      <aside className="nav-rail">
        <div className="brand-mark">
          <StarFilled />
        </div>
        <nav>
          {items.map(([to, label, Icon]) => (
            <Tooltip title={label} placement="right" key={to}>
              <NavLink
                to={to}
                end={to === "/"}
                className={({ isActive }) =>
                  isActive ? "rail-item active" : "rail-item"
                }
              >
                <Icon />
                <span>{label}</span>
              </NavLink>
            </Tooltip>
          ))}
        </nav>
        <div className="rail-bottom">
          {hasPermission("issue:create") && (
            <Tooltip title="新建问题" placement="right">
              <Button
                type="primary"
                shape="circle"
                icon={<PlusOutlined />}
                onClick={() => nav("/issues/new")}
              />
            </Tooltip>
          )}
          <Tooltip
            title={`${user?.displayName || user?.username} · ${user?.role}`}
            placement="right"
          >
            <div className="rail-user">{user?.displayName?.slice(0, 1) || "用"}</div>
          </Tooltip>
          {hasPermission("field:manage") && (
            <Tooltip title="字段配置" placement="right">
              <SettingOutlined
                className="rail-icon"
                onClick={() => nav("/settings/fields")}
              />
            </Tooltip>
          )}
          <Tooltip title="退出登录" placement="right">
            <LogoutOutlined className="rail-icon" onClick={logout} />
          </Tooltip>
        </div>
      </aside>
      <main className="main-canvas">
        <header className="mobile-top">
          <b>问题进度</b>
          <SwapOutlined />
        </header>
        <Outlet key={loc.pathname} />
      </main>
    </div>
  );
}
