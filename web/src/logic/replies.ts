import type { DexNoticeLevel } from "../wire/models";
import { bytes } from "./format";

export interface Feedback { level: DexNoticeLevel; message: string }

export function feedbackForDone(what: string, message?: string): Feedback | null {
  switch (what) {
    case "contact_add": return { level: "info", message: "Contact added." };
    case "contact_change_ip": return { level: "info", message: "Overlay address changed." };
    case "contact_delete": return { level: "info", message: "Contact deleted, with its messages and call history." };
    case "clear_history": return { level: "info", message: "History cleared on the phone." };
    case "clear_all_history": return { level: "info", message: "Every chat was cleared on the phone." };
    case "clear_orphans": {
      const freed = message === undefined || message.trim() === "" ? NaN : Number(message);
      if (!Number.isSafeInteger(freed) || freed < 0) {
        return { level: "info", message: "Media no message refers to was removed." };
      }
      if (freed === 0) return { level: "info", message: "There was no unused media to remove." };
      return { level: "info", message: `Removed ${bytes(freed)} of media no message refers to.` };
    }
    case "check_updates": return { level: "info", message: "Checked for updates." };
    case "retry_failed": return { level: "info", message: "Retrying what failed." };
    default: return null;
  }
}
