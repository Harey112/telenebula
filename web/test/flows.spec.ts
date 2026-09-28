import { expect, test } from "@playwright/test";
import { installMockPhone, type RecordedFrame } from "./mock-phone";

function requestId(frame: RecordedFrame | undefined): string {
  if (typeof frame?.requestId !== "string") throw new Error("Text send had no request ID");
  return frame.requestId;
}

test("login reports a refusal and then opens the browser session", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  await installMockPhone(page, { active: false });
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "TeleNebula Dex" })).toBeVisible();
  await expect(page).toHaveScreenshot(`login-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("textbox", { name: "Username" }).fill("dex");
  await page.getByLabel("Password").fill("wrong");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page.getByRole("alert")).toHaveText("Wrong username or password.");
  await page.getByLabel("Password").fill("password");
  await page.getByRole("button", { name: "Log in" }).click();
  await expect(page.getByRole("heading", { name: "Chats" })).toBeVisible();
});

test("archive button switches lists and routine Done replies stay quiet", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const phone = await installMockPhone(page, {
    chats: [
      { peer: "fd00::2", label: "Alice", lastBody: "Recent", lastTs: Date.UTC(2026, 8, 23, 11) },
      { peer: "fd00::3", label: "Bob", lastBody: "Earlier", lastTs: Date.UTC(2026, 8, 22, 11), isArchived: true },
    ],
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Archived chats" }).click();
  await expect(page.getByRole("heading", { name: "Archived chats" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Bob/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /Alice/ })).toHaveCount(0);
  phone.send({ t: "done", what: "watch" });
  await expect(page.getByRole("status", { name: "Done" })).toHaveCount(0);
  await expect(page).toHaveScreenshot(`archived-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("button", { name: "All chats" }).click();
  await expect(page.getByRole("button", { name: /Alice/ })).toBeVisible();
});

