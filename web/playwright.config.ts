import { defineConfig, devices } from "@playwright/test";

const widths = [360, 768, 1024, 1440];
const engines = ["chromium", "firefox", "webkit"] as const;

export default defineConfig({
  testDir: "./test",
  workers: 1,
  timeout: 60_000,
  projects: engines.flatMap((engine) =>
    widths.map((width) => ({
      name: `${engine}-${width}`,
      snapshotPathTemplate: `{testDir}/__screenshots__/${engine === "webkit" ? "chromium" : engine}/{arg}{ext}`,
      use: {
        ...devices[engine === "chromium" ? "Desktop Chrome" : engine === "firefox" ? "Desktop Firefox" : "Desktop Safari"],
        viewport: { width, height: 900 },
      },
    })),
  ),
  use: { baseURL: "http://127.0.0.1:4173" },
  webServer: {
    command: "npm run preview -- --port 4173 --strictPort",
    url: "http://127.0.0.1:4173",
    reuseExistingServer: false,
  },
});
