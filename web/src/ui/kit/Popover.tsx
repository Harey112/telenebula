import { createSignal, onCleanup, onMount, type JSX } from "solid-js";
import { Portal } from "solid-js/web";
import "./Popover.css";

export interface PopoverProps {
  anchor: HTMLElement;
  onClose: () => void;
  children: JSX.Element;
  label: string;
}

export default function Popover(props: PopoverProps) {
  let panel: HTMLDivElement | undefined;
  const position = () => {
    const rect = props.anchor.getBoundingClientRect();
    const width = panel?.offsetWidth ?? 216;
    const height = panel?.offsetHeight ?? 200;
    return {
      left: `${Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))}px`,
      top: `${rect.bottom + height + 8 > window.innerHeight ? Math.max(8, rect.top - height - 4) : rect.bottom + 4}px`,
    };
  };
  const [placement, setPlacement] = createSignal(position());

  function onPointer(event: PointerEvent): void {
    if (event.target instanceof Node && !panel?.contains(event.target) && !props.anchor.contains(event.target)) props.onClose();
  }
  function onKey(event: KeyboardEvent): void {
    if (event.key === "Escape") { event.preventDefault(); props.onClose(); }
  }
  onMount(() => {
    setPlacement(position());
    panel?.querySelector<HTMLElement>("button, a")?.focus();
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    window.addEventListener("resize", props.onClose);
    window.addEventListener("scroll", props.onClose, true);
  });
  onCleanup(() => {
    document.removeEventListener("pointerdown", onPointer);
    document.removeEventListener("keydown", onKey);
    window.removeEventListener("resize", props.onClose);
    window.removeEventListener("scroll", props.onClose, true);
    props.anchor.focus();
  });

  return <Portal><div ref={panel} class="dex-popover" role="menu" aria-label={props.label}
    style={{ left: placement().left, top: placement().top }}>{props.children}</div></Portal>;
}
