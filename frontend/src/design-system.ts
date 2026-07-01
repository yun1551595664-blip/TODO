import type { ThemeConfig } from "antd";
export const palette = {
  canvas: "#fbfbfd",
  surface: "#ffffff",
  text: "#1d1d1f",
  muted: "#6e6e73",
  subtle: "#a1a1a6",
  line: "#e5e5ea",
  primary: "#6c63ff",
  primarySoft: "#f0efff",
  success: "#22a06b",
  warning: "#ff8a1f",
  danger: "#ff4d4f",
  info: "#7b8cff",
};
export const theme: ThemeConfig = {
  token: {
    colorPrimary: palette.primary,
    colorBgLayout: palette.canvas,
    colorBgContainer: palette.surface,
    colorText: palette.text,
    colorTextSecondary: palette.muted,
    colorBorder: palette.line,
    borderRadius: 10,
    fontFamily:
      '-apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", "Microsoft YaHei", sans-serif',
    fontSize: 14,
    controlHeight: 38,
    boxShadow: "0 12px 36px rgba(30,30,50,.08)",
  },
  components: {
    Button: { borderRadius: 10, fontWeight: 500 },
    Card: { borderRadiusLG: 14 },
    Table: {
      headerBg: "transparent",
      headerColor: palette.muted,
      rowHoverBg: "#f7f7fb",
      cellPaddingBlock: 12,
    },
    Tag: { borderRadiusSM: 6 },
    Input: { activeShadow: "0 0 0 3px rgba(108,99,255,.12)" },
  },
};