test("add contact uses full-size fields and handles validation and phone replies", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const phone = await installMockPhone(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Contacts" }).click();
  const open = page.getByRole("button", { name: "Add contact" });
  await open.click();
  await expect(page.getByRole("dialog", { name: "Add contact" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(open).toBeFocused();
  await open.click();
  const ip = page.getByLabel("Nebula IPv6 address");
  expect((await ip.boundingBox())?.height).toBeGreaterThanOrEqual(48);
  expect((await page.getByLabel("Notes").boundingBox())?.height).toBeGreaterThanOrEqual(96);
  await expect(page).toHaveScreenshot(`add-contact-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await ip.fill("wrong");
  await page.getByRole("dialog").getByRole("button", { name: "Add contact" }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText("Nebula IPv6");
  await ip.fill("fd00::2");
  await page.getByLabel("Nickname").fill("Alice");
  await page.getByRole("dialog").getByRole("button", { name: "Add contact" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "contact_add"))
    .toMatchObject({ t: "contact_add", peer: "fd00::2", nickname: "Alice" });
  phone.send({ t: "error", ref: "contact_add", message: "The phone could not save this contact." });
  await expect(page.getByRole("dialog").getByRole("alert")).toHaveText("The phone could not save this contact.");
  await page.getByRole("dialog").getByRole("button", { name: "Add contact" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_add").length).toBe(2);
  phone.send({ t: "done", what: "contact_add" });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Chats", exact: true })).toHaveAttribute("aria-current", "page");
  await expect(page.getByRole("region", { name: "Conversation" })).toContainText("fd00::2");
});

test("contact selection requests details and opens its chat only from Message", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice", nickname: "Friend", notes: "Coffee on Friday", addedAt: Date.UTC(2026, 8, 1) };
  const phone = await installMockPhone(page, { contacts: [contact] });
  await page.goto("/");
  await page.getByRole("button", { name: "Contacts" }).click();
  await page.getByRole("button", { name: /Alice/ }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "request_contact_detail"))
    .toMatchObject({ t: "request_contact_detail", peer: contact.ip });
  await expect(page.getByRole("button", { name: "Chats", exact: true })).not.toHaveAttribute("aria-current", "page");
  phone.send({ t: "contact_detail", detail: {
    contact, connectionStatus: "Connected", endpoint: "192.0.2.1:4242", peerCertName: "Alice",
    stats: { isConnected: true, messagesSent: 5, messagesReceived: 8, bytesSent: 1024, bytesReceived: 2048 },
  } });
  await expect(page.getByRole("region", { name: "Contact details" })).toContainText("5");
  await expect(page).toHaveScreenshot(`contact-detail-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("button", { name: "Edit contact" }).click();
  await expect(page.getByRole("dialog", { name: "Edit contact" })).toBeVisible();
  await expect(page).toHaveScreenshot(`edit-contact-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("dialog").getByLabel("Nickname").fill("Best friend");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "contact_save"))
    .toMatchObject({ t: "contact_save", peer: contact.ip, nickname: "Best friend" });
  phone.send({ t: "error", ref: "contact_save", message: "Could not save." });
  await expect(page.getByRole("dialog").getByRole("alert")).toHaveText("Could not save.");
  await page.getByRole("dialog").getByRole("button", { name: "Save" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_save").length).toBe(2);
  phone.send({ t: "done", what: "contact_save" });
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "request_contact_detail").length).toBe(2);
  phone.send({ t: "contacts", items: [{ ...contact, nickname: "Best friend" }] });
  await expect(page.getByRole("region", { name: "Contact details" })).toContainText("Best friend");
  const detail = page.getByRole("region", { name: "Contact details" });
  await detail.getByRole("button", { name: "Pin", exact: true }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_flags").at(-1))
    .toMatchObject({ t: "contact_flags", peer: contact.ip, flags: { isPinned: true } });
  phone.send({ t: "error", ref: "contact_flags", message: "Could not pin." });
  await expect(detail.getByRole("button", { name: "Pin", exact: true })).toHaveAttribute("aria-pressed", "false");
  await detail.getByRole("button", { name: "Pin", exact: true }).click();
  phone.send({ t: "contacts", items: [{ ...contact, nickname: "Best friend", isPinned: true }] });
  await expect(detail.getByRole("button", { name: "Unpin" })).toHaveAttribute("aria-pressed", "true");
  await detail.getByRole("button", { name: "Archive", exact: true }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_flags").at(-1))
    .toMatchObject({ t: "contact_flags", peer: contact.ip, flags: { isArchived: true } });
  phone.send({ t: "contacts", items: [{ ...contact, nickname: "Best friend", isPinned: true, isArchived: true }] });
  await expect(detail.getByRole("button", { name: "Unarchive" })).toHaveAttribute("aria-pressed", "true");
  await detail.getByRole("button", { name: "Block", exact: true }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_flags").at(-1))
    .toMatchObject({ t: "contact_flags", peer: contact.ip, flags: { isBlocked: true } });
  phone.send({ t: "contacts", items: [{ ...contact, nickname: "Best friend", isPinned: true, isArchived: true, isBlocked: true }] });
  await expect(detail.getByRole("button", { name: "Unblock" })).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("region", { name: "Contact details" }).getByRole("button", { name: "Message" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "open_chat"))
    .toMatchObject({ t: "open_chat", peer: contact.ip });
});

test("deleting a contact requires confirmation and waits for the phone", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact] });
  await page.goto("/");
  await page.getByRole("button", { name: "Contacts" }).click();
  await page.getByRole("button", { name: /Alice/ }).click();
  await page.getByRole("region", { name: "Contact details" }).getByRole("button", { name: "Delete" }).click();
  const dialog = page.getByRole("dialog", { name: "Delete contact" });
  await expect(dialog).toContainText("every message");
  await expect(dialog).toContainText("cannot be undone");
  await expect(page).toHaveScreenshot(`delete-contact-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await dialog.getByRole("button", { name: "Cancel" }).click();
  expect(phone.frames.some((frame) => frame.t === "contact_delete")).toBe(false);
  await page.getByRole("region", { name: "Contact details" }).getByRole("button", { name: "Delete" }).click();
  await dialog.getByRole("button", { name: "Delete contact" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "contact_delete"))
    .toMatchObject({ t: "contact_delete", peer: contact.ip });
  await expect(dialog.getByRole("button", { name: "Delete contact" })).toBeDisabled();
  phone.send({ t: "error", ref: "contact_delete", message: "Could not remove it." });
  await expect(dialog.getByRole("alert")).toHaveText("Could not remove it.");
  await dialog.getByRole("button", { name: "Delete contact" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_delete").length).toBe(2);
  phone.send({ t: "done", what: "contact_delete" });
  await expect(dialog).toHaveCount(0);
  await expect(page.locator(".dex-contacts-pane")).toHaveAttribute("data-contact-open", "false");
});

test("changing a contact address keeps the dialog open on a phone refusal", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact] });
  await page.goto("/");
  await page.getByRole("button", { name: "Contacts" }).click();
  await page.getByRole("button", { name: /Alice/ }).click();
  await page.getByRole("region", { name: "Contact details" }).getByRole("button", { name: "Change" }).click();
  const dialog = page.getByRole("dialog", { name: "Change contact address" });
  await expect(page).toHaveScreenshot(`change-contact-ip-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await dialog.getByLabel("Nebula IPv6 address").fill("fd00::3");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "contact_change_ip"))
    .toMatchObject({ peer: contact.ip, newIp: "fd00::3" });
  phone.send({ t: "error", ref: "contact_change_ip", message: "Address already in use." });
  await expect(dialog.getByRole("alert")).toHaveText("Address already in use.");
  await dialog.getByRole("button", { name: "Save" }).click();
  phone.send({ t: "done", what: "contact_change_ip" });
  await expect(dialog).toHaveCount(0);
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "request_contact_detail").at(-1))
    .toMatchObject({ peer: "fd00::3" });
});

test("composer preserves a refused message and clears it for its matching completion", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, {
    contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }],
  });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "m0", peer: contact.ip, dir: "out", body: "See you soon", ts: Date.UTC(2026, 8, 22, 11), status: "delivered", kind: "text" },
    { id: "m1", peer: contact.ip, dir: "in", body: "Hello", ts: Date.UTC(2026, 8, 23, 11), status: "received", kind: "text" },
  ] } });
  const composer = page.getByRole("textbox", { name: "Message" });
  await expect(composer).toBeVisible();
  await expect(page.getByRole("log", { name: "Messages" })).toContainText("Hello");
  await expect(page.getByText("Yesterday")).toBeVisible();
  await expect(page).toHaveScreenshot(`conversation-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await composer.fill("See you soon");
  await composer.press("Shift+Enter");
  await expect.poll(() => phone.frames.find((frame) => frame.t === "send_text"))
    .toMatchObject({ t: "send_text", peer: contact.ip, body: "See you soon" });
  const firstRequestId = requestId(phone.frames.find((frame) => frame.t === "send_text"));
  await expect(page.getByRole("button", { name: "Send" })).toBeDisabled();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "m0", peer: contact.ip, dir: "out", body: "See you soon", ts: Date.UTC(2026, 8, 22, 11), status: "delivered", kind: "text" },
    { id: "m1", peer: contact.ip, dir: "in", body: "Hello", ts: Date.UTC(2026, 8, 23, 11), status: "received", kind: "text" },
  ] } });
  await expect(page.getByRole("button", { name: "Send" })).toBeDisabled();
  phone.send({ t: "error", ref: "send_text", message: "Could not queue it", requestId: firstRequestId });
  await expect(composer).toHaveValue("See you soon");
  await expect(page.getByRole("button", { name: "Send" })).toBeEnabled();
  await page.getByRole("button", { name: "Send" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "send_text").length).toBe(2);
  const retryRequestId = requestId(phone.frames.filter((frame) => frame.t === "send_text").at(-1));
  expect(retryRequestId).not.toBe(firstRequestId);
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "m0", peer: contact.ip, dir: "out", body: "See you soon", ts: Date.UTC(2026, 8, 22, 11), status: "delivered", kind: "text" },
    { id: "m1", peer: contact.ip, dir: "in", body: "Hello", ts: Date.UTC(2026, 8, 23, 11), status: "received", kind: "text" },
    { id: "m2", peer: contact.ip, dir: "out", body: "See you soon", ts: Date.UTC(2026, 8, 23, 12), status: "sent", kind: "text" },
  ] } });
  await expect(composer).toHaveValue("See you soon");
  phone.send({ t: "done", what: "send_text", requestId: firstRequestId });
  await expect(composer).toHaveValue("See you soon");
  phone.send({ t: "done", what: "send_text", requestId: retryRequestId });
  await expect(composer).toHaveValue("");
  phone.send({ t: "contacts", items: [{ ...contact, isArchived: true }] });
  await expect(page.getByRole("button", { name: "Unarchive" })).toBeVisible();
  await page.getByRole("button", { name: "Unarchive" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "contact_flags"))
    .toMatchObject({ t: "contact_flags", peer: contact.ip, flags: { isArchived: false } });
});

