import { Show } from "solid-js";
import { useDex } from "../../store/context";
import Dialog from "../kit/Dialog";

export default function ChangeContactIpDialog() {
  const runtime = useDex();
  const dialog = () => runtime.state.dialog?.kind === "change-contact-ip" ? runtime.state.dialog : null;
  return <Show when={dialog()}>{(current) => <Dialog title="Change contact address" onClose={() => runtime.actions.closeDialog()} actions={<>
    <button type="button" onClick={() => runtime.actions.closeDialog()}>Cancel</button>
    <button type="button" data-primary="true" disabled={current().isBusy} onClick={() => runtime.changeContactIp()}>{current().isBusy ? "Saving…" : "Save"}</button>
  </>}><p>Use their new Nebula IPv6 address. Their messages and contact details stay together.</p>
    <label for="dex-contact-new-ip">Nebula IPv6 address</label>
    <input id="dex-contact-new-ip" type="text" value={current().newIp} onInput={(event) => runtime.actions.updateChangeContactIp(event.currentTarget.value)} />
    <Show when={current().error}><p role="alert" class="dex-dialog-error">{current().error}</p></Show>
  </Dialog>}</Show>;
}
