import { expect, test } from "vitest";
import { bytes, duration, isMuted, remaining } from "./format";

test("formats storage, clips and countdowns like the existing browser", () => {
  expect(bytes(2048)).toBe("2.0 KB");
  expect(duration(3_661_000)).toBe("1:01:01");
  expect(remaining(100_000, 0)).toBe("1m");
  expect(isMuted(-1, 100)).toBe(true);
  expect(isMuted(50, 100)).toBe(false);
});
