import { createMemo } from "solid-js";
import { useDex } from "../../store/context";
import Dialog from "../kit/Dialog";

export default function DeleteContactDialog() {
  const runtime = useDex();
  const dialog = createMemo(() => runtime.state.dialog?.kind === "delete-contact" ? runtime.state.dialog : null);
  return <Dialog title="Delete contact" onClose={() => { if (!dialog()?.isBusy) runtime.actions.closeDialog(); }} actions={<>
    <button type="button" disabled={dialog()?.isBusy} onClick={() => runtime.actions.closeDialog()}>Cancel</button>
    <button type="button" class="dex-dialog-danger" disabled={dialog()?.isBusy} onClick={() => runtime.deleteContact()}>Delete contact</button>
  </>}>
    <p>Delete {dialog()?.label}? This removes the contact, every message with them and their call history from the phone. It cannot be undone.</p>
    <p class="dex-dialog-error" role="alert">{dialog()?.error}</p>
  </Dialog>;
}
