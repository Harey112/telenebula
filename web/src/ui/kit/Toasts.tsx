import { For, Show } from "solid-js";
import { Portal } from "solid-js/web";
import { useDex } from "../../store/context";
import { Icon } from "../icons.gen";
import "./Toasts.css";

export default function Toasts() {
  const runtime = useDex();
  return (
    <Show when={runtime.state.toasts.length > 0}>
      <Portal>
        <div class="dex-toasts" aria-live="polite">
          <For each={runtime.state.toasts}>{(toast) =>
            <div class="dex-toast" data-level={toast.level} role={toast.level === "error" ? "alert" : "status"}>
              <span>{toast.message}</span>
              <button type="button" aria-label="Dismiss notice" onClick={() => runtime.actions.dismissToast(toast.id)}>
                <Icon name="close" size={16} />
              </button>
            </div>
          }</For>
        </div>
      </Portal>
    </Show>
  );
}
