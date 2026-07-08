import { expect, type Page, test } from "@playwright/test";
import { mockIssueOpsApi, seedAuth } from "./fixtures";

async function chooseOption(page: Page, label: string, value: string) {
  await page.locator(".ant-form-item").filter({ hasText: label }).locator(".ant-select").click();
  await page.locator(".ant-select-item-option").filter({ hasText: value }).click();
}

test("新增问题表单提交后进入详情页", async ({ page }) => {
  await seedAuth(page);
  const calls = await mockIssueOpsApi(page);

  await page.goto("/issues/new");
  await expect(page.getByRole("heading", { name: "新增问题" })).toBeVisible();

  await page.getByLabel("问题标题").fill("E2E 新增支付回调异常");
  await page.getByLabel("问题描述").fill("支付回调偶发失败，需要补偿机制。");
  await chooseOption(page, "问题来源", "客服反馈");
  await chooseOption(page, "业务场景", "订单支付");
  await chooseOption(page, "问题类型", "系统缺陷");
  await chooseOption(page, "影响范围", "部分用户");
  await page.getByLabel("客户影响说明").fill("部分用户无法及时看到订单状态。");
  await page.getByLabel("复现步骤").fill("1. 完成支付\n2. 刷新订单详情");
  await chooseOption(page, "责任部门", "技术部");
  await page.getByLabel("责任人").fill("张臻");

  await page.getByRole("button", { name: /保存问题/ }).click();

  await expect.poll(() => calls.createIssueBodies.at(-1)).toMatchObject({
    title: "E2E 新增支付回调异常",
    description: "支付回调偶发失败，需要补偿机制。",
    source: "客服反馈",
    businessScene: "订单支付",
    issueType: "系统缺陷",
    impactScope: "部分用户",
    responsibleDepartment: "技术部",
    responsiblePerson: "张臻",
  });
  await expect(page).toHaveURL(/\/issues\/3$/);
  await expect(page.getByRole("heading", { name: "E2E 新增支付回调异常" })).toBeVisible();
});
