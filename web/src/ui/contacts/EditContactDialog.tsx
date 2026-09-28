import { createMemo } from "solid-js";
import { useDex } from "../../store/context";
import Dialog from "../kit/Dialog";

export default function EditContactDialog() {
  const runtime = useDex();
  const dialog = createMemo(() => runtime.state.dialog?.kind === "edit-contact" ? runtime.state.dialog : null);
  return <Dialog title="Edit contact" onClose={() => { if (!dialog()?.isBusy) runtime.actions.closeDialog(); }} actions={
    <>
      <button type="button" disabled={dialog()?.isBusy} onClick={() => runtime.actions.closeDialog()}>Cancel</button>
      <button type="submit" form="dex-edit-contact" disabled={dialog()?.isBusy}>Save</button>
    </>
  }>
    <form id="dex-edit-contact" onSubmit={(event) => { event.preventDefault(); runtime.editContact(); }}>
      <label for="dex-edit-nickname">Nickname</label>
      <input id="dex-edit-nickname" type="text" autocomplete="off"
        value={dialog()?.nickname ?? ""}
        onInput={(event) => runtime.actions.updateEditContact("nickname", event.currentTarget.value)} />
      <label for="dex-edit-notes">Notes</label>
      <textarea id="dex-edit-notes" value={dialog()?.notes ?? ""}
        onInput={(event) => runtime.actions.updateEditContact("notes", event.currentTarget.value)} />
      <p class="dex-dialog-error" role="alert">{dialog()?.error}</p>
    </form>
  </Dialog>;
}
