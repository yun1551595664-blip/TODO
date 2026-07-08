import { expect, test } from "@playwright/test";
import { mockIssueOpsApi } from "./fixtures";

test("账号密码登录后进入首页", async ({ page }) => {
  const calls = await mockIssueOpsApi(page);

  await page.goto("/login");
  await expect(page.getByRole("heading", { name: /登录/ })).toBeVisible();
  await expect(page.getByText("统一问题台账")).toBeVisible();

  await page.getByPlaceholder("请输入账号").fill("admin");
  await page.getByPlaceholder("请输入密码").fill("admin123");
  await page.getByRole("button", { name: "进入工作台" }).click();

  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("heading", { name: /照远/ })).toBeVisible();
  expect(calls.loginBodies).toContainEqual({
    username: "admin",
    password: "admin123",
  });
});
