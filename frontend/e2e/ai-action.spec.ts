import { expect, test } from "@playwright/test";
import { mockIssueOpsApi, seedAuth } from "./fixtures";

test("AI 洞察生成待确认草稿后需要用户确认执行", async ({ page }) => {
  await seedAuth(page);
  const calls = await mockIssueOpsApi(page);

  await page.goto("/ai-insights");
  await expect(page.getByRole("heading", { name: "AI 洞察" })).toBeVisible();
  await expect(page.getByText("本次建议优先级")).toBeVisible();

  const input = page.getByPlaceholder("继续追问：按部门、复发风险或超期原因分析...");
  await input.fill("把支付问题更新为待验证");
  await input.press("Enter");

  await expect(page.getByText("待确认 · 更新状态")).toBeVisible();
  await expect(page.getByText("将支付问题更新为待验证")).toBeVisible();
  await page.getByRole("button", { name: "确认写入系统" }).click();

  await expect.poll(() => calls.actionExecuteBodies.at(-1)).toEqual({
    actionId: "action-e2e-1",
  });
  await expect(page.getByText("已更新状态：待验证")).toBeVisible();
});
