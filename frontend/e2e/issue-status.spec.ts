import { expect, test } from "@playwright/test";
import { mockIssueOpsApi, seedAuth } from "./fixtures";

test("问题详情页更新状态并写入处理记录", async ({ page }) => {
  await seedAuth(page);
  const calls = await mockIssueOpsApi(page);

  await page.goto("/issues/1");
  await expect(
    page.getByRole("heading", { name: "支付成功后订单状态延迟更新" }),
  ).toBeVisible();
  await expect(page.getByText("处理中").first()).toBeVisible();

  await page.getByRole("button", { name: "更新状态" }).click();
  const dialog = page.getByRole("dialog", { name: "更新问题状态" });
  await expect(dialog).toBeVisible();

  await dialog.locator(".ant-select").click();
  await page
    .locator(".ant-select-item-option")
    .filter({ hasText: "待验证" })
    .click();
  await expect(dialog).toContainText("待验证");
  await dialog.getByPlaceholder("说明本次状态变更").fill("修复完成，进入验证");
  await dialog.locator(".ant-btn-primary").click();

  await expect.poll(() => calls.statusBodies.at(-1)).toMatchObject({
    status: "待验证",
    operator: "照远",
    content: "修复完成，进入验证",
  });
  await expect(page.getByText("状态已更新并写入处理记录")).toBeVisible();
});
