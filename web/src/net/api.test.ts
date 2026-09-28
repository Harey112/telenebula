import { afterEach, describe, expect, it, vi } from "vitest";
import { attachmentUrl, login, session, uploadUrl } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("Dex HTTP API", () => {
  it("maps login refusal codes without consuming response bodies", async () => {
    const fetcher = vi.fn().mockResolvedValueOnce({ status: 401 })
      .mockResolvedValueOnce({ status: 429 }).mockResolvedValueOnce({ status: 503 });
    vi.stubGlobal("fetch", fetcher);
    expect(await login("name", "password")).toEqual({ kind: "wrong" });
    expect(await login("name", "password")).toEqual({ kind: "locked" });
    expect(await login("name", "password")).toEqual({ kind: "client-limit" });
    expect(fetcher).toHaveBeenCalledWith("/api/login", expect.objectContaining({
      credentials: "same-origin", cache: "no-store", method: "POST",
      body: JSON.stringify({ username: "name", password: "password" }),
    }));
  });

  it("rejects an invalid active session", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ status: 200, json: () => ({ name: "name" }) }));
    expect(await session()).toEqual({ kind: "failed", message: "The phone sent an invalid session" });
  });

  it("encodes upload fields and attachment identifiers", () => {
    const request = {
      peer: "10.0.0.1", name: "a & b.png", mime: "image/png", body: new Blob(),
      replyTo: "reply/id", isCovered: true, isVoice: false, width: 100,
    };
    const url = new URL(uploadUrl(request), "https://example.test");
    expect(url.pathname).toBe("/a");
    expect(url.searchParams.get("name")).toBe("a & b.png");
    expect(url.searchParams.get("reply")).toBe("reply/id");
    expect(url.searchParams.get("cover")).toBe("1");
    expect(url.searchParams.has("voice")).toBe(false);
    expect(attachmentUrl("a/b")).toBe("/a/a%2Fb");
  });
});