test("message actions send replies, reactions, transfer decisions and retries", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: true, freeBytes: 1_000_000, messages: [
    { id: "m1", peer: contact.ip, dir: "in", body: "Hello", ts: Date.UTC(2026, 8, 22, 11), status: "received", kind: "text" },
    { id: "m2", peer: contact.ip, dir: "in", body: "", ts: Date.UTC(2026, 8, 23, 11), status: "offered", kind: "file", att: { name: "notes.txt", mime: "text/plain", size: 128, hasFile: false } },
    { id: "m3", peer: contact.ip, dir: "out", body: "Try again", ts: Date.UTC(2026, 8, 23, 12), status: "pending", kind: "text", send: "failed", actionId: "a3" },
  ] } });
  await expect(page.getByText("Yesterday")).toBeVisible();
  await expect(page.getByText("Today", { exact: true })).toBeVisible();
  await expect(page).toHaveScreenshot(`message-actions-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("button", { name: "Load earlier messages" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "load_more"))
    .toMatchObject({ peer: contact.ip, beforeId: "m1" });
  await page.locator('[data-id="m1"]').getByRole("button", { name: "Reply" }).click();
  await expect(page.getByText("Reply to Alice")).toBeVisible();
  await page.getByRole("textbox", { name: "Message" }).fill("Hi back");
  await page.getByRole("button", { name: "Send" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "send_text"))
    .toMatchObject({ peer: contact.ip, replyTo: "m1", body: "Hi back" });
  phone.send({ t: "done", what: "send_text", requestId: requestId(phone.frames.find((frame) => frame.t === "send_text")) });
  await page.locator('[data-id="m1"]').getByRole("button", { name: "React" }).click();
  await page.getByRole("menuitem", { name: "👍" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "react"))
    .toMatchObject({ messageId: "m1", emoji: "👍" });
  await page.locator('[data-id="m2"]').getByRole("button", { name: "Accept" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "accept_offer"))
    .toMatchObject({ messageId: "m2" });
  await page.locator('[data-id="m3"]').getByRole("button", { name: "Retry" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "retry_action"))
    .toMatchObject({ actionId: "a3" });
});

test("editing keeps the draft when the phone refuses and clears it on confirmation", async ({ page }) => {
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Before", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "m1", peer: contact.ip, dir: "out", body: "Before", ts: Date.UTC(2026, 8, 23, 11), status: "sent", kind: "text" },
  ] } });
  await page.locator('[data-id="m1"]').getByRole("button", { name: "More message actions" }).click();
  await page.getByRole("menuitem", { name: "Edit" }).click();
  const draft = page.getByRole("textbox", { name: "Message" });
  await expect(draft).toHaveValue("Before");
  await draft.fill("After");
  await page.getByRole("button", { name: "Send" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "edit").length).toBe(1);
  await expect(draft).toHaveValue("After");
  await expect(page.getByRole("button", { name: "Send" })).toBeDisabled();
  phone.send({ t: "error", ref: "m1", message: "No longer editable" });
  await expect(draft).toHaveValue("After");
  await expect(page.getByRole("button", { name: "Send" })).toBeEnabled();
  await page.getByRole("button", { name: "Send" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "edit").length).toBe(2);
  phone.send({ t: "done", what: "edit", requestId: "m1" });
  await expect(draft).toHaveValue("");
});

test("attachment picker uploads through the phone with the selected peer", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const requests: string[] = [];
  await page.route("**/a?**", async (route) => {
    requests.push(route.request().url());
    await route.fulfill({ status: 201, body: "{}" });
  });
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [] } });
  const chooser = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "Attach files" }).click();
  await (await chooser).setFiles("test/fixtures/upload.txt");
  await expect.poll(() => requests.length).toBe(1);
  expect(new URL(requests[0] ?? "http://invalid/").searchParams.get("peer")).toBe(contact.ip);
  expect(new URL(requests[0] ?? "http://invalid/").searchParams.get("name")).toBe("upload.txt");
  await expect(page.getByRole("button", { name: "Cancel upload.txt" })).toHaveCount(0);
});

test("a refused voice upload keeps the recording for retry", async ({ page }) => {
  await page.addInitScript(() => {
    class Recorder {
      static isTypeSupported(): boolean { return true; }
      mimeType = "audio/webm";
      state = "inactive";
      ondataavailable: ((event: { data: Blob }) => void) | null = null;
      onstop: (() => void) | null = null;
      start(): void { this.state = "recording"; }
      stop(): void {
        this.state = "inactive";
        this.ondataavailable?.({ data: new Blob(["voice"], { type: this.mimeType }) });
        this.onstop?.();
      }
    }
    Object.defineProperty(window, "MediaRecorder", { value: Recorder });
    Object.defineProperty(navigator, "mediaDevices", { value: { getUserMedia: async () => new MediaStream() } });
  });
  const requests: string[] = [];
  await page.route("**/a?**", async (route) => {
    requests.push(route.request().url());
    await route.fulfill({ status: requests.length === 1 ? 500 : 201, contentType: "application/json",
      body: requests.length === 1 ? '{"error":"Upload refused"}' : "{}" });
  });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [] } });
  await page.getByRole("button", { name: "Record voice message" }).click();
  await page.getByRole("button", { name: "Stop recording" }).click();
  await expect(page.getByRole("button", { name: "Send voice message" })).toBeVisible();
  await page.getByRole("button", { name: "Send voice message" }).click();
  await expect(page.getByText(/Upload refused/)).toBeVisible();
  await expect(page.getByRole("button", { name: "Send voice message" })).toBeEnabled();
  await page.getByRole("button", { name: "Send voice message" }).click();
  await expect.poll(() => requests.length).toBe(2);
  await expect(page.getByRole("button", { name: "Send voice message" })).toHaveCount(0);
  expect(new URL(requests[0] ?? "http://invalid/").searchParams.get("voice")).toBe("1");
  expect(new URL(requests[1] ?? "http://invalid/").searchParams.get("peer")).toBe(contact.ip);
});

test("a load-more refusal releases the loading state", async ({ page }) => {
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: true, messages: [
    { id: "m1", peer: contact.ip, dir: "in", body: "Hello", ts: Date.UTC(2026, 8, 23, 11), status: "received", kind: "text" },
  ] } });
  await page.getByRole("button", { name: "Load earlier messages" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "load_more")).toMatchObject({ peer: contact.ip });
  phone.send({ t: "error", ref: "load_more", message: "Could not load older messages" });
  await expect(page.getByRole("button", { name: "Load earlier messages" })).toBeEnabled();
});

test("a hidden chat waits until visible before marking new messages read", async ({ page }) => {
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => true });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "m1", peer: contact.ip, dir: "in", body: "Hello", ts: Date.UTC(2026, 8, 23, 11), status: "received", kind: "text", isRead: false },
  ] } });
  await expect(page.getByRole("log", { name: "Messages" })).toContainText("Hello");
  expect(phone.frames.some((frame) => frame.t === "mark_read")).toBe(false);
  await page.evaluate(() => {
    Object.defineProperty(document, "hidden", { configurable: true, get: () => false });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect.poll(() => phone.frames.find((frame) => frame.t === "mark_read"))
    .toMatchObject({ peer: contact.ip });
});

test("a contact typing override works when the Dex default is off", async ({ page }) => {
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice", privacy: { sendTypingIndicators: true } };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }],
    settings: { dexProfile: { sendTypingIndicators: false } } });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [] } });
  await page.getByRole("textbox", { name: "Message" }).fill("Hello");
  await expect.poll(() => phone.frames.find((frame) => frame.t === "typing" && frame.isTyping === true))
    .toMatchObject({ peer: contact.ip });
});

test("a paused presence still shows sharing as enabled", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const phone = await installMockPhone(page, { settings: { presence: {
    isShared: true, pauseMinutes: 30, pausedUntil: Date.UTC(2026, 8, 23, 12, 30),
  } } });
  await page.goto("/");
  await page.getByRole("button", { name: "Settings" }).click();
  await page.getByRole("tab", { name: "Status" }).click();
  await expect(page.getByRole("checkbox", { name: "Share when I am online" })).toBeChecked();
  await expect(page.getByLabel("Pause sharing")).toHaveValue("30");
  await page.getByLabel("Pause sharing").selectOption("0");
  await expect.poll(() => phone.frames.find((frame) => frame.t === "set_settings"))
    .toMatchObject({ patch: { presence: { isShared: true, pauseMinutes: 0, pausedUntil: 0 } } });
});

test("call history and incoming call controls use the phone wire", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const phone = await installMockPhone(page, { contacts: [{ ip: "fd00::2", label: "Alice", name: "Alice" }] });
  await page.goto("/");
  await page.getByRole("button", { name: "Calls" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "request_call_logs")).toMatchObject({ limit: 200 });
  phone.send({ t: "call_logs", items: [{ id: "c1", peer: "fd00::2", label: "Alice", dir: "in", isVideo: false,
    outcome: "missed", startedAt: Date.UTC(2026, 8, 23, 11), endedAt: Date.UTC(2026, 8, 23, 11, 1) }] });
  await expect(page.getByRole("region", { name: "Call history" })).toContainText("Missed");
  await expect(page).toHaveScreenshot(`calls-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  phone.send({ t: "call_state", state: { phase: "incoming", callId: "live1", peer: { ip: "fd00::2", name: "Alice" }, video: false, seat: "dex", seatClientId: "mock-client" } });
  await expect(page.getByRole("dialog", { name: "Incoming call" })).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Accept" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "call_accept")).toMatchObject({ callId: "live1" });
  phone.send({ t: "call_state", state: { phase: "active", callId: "live1", peer: { ip: "fd00::2", name: "Alice" }, video: false, seat: "dex", seatClientId: "mock-client" } });
  await expect(page.getByRole("dialog", { name: "Call with Alice" })).toBeVisible();
  await page.getByRole("button", { name: "Move call to phone" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "call_move_to_phone")).toMatchObject({ callId: "live1" });
  await page.getByRole("dialog").getByRole("button", { name: "End call" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "call_end")).toMatchObject({ callId: "live1" });
});

