import { expect, test } from "vitest";
import { feedbackForDone } from "./replies";

test("background acknowledgements do not toast Done", () => {
  for (const what of ["watch", "set_settings", "typing", "mark_read", "contact_privacy", "contact_notifications"]) {
    expect(feedbackForDone(what)).toBeNull();
  }
});

test("visible operations get specific confirmation", () => {
  expect(feedbackForDone("contact_add")?.message).toBe("Contact added.");
  expect(feedbackForDone("clear_orphans", "0")?.message).toBe("There was no unused media to remove.");
  expect(feedbackForDone("clear_orphans", "2048")?.message).toContain("2.0 KB");
});
