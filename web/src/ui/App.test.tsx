import { render, screen } from "@solidjs/testing-library";
import { afterEach, expect, test, vi } from "vitest";
import App from "./App";

afterEach(() => vi.unstubAllGlobals());

test("a browser without a session sees the phone login", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ status: 401 }));
  render(() => <App />);
  expect(await screen.findByRole("heading", { name: "TeleNebula Dex" })).toBeTruthy();
  expect(screen.getByRole("textbox", { name: "Username" })).toBeTruthy();
});
