import { expect, test } from "@playwright/test";
import { mockIssueOpsApi, seedAuth } from "./fixtures";

test("账号管理可以停用账号并查看角色配置", async ({ page }) => {
  await seedAuth(page);
  const calls = await mockIssueOpsApi(page);

  await page.goto("/settings/accounts");
  await expect(page.getByRole("heading", { name: "账号管理" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "角色配置" })).toBeVisible();

  const viewerRow = page.locator("tr").filter({ hasText: "viewer" });
  await expect(viewerRow.locator("b", { hasText: "viewer" })).toBeVisible();
  await viewerRow.locator(".ant-switch").click();

  await expect.poll(() => calls.accountEnabledBodies.at(-1)).toMatchObject({
    enabled: false,
  });
  await expect(viewerRow.locator(".ant-tag", { hasText: "停用" })).toBeVisible();

  await page.getByRole("tab", { name: "角色配置" }).click();
  const rolePanel = page.locator(".role-config-panel");
  await expect(rolePanel.locator("tr").filter({ hasText: "ADMIN" }).locator("b")).toHaveText("管理员");
  await expect(rolePanel.locator("tr").filter({ hasText: "VIEWER" }).locator("b")).toHaveText("观察员");
});

test("账号管理可以新增后台角色", async ({ page }) => {
  await seedAuth(page);
  const calls = await mockIssueOpsApi(page);

  await page.goto("/settings/accounts");
  await page.getByRole("tab", { name: "角色配置" }).click();
  const rolePanel = page.locator(".role-config-panel");
  await expect(rolePanel.locator("tr").filter({ hasText: "ADMIN" }).locator("b")).toHaveText("管理员");

  await page.getByRole("button", { name: "新增角色" }).click();
  const dialog = page.locator(".ant-modal").last();
  await expect(dialog).toBeVisible();
  await dialog.locator(".ant-form-item").nth(0).locator("input").fill("OPS_MANAGER");
  await dialog.locator(".ant-form-item").nth(1).locator("input").fill("Ops Manager");
  await dialog.locator(".ant-form-item").nth(5).locator(".ant-select").click();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await dialog.getByText("新增问题（issue:create）").click();
  await page.getByRole("button", { name: /保\s*存/ }).click();

  await expect.poll(() => calls.roleCreateBodies.at(-1)).toMatchObject({
    code: "OPS_MANAGER",
    name: "Ops Manager",
    defaultDepartment: expect.any(String),
    permissions: ["issue:create"],
  });
  await expect(rolePanel.getByText("OPS_MANAGER")).toBeVisible();
});

test("账号管理可以维护部门配置", async ({ page }) => {
  await seedAuth(page);
  const calls = await mockIssueOpsApi(page);

  await page.goto("/settings/accounts");
  await page.getByRole("tab", { name: "部门配置" }).click();
  const departmentPanel = page.locator(".role-config-panel").last();
  await expect(departmentPanel.getByText("技术部")).toBeVisible();

  await page.getByRole("button", { name: "新增部门" }).click();
  const dialog = page.locator(".ant-modal").last();
  await expect(dialog).toBeVisible();
  await dialog.locator(".ant-form-item").nth(0).locator("input").fill("QA");
  await dialog.locator(".ant-form-item").nth(1).locator("input").fill("质量部");
  await page.getByRole("button", { name: /保\s*存/ }).click();

  await expect.poll(() => calls.departmentCreateBodies.at(-1)).toMatchObject({
    code: "QA",
    name: "质量部",
    enabled: true,
  });
  await expect(departmentPanel.getByText("质量部")).toBeVisible();
});
