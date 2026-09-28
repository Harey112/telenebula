import type { DexContact } from "../wire/models";

export function contactLabel(contact: DexContact): string {
  return contact.contactLabel || contact.name || contact.nickname || contact.ip;
}

export function contactSecondary(contact: DexContact): string | null {
  return contact.nickname && contact.nickname !== contactLabel(contact) ? contact.nickname : null;
}

export function contactRows(contacts: readonly DexContact[], search: string): DexContact[] {
  const query = search.trim().toLocaleLowerCase();
  return contacts.filter((contact) => !query
    || [contactLabel(contact), contact.name, contact.nickname ?? "", contact.ip]
      .some((value) => value.toLocaleLowerCase().includes(query)))
    .sort((left, right) => (right.lastSeenAt ?? 0) - (left.lastSeenAt ?? 0)
      || contactLabel(left).localeCompare(contactLabel(right)));
}

export function contactsForNewMessage(contacts: readonly DexContact[], search: string): DexContact[] {
  const query = search.trim().toLocaleLowerCase();
  return contacts.filter((contact) => !query
    || [contact.label, contact.name, contact.ip].some((value) => value.toLocaleLowerCase().includes(query)))
    .sort((left, right) => (right.lastSeenAt ?? 0) - (left.lastSeenAt ?? 0)
      || left.label.localeCompare(right.label));
}
