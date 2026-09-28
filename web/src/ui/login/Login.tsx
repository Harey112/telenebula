import { createSignal, onMount } from "solid-js";
import { useDex } from "../../store/context";
import { Icon } from "../icons.gen";
import "./Login.css";

export default function Login() {
  const runtime = useDex();
  const [username, setUsername] = createSignal("");
  const [password, setPassword] = createSignal("");
  let usernameInput: HTMLInputElement | undefined;
  onMount(() => usernameInput?.focus());

  function submit(event: SubmitEvent): void {
    event.preventDefault();
    void runtime.signIn(username(), password());
  }

  return (
    <main class="dex-login">
      <form class="dex-login-card" onSubmit={submit}>
        <span class="dex-login-logo"><Icon name="desktop" size={28} /></span>
        <h1>TeleNebula Dex</h1>
        <p>Sign in with the username and password set on your phone.</p>
        <label for="dex-username">Username</label>
        <input id="dex-username" ref={usernameInput} type="text" autocomplete="username"
          value={username()} onInput={(event) => setUsername(event.currentTarget.value)} />
        <label for="dex-password">Password</label>
        <input id="dex-password" type="password" autocomplete="current-password"
          value={password()} onInput={(event) => setPassword(event.currentTarget.value)} />
        <span class="dex-login-error" role="alert">{runtime.state.loginError}</span>
        <button type="submit" disabled={runtime.state.isLoginBusy}>Log in</button>
      </form>
    </main>
  );
}