test("call-log deletion can be retried and refreshes the list after confirmation", async ({ page }) => {
  const phone = await installMockPhone(page);
  await page.goto("/");
  await page.getByRole("button", { name: "Calls" }).click();
  phone.send({ t: "call_logs", items: [{ id: "c1", peer: "fd00::2", label: "Alice", dir: "in", isVideo: false,
    outcome: "missed", startedAt: Date.UTC(2026, 8, 23, 11), endedAt: Date.UTC(2026, 8, 23, 11, 1) }] });
  await page.getByRole("checkbox", { name: /Select the call with Alice/ }).check();
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Delete selected" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "delete_call_logs").length).toBe(1);
  phone.send({ t: "error", ref: "delete_call_logs", message: "Could not delete the call" });
  await expect(page.getByRole("checkbox", { name: /Select the call with Alice/ })).toBeChecked();
  await page.getByRole("button", { name: "Delete", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Delete selected" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "delete_call_logs").length).toBe(2);
  phone.send({ t: "done", what: "delete_call_logs" });
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "request_call_logs").length).toBe(2);
  phone.send({ t: "call_logs", items: [] });
  await expect(page.getByRole("checkbox", { name: /Select the call with Alice/ })).toHaveCount(0);
  await expect(page.getByText("No calls yet.")).toBeVisible();
});

