import { expect, test } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { mockIssueOpsApi, seedAuth } from "./fixtures";

test("数据模块支持后端部门重算、对比开关和 URL 化钻取", async ({ page }) => {
  await seedAuth(page);
  const calls = await mockIssueOpsApi(page);

  await page.goto("/data");
  await expect(page.getByRole("heading", { name: "数据分析" })).toBeVisible();
  await expect(page.getByText("治理指数")).toBeVisible();
  await expect(page.getByText("较上期", { exact: true })).toBeVisible();

  await page.locator(".data-department-select").click();
  await page
    .locator(".ant-select-item-option")
    .filter({ hasText: "技术部" })
    .click();

  await expect(page.getByText(/已按责任部门筛选：技术部/)).toBeVisible();
  await expect.poll(() => calls.analysisSearches.at(-1) || "").toContain(
    "departments=%E6%8A%80%E6%9C%AF%E9%83%A8",
  );

  await page.getByRole("switch").click();
  await expect(page).toHaveURL(/compare=false/);
  await expect(page.getByText("当前周期").first()).toBeVisible();

  await page.getByRole("button", { name: /部门排行/ }).click();
  await expect(page).toHaveURL(/\/data\/analysis/);
  await expect(page).toHaveURL(/dimension=responsibleDepartment/);
  await expect(page).toHaveURL(/value=%E6%8A%80%E6%9C%AF%E9%83%A8|value=技术部/);
  await expect(page.getByText("分析路径")).toBeVisible();
  await expect(page.getByText("技术部").first()).toBeVisible();
});

test("数据模块导出当前筛选下的 CSV 明细", async ({ page }) => {
  await seedAuth(page);
  await mockIssueOpsApi(page);

  await page.goto("/data");
  await expect(page.getByRole("heading", { name: "数据分析" })).toBeVisible();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出" }).click();
  const download = await downloadPromise;
  const path = await download.path();
  expect(path).toBeTruthy();
  const csv = await readFile(path!, "utf8");

  expect(download.suggestedFilename()).toContain("IssueOps-数据分析");
  expect(csv).toContain("问题编号");
  expect(csv).toContain("PBI-20260603-0001");
  expect(csv).toContain("支付成功后订单状态延迟更新");
});
