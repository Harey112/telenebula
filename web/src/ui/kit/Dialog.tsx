import { onCleanup, onMount, type JSX } from "solid-js";
import { Portal } from "solid-js/web";
import "./Dialog.css";

export interface DialogProps {
  title: string;
  onClose: () => void;
  children: JSX.Element;
  actions: JSX.Element;
}

export default function Dialog(props: DialogProps) {
  let panel: HTMLDivElement | undefined;
  let opener: HTMLElement | null = null;

  function focusable(): HTMLElement[] {
    return panel ? [...panel.querySelectorAll<HTMLElement>("button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), [href], [tabindex]:not([tabindex='-1'])")]
      .filter((element) => element.getClientRects().length > 0) : [];
  }

  function onKey(event: KeyboardEvent): void {
    if (event.key === "Escape") {
      event.preventDefault();
      props.onClose();
      return;
    }
    if (event.key !== "Tab") return;
    const items = focusable();
    const first = items[0];
    const last = items[items.length - 1];
    if (!first || !last) {
      event.preventDefault();
    } else if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  onMount(() => {
    opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    document.addEventListener("keydown", onKey);
    queueMicrotask(() => (focusable().find((element) => element instanceof HTMLInputElement) ?? focusable()[0])?.focus());
  });
  onCleanup(() => {
    document.removeEventListener("keydown", onKey);
    opener?.focus();
  });

  return (
    <Portal>
      <div class="dex-dialog-backdrop" onClick={(event) => { if (event.target === event.currentTarget) props.onClose(); }}>
        <div ref={panel} class="dex-dialog" role="dialog" aria-modal="true" aria-labelledby="dex-dialog-title">
          <header><h2 id="dex-dialog-title">{props.title}</h2></header>
          <div class="dex-dialog-body">{props.children}</div>
          <footer>{props.actions}</footer>
        </div>
      </div>
    </Portal>
  );
}
