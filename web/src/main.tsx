import { render } from "solid-js/web";
import App from "./ui/App";

const root = document.getElementById("app");
if (root === null) throw new Error("Dex mount point is missing");
render(() => <App />, root);
