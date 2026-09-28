import { createMemo, For, Show } from "solid-js";
import { contactLabel, contactRows, contactSecondary } from "../../logic/contact-lists";
import { useDex } from "../../store/context";
import { Icon } from "../icons.gen";
import AddContactDialog from "./AddContactDialog";
import ContactDetail from "./ContactDetail";
import DeleteContactDialog from "./DeleteContactDialog";
import EditContactDialog from "./EditContactDialog";
import ChangeContactIpDialog from "./ChangeContactIpDialog";
import "./Contacts.css";

export default function Contacts() {
  const runtime = useDex();
  const rows = createMemo(() => contactRows(runtime.state.contacts, runtime.state.contactSearch));
  return (
    <section class="dex-contacts-pane" data-contact-open={runtime.state.selectedContact !== null}>
      <div class="dex-contacts-side">
      <header><h1>Contacts</h1><button type="button" onClick={() => runtime.actions.openAddContact()}>
        <Icon name="person_add" size={18} /> Add contact
      </button></header>
      <label class="dex-chat-search"><Icon name="search" size={18} />
        <input type="search" aria-label="Search contacts" placeholder="Search contacts"
          value={runtime.state.contactSearch}
          onInput={(event) => runtime.actions.setContactSearch(event.currentTarget.value)} />
      </label>
      <div class="dex-contacts-list">
        <For each={rows()} fallback={<p class="dex-list-empty">{runtime.state.contactSearch.trim() ? "No contacts match." : "No contacts yet. Add someone to start a chat."}</p>}>
          {(contact) => <button type="button" class="dex-contact-row"
            aria-current={runtime.state.selectedContact === contact.ip ? "true" : undefined}
            onClick={() => runtime.selectContact(contact.ip)}>
            <span class="dex-chat-avatar" aria-hidden="true">{contactLabel(contact).slice(0, 1).toLocaleUpperCase()}</span>
            <span><strong>{contactLabel(contact)}</strong><Show when={contactSecondary(contact)}><small>{contactSecondary(contact)}</small></Show></span>
            <Icon name="chevron_right" size={18} />
          </button>}
        </For>
      </div>
      </div>
      <ContactDetail />
      <Show when={runtime.state.dialog?.kind === "add-contact"}><AddContactDialog /></Show>
      <Show when={runtime.state.dialog?.kind === "edit-contact"}><EditContactDialog /></Show>
      <Show when={runtime.state.dialog?.kind === "delete-contact"}><DeleteContactDialog /></Show>
      <Show when={runtime.state.dialog?.kind === "change-contact-ip"}><ChangeContactIpDialog /></Show>
    </section>
  );
}
