import { expect, test } from "vitest";
import { decodeFrame, encodeFrame } from "./codec";
import serverChat from "../../test/fixtures/server-chat.json";
import clientSendText from "../../test/fixtures/client-send-text.json";

test("decodes a phone hello and preserves unknown future fields", () => {
  expect(decodeFrame('{"t":"hello","me":{"name":"A","ip":"10.0.0.1"},"clientId":"c","freeBytes":42,"future":1}'))
    .toMatchObject({ t: "hello", clientId: "c" });
});

test("rejects malformed and unknown phone frames", () => {
  expect(decodeFrame("{" )).toBeNull();
  expect(decodeFrame('{"t":"hello","me":{},"clientId":"c","freeBytes":42}')).toBeNull();
  expect(decodeFrame('{"t":"unknown"}')).toBeNull();
  expect(decodeFrame('{"t":"done","what":"send_text","requestId":5}')).toBeNull();
});

test("encodes the browser discriminator and omits absent defaults", () => {
  expect(encodeFrame({ t: "send_text", peer: "10.0.0.2", body: "Hello" }))
    .toBe('{"t":"send_text","peer":"10.0.0.2","body":"Hello"}');
});

test("decodes the Kotlin server fixture", () => {
  expect(decodeFrame(JSON.stringify(serverChat))).toEqual(serverChat);
});

test("encodes the client fixture Kotlin reads", () => {
  expect(JSON.parse(encodeFrame({ t: "send_text", peer: "10.0.0.2", body: "hello", covered: true, requestId: "r1" })))
    .toEqual(clientSendText);
});

test("decodes a text completion with its request ID", () => {
  expect(decodeFrame('{"t":"done","what":"send_text","requestId":"r1"}'))
    .toEqual({ t: "done", what: "send_text", requestId: "r1" });
  expect(decodeFrame('{"t":"error","ref":"send_text","message":"blocked","requestId":"r1"}'))
    .toEqual({ t: "error", ref: "send_text", message: "blocked", requestId: "r1" });
});
