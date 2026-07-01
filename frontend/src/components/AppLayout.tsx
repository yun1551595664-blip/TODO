import {
  AppstoreOutlined,
  BulbOutlined,
  FileTextOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  RobotOutlined,
  SettingOutlined,
  StarFilled,
  SwapOutlined,
} from "@ant-design/icons";
import { Button, Tooltip } from "antd";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
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
          <Tooltip title="新建问题" placement="right">
            <Button
              type="primary"
              shape="circle"
              icon={<PlusOutlined />}
              onClick={() => nav("/issues/new")}
            />
          </Tooltip>
          <Tooltip title="字段配置" placement="right">
            <SettingOutlined
              className="rail-icon"
              onClick={() => nav("/settings/fields")}
            />
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
