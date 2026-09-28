import { createContext, useContext } from "solid-js";
import type { DexRuntime } from "./runtime";

export const DexContext = createContext<DexRuntime>();

export function useDex(): DexRuntime {
  const runtime = useContext(DexContext);
  if (!runtime) throw new Error("Dex context is missing");
  return runtime;
}