test("chat information requests media and sends per-contact settings", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [] } });
  await page.getByRole("button", { name: "Chat information" }).click();
  await expect(page.getByRole("complementary", { name: "Chat information" })).toBeVisible();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "request_chat_media")).toMatchObject({ peer: contact.ip });
  await expect.poll(() => phone.frames.find((frame) => frame.t === "request_chat_links")).toMatchObject({ peer: contact.ip });
  phone.send({ t: "contact_detail", detail: { contact, privacy: { revealGate: "tap", sendReadReceipts: true }, notifications: { useGlobal: true } } });
  phone.send({ t: "chat_links", peer: contact.ip, items: [{ messageId: "m1", url: "https://example.com/", ts: Date.UTC(2026, 8, 23, 11) }] });
  await expect(page.getByRole("link", { name: "https://example.com/" })).toBeVisible();
  await expect(page).toHaveScreenshot(`chat-info-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByLabel("Reveal covered messages in this chat").selectOption("ask");
  await expect.poll(() => phone.frames.find((frame) => frame.t === "contact_privacy"))
    .toMatchObject({ peer: contact.ip, privacy: { revealGate: "ask", sendReadReceipts: true } });
  await page.getByLabel("Read receipts in this chat").selectOption("off");
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_privacy").at(-1))
    .toMatchObject({ peer: contact.ip, privacy: { revealGate: "tap", sendReadReceipts: false } });
  await page.getByLabel("Disappearing messages").selectOption("3600");
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_flags").at(-1))
    .toMatchObject({ peer: contact.ip, flags: { disappearSeconds: 3600 } });
  await page.getByLabel("Mute notifications").selectOption("hour");
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "contact_flags").at(-1))
    .toMatchObject({ peer: contact.ip, flags: { muteUntil: expect.any(Number) } });
  const mute = phone.frames.filter((frame) => frame.t === "contact_flags").at(-1)?.flags;
  const muteUntil = mute && typeof mute === "object" && "muteUntil" in mute ? mute.muteUntil : null;
  expect(typeof muteUntil === "number" && muteUntil >= Date.UTC(2026, 8, 23, 13) &&
    muteUntil < Date.UTC(2026, 8, 23, 13, 0, 1)).toBe(true);
  await page.getByLabel("Search this chat").fill("coffee");
  await page.getByRole("button", { name: "Search messages" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "search"))
    .toMatchObject({ peer: contact.ip, text: "coffee" });
  await page.getByRole("button", { name: "Clear chat history" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();
  expect(phone.frames.some((frame) => frame.t === "clear_history")).toBe(false);
  phone.send({ t: "contacts", items: [{ ...contact, isBlocked: true }] });
  await expect(page.getByRole("button", { name: "Voice call", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Video call", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Start voice call" })).toBeDisabled();
});

test("emoji picker inserts into the composer at the caret", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, { contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastBody: "Hello", lastTs: Date.UTC(2026, 8, 23, 11) }] });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [] } });
  const message = page.getByRole("textbox", { name: "Message" });
  await message.fill("Hello world");
  await message.press("Home");
  await page.getByRole("button", { name: "Choose emoji" }).click();
  await expect(page.getByRole("dialog", { name: "Choose an emoji" })).toBeVisible();
  await expect(page).toHaveScreenshot(`emoji-picker-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("button", { name: "Choose 😀" }).click();
  await expect(message).toHaveValue("😀Hello world");
});

