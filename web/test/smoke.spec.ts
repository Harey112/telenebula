import { expect, test } from "@playwright/test";
import { installMockPhone } from "./mock-phone";

test("the parallel shell loads from the mock phone", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  await installMockPhone(page, {
    chats: [
      { peer: "fd00::2", label: "Alice", lastBody: "See you soon", lastTs: Date.UTC(2026, 8, 23, 11, 30), unread: 1 },
      { peer: "fd00::3", label: "Bob", lastBody: "Archived", lastTs: Date.UTC(2026, 8, 22, 9), isArchived: true },
    ],
  });
  const response = await page.goto("/");
  expect(response?.headers()["content-security-policy"]).toContain("script-src 'self'");
  await expect(page.getByRole("heading", { name: "Chats" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Archived chats" })).toBeVisible();
  await expect(page).toHaveScreenshot(`smoke-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
});
