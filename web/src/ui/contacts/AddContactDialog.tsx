import { createMemo } from "solid-js";
import { useDex } from "../../store/context";
import Dialog from "../kit/Dialog";

export default function AddContactDialog() {
  const runtime = useDex();
  const dialog = createMemo(() => runtime.state.dialog?.kind === "add-contact" ? runtime.state.dialog : null);
  return (
    <Dialog title="Add contact" onClose={() => { if (!dialog()?.isBusy) runtime.actions.closeDialog(); }} actions={
      <>
        <button type="button" disabled={dialog()?.isBusy} onClick={() => runtime.actions.closeDialog()}>Cancel</button>
        <button type="submit" form="dex-add-contact" disabled={dialog()?.isBusy}>Add contact</button>
      </>
    }>
      <form id="dex-add-contact" onSubmit={(event) => { event.preventDefault(); runtime.addContact(); }}>
        <label for="dex-contact-ip">Nebula IPv6 address</label>
        <input id="dex-contact-ip" type="text" autocomplete="off" spellcheck={false}
          value={dialog()?.ip ?? ""}
          onInput={(event) => runtime.actions.updateAddContact("ip", event.currentTarget.value)} />
        <label for="dex-contact-nickname">Nickname</label>
        <input id="dex-contact-nickname" type="text" autocomplete="off"
          value={dialog()?.nickname ?? ""}
          onInput={(event) => runtime.actions.updateAddContact("nickname", event.currentTarget.value)} />
        <label for="dex-contact-notes">Notes</label>
        <textarea id="dex-contact-notes" value={dialog()?.notes ?? ""}
          onInput={(event) => runtime.actions.updateAddContact("notes", event.currentTarget.value)} />
        <p class="dex-dialog-error" role="alert">{dialog()?.error}</p>
      </form>
    </Dialog>
  );
}