test("covered messages use the Dex reveal gate", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice", revealGate: "ask" as const };
  const phone = await installMockPhone(page, {
    contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastTs: Date.UTC(2026, 8, 23, 11) }],
    settings: { dexProfile: { coverRevealGate: "ask" } },
  });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "m1", peer: contact.ip, dir: "in", body: "Secret", ts: Date.UTC(2026, 8, 23, 11), status: "received", kind: "text", isCovered: true },
  ] } });
  await expect(page.getByRole("log", { name: "Messages" })).not.toContainText("Secret");
  await expect(page.getByRole("button", { name: "Covered message. Reveal it" })).toBeVisible();
  await expect(page.getByText("Today", { exact: true })).toBeVisible();
  await expect(page).toHaveScreenshot(`covered-message-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("button", { name: "Covered message. Reveal it" }).click();
  await expect(page.getByRole("dialog", { name: "Reveal message?" })).toBeVisible();
  await expect(page).toHaveScreenshot(`reveal-confirm-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByRole("log", { name: "Messages" })).not.toContainText("Secret");
  await page.getByRole("button", { name: "Covered message. Reveal it" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Reveal" }).click();
  await expect(page.getByRole("log", { name: "Messages" })).toContainText("Secret");
  phone.send({ t: "contacts", items: [{ ...contact, revealGate: "code" }] });
  phone.send({ t: "chat", view: { peer: contact.ip, contact: { ...contact, revealGate: "code" }, hasMore: false, messages: [
    { id: "m2", peer: contact.ip, dir: "in", body: "Hidden on phone", ts: Date.UTC(2026, 8, 23, 12), status: "received", kind: "text", isCovered: true },
  ] } });
  await page.getByRole("button", { name: "Covered message. Reveal it" }).click();
  await expect(page.getByText("This covered message opens only on your phone.")).toBeVisible();
  await expect(page.getByRole("log", { name: "Messages" })).not.toContainText("Hidden on phone");
});

