import { createEffect, onCleanup, onMount, Show } from "solid-js";
import { shownProfile } from "../logic/effective";
import { createDexRuntime } from "../store/runtime";
import { DexContext } from "../store/context";
import Login from "./login/Login";
import Shell from "./shell/Shell";
import "./theme.css";

const accents: Record<string, [string, string]> = {
  sky: ["#5FA3DB", "#7FB7E6"], forest: ["#4C9A6A", "#6FC291"], amber: ["#C8862A", "#E0A94E"],
  rose: ["#C85C7A", "#E58AA3"], violet: ["#7A63C8", "#A38FE5"], slate: ["#5A6B7C", "#8AA0B4"],
};

export default function App() {
  const runtime = createDexRuntime();
  onMount(() => { void runtime.boot(); });
  createEffect(() => {
    const profile = shownProfile(runtime.state.profileEdits, runtime.state.settings?.dexProfile ?? {});
    const root = document.documentElement;
    root.dataset.theme = profile.themeMode;
    root.dataset.text = profile.chatTextSize;
    root.dataset.density = profile.messageDensity;
    const palette = profile.colorTheme === "custom" && /^#[\da-fA-F]{6}$/.test(profile.customAccent)
      ? [profile.customAccent, profile.customAccent] : accents[profile.colorTheme] ?? accents.sky;
    if (palette) {
      root.style.setProperty("--accent-light", palette[0] ?? "");
      root.style.setProperty("--accent-dark", palette[1] ?? "");
    }
  });
  onCleanup(() => {
    delete document.documentElement.dataset.theme;
    delete document.documentElement.dataset.text;
    delete document.documentElement.dataset.density;
    document.documentElement.style.removeProperty("--accent-light");
    document.documentElement.style.removeProperty("--accent-dark");
    runtime.dispose();
  });
  return (
    <DexContext.Provider value={runtime}>
      <Show when={runtime.state.screen === "app"} fallback={
        <Show when={runtime.state.screen === "login"} fallback={<main class="dex-loading" role="status">Connecting to the phone…</main>}>
          <Login />
        </Show>
      }>
        <Shell />
      </Show>
    </DexContext.Provider>
  );
}
