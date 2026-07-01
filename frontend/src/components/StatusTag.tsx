import type { CSSProperties } from "react";
import { palette } from "../design-system";

type StatusTagSize = "compact" | "default" | "prominent";

const tones: Record<
  string,
  { color: string; background: string; border: string }
> = {
  待处理: {
    color: "#6e6e73",
    background: "#f7f7f9",
    border: "#e8e8ed",
  },
  处理中: {
    color: "#b85f00",
    background: "#fff8ef",
    border: "#f2dfc6",
  },
  待验证: {
    color: palette.primary,
    background: "#f4f3ff",
    border: "#dedcff",
  },
  已完成: {
    color: "#167a55",
    background: "#f1faf6",
    border: "#d7efe4",
  },
};

export default function StatusTag({
  status,
  size = "default",
}: {
  status: string;
  size?: StatusTagSize;
}) {
  const tone =
    tones[status] || {
      color: palette.muted,
      background: "#f7f7f9",
      border: palette.line,
    };
  const style = {
    "--status-color": tone.color,
    "--status-bg": tone.background,
    "--status-border": tone.border,
  } as CSSProperties;

  return (
    <span className={`status-pill status-pill-${size}`} style={style}>
      <span className="status-dot" />
      {status}
    </span>
  );
}