test("covered text waits for its own completion even after the phone withholds its echo", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice", revealGate: "code" as const };
  const phone = await installMockPhone(page, {
    contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastTs: Date.UTC(2026, 8, 23, 11) }],
    settings: { dexProfile: { coverRevealGate: "code" } },
  });
  await page.goto("/");
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [] } });
  const cover = page.getByRole("button", { name: "Cover message" });
  await cover.click();
  await expect(cover).toHaveAttribute("aria-pressed", "true");
  await expect(page).toHaveScreenshot(`cover-composer-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  const composer = page.getByRole("textbox", { name: "Message" });
  await composer.fill("A private note");
  await page.getByRole("button", { name: "Send" }).click();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "send_text"))
    .toMatchObject({ t: "send_text", peer: contact.ip, body: "A private note", covered: true });
  const firstRequestId = requestId(phone.frames.find((frame) => frame.t === "send_text"));
  phone.send({ t: "error", ref: "send_text", message: "Could not queue it", requestId: firstRequestId });
  await expect(composer).toHaveValue("A private note");
  await expect(cover).toHaveAttribute("aria-pressed", "true");
  await page.getByRole("button", { name: "Send" }).click();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "send_text").length).toBe(2);
  const retryRequestId = requestId(phone.frames.filter((frame) => frame.t === "send_text").at(-1));
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [
    { id: "m1", peer: contact.ip, dir: "out", body: "", ts: Date.UTC(2026, 8, 23, 12), status: "sent", kind: "text", isCovered: true },
  ] } });
  await expect(composer).toHaveValue("A private note");
  phone.send({ t: "done", what: "send_text", requestId: firstRequestId });
  await expect(composer).toHaveValue("A private note");
  phone.send({ t: "done", what: "send_text", requestId: retryRequestId });
  await expect(composer).toHaveValue("");
  await expect(cover).toHaveAttribute("aria-pressed", "false");
  await expect(page.getByRole("log", { name: "Messages" })).not.toContainText("A private note");
});

test("Dex browser settings are separate and apply confirmed phone profile changes", async ({ page }) => {
  await page.clock.install({ time: new Date("2026-09-23T12:00:00Z") });
  const contact = { ip: "fd00::2", label: "Alice", name: "Alice" };
  const phone = await installMockPhone(page, {
    contacts: [contact], chats: [{ peer: contact.ip, label: "Alice", lastTs: Date.UTC(2026, 8, 23, 11) }],
    settings: { dexProfile: { themeMode: "system", isEnterToSend: false, coverRevealGate: "tap" } },
  });
  await page.goto("/");
  await page.getByRole("button", { name: "Settings" }).click();
  await expect(page.getByRole("region", { name: "Dex browser settings" })).toBeVisible();
  await expect(page.getByText("Developer mode")).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "Account" })).toHaveAttribute("aria-selected", "true");
  await expect(page).toHaveScreenshot(`browser-settings-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("tab", { name: "Chats" }).click();
  await page.getByLabel("Enter sends the message").check();
  await expect.poll(() => phone.frames.find((frame) => frame.t === "set_settings"))
    .toMatchObject({ t: "set_settings", patch: { dexProfile: { isEnterToSend: true } } });
  phone.send({ t: "error", ref: "set_settings", message: "Could not save." });
  await expect(page.getByLabel("Enter sends the message")).not.toBeChecked();
  await page.getByLabel("Enter sends the message").check();
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "set_settings").length).toBe(2);
  phone.send({ t: "settings", settings: { dexProfile: { isEnterToSend: true, themeMode: "system", coverRevealGate: "tap" } } });
  await expect(page.getByLabel("Enter sends the message")).toBeChecked();
  await page.getByRole("tab", { name: "Appearance" }).click();
  await page.getByLabel("Theme", { exact: true }).selectOption("dark");
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "set_settings").at(-1))
    .toMatchObject({ patch: { dexProfile: { themeMode: "dark", isEnterToSend: true } } });
  phone.send({ t: "settings", settings: { dexProfile: { isEnterToSend: true, themeMode: "dark", coverRevealGate: "tap" } } });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "Dismiss notice" }).click();
  await expect(page).toHaveScreenshot(`browser-settings-dark-${page.viewportSize()?.width ?? 0}.png`, {
    maxDiffPixelRatio: test.info().project.name.startsWith("webkit") ? 0.03 : 0.003,
  });
  await page.getByRole("tab", { name: "Privacy" }).click();
  await page.getByLabel("Reveal covered messages").selectOption("ask");
  await expect.poll(() => phone.frames.filter((frame) => frame.t === "set_settings").at(-1))
    .toMatchObject({ patch: { dexProfile: { coverRevealGate: "ask", themeMode: "dark" } } });
  phone.send({ t: "settings", settings: { dexProfile: { isEnterToSend: true, themeMode: "dark", coverRevealGate: "ask" } } });
  await page.getByRole("button", { name: "Chats", exact: true }).click();
  await page.getByRole("button", { name: /Alice/ }).click();
  phone.send({ t: "chat", view: { peer: contact.ip, contact, hasMore: false, messages: [] } });
  const composer = page.getByRole("textbox", { name: "Message" });
  await composer.fill("Enter works");
  await composer.press("Enter");
  await expect.poll(() => phone.frames.find((frame) => frame.t === "send_text"))
    .toMatchObject({ t: "send_text", peer: contact.ip, body: "Enter works" });
});
