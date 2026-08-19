// Minimal, framework-free demo: connect, print status changes to the
// console (and this page), disconnect. Exercises the same
// `@reactor-team/js-sdk` package a plain-JS consumer would `npm install`.
import { Reactor } from "@reactor-team/js-sdk";
import type { Reactor as ReactorType } from "@reactor-team/js-sdk";

const statusEl = document.querySelector<HTMLSpanElement>("#status")!;
const logEl = document.querySelector<HTMLDivElement>("#log")!;
const modelNameEl = document.querySelector<HTMLInputElement>("#modelName")!;
const apiUrlEl = document.querySelector<HTMLInputElement>("#apiUrl")!;
const localEl = document.querySelector<HTMLInputElement>("#local")!;
const jwtEl = document.querySelector<HTMLInputElement>("#jwt")!;
const apiKeyEl = document.querySelector<HTMLInputElement>("#apiKey")!;

function log(message: string): void {
  console.log(message);
  logEl.textContent += `${new Date().toLocaleTimeString()}  ${message}\n`;
  logEl.scrollTop = logEl.scrollHeight;
}

// Demo-only convenience: persist every field in this browser's localStorage
// so the form comes back as you left it. Fine for a local tool nobody else's
// browser ever loads — never do this in a real app served to users.
const STORAGE_PREFIX = "reactor-demo-";

function persistText(el: HTMLInputElement, key: string): void {
  const stored = localStorage.getItem(STORAGE_PREFIX + key);
  if (stored !== null) el.value = stored;
  el.addEventListener("input", () => localStorage.setItem(STORAGE_PREFIX + key, el.value));
}

function persistCheckbox(el: HTMLInputElement, key: string): void {
  const stored = localStorage.getItem(STORAGE_PREFIX + key);
  if (stored !== null) el.checked = stored === "true";
  el.addEventListener("change", () => localStorage.setItem(STORAGE_PREFIX + key, String(el.checked)));
}

persistCheckbox(localEl, "local");
persistText(modelNameEl, "model-name");
persistText(apiUrlEl, "api-url");
persistText(jwtEl, "jwt");
persistText(apiKeyEl, "api-key");

const generateJwtButton = document.querySelector<HTMLButtonElement>("#generateJwt")!;

// A local runtime serves exactly one, already-loaded model and takes no auth
// (see `local_start_session` in reactor-core's coordinator.rs — it never
// sends `model_name`, and there's no JWT check). `apiUrl` stays enabled in
// both modes: it's the override for a local runtime on a non-default
// host/port, not something tied to prod vs. local.
function updateFieldAvailability(): void {
  const disabledForLocal = localEl.checked;
  modelNameEl.disabled = disabledForLocal;
  jwtEl.disabled = disabledForLocal;
  apiKeyEl.disabled = disabledForLocal;
  generateJwtButton.disabled = disabledForLocal;
}

localEl.addEventListener("change", updateFieldAvailability);
updateFieldAvailability();

document.querySelector("#generateJwt")!.addEventListener("click", () => {
  void (async () => {
    const apiKey = apiKeyEl.value.trim();
    if (!apiKey) {
      log("generate JWT failed: enter an API key first");
      return;
    }

    log("generating JWT...");
    try {
      const response = await fetch("/api/generate-jwt", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ apiKey }),
      });
      const body = await response.json();
      if (!response.ok) throw new Error(body.error ?? `HTTP ${response.status}`);
      jwtEl.value = body.jwt;
      log("JWT generated and filled in");
    } catch (error) {
      log(`generate JWT failed: ${String(error)}`);
    }
  })();
});

let reactor: ReactorType | undefined;
// modelName/apiUrl/local are fixed for a Reactor's lifetime — only `jwt` can
// change after construction (via `setJwt`, called below on every connect).
// This tracks what the current `reactor`, if any, was built with, so a
// changed target (e.g. toggling `local`) rebuilds instead of silently
// reconnecting under the old settings.
let reactorTargetKey: string | undefined;

document.querySelector("#connect")!.addEventListener("click", () => {
  void (async () => {
    if (!localEl.checked && !modelNameEl.value.trim()) {
      log("connect failed: enter a model name (required against prod)");
      return;
    }
    // The wasm binding requires a non-empty modelName unconditionally, even
    // though a local runtime ignores it (serves whatever one it was started
    // with — see `local_start_session` in coordinator.rs). The field is
    // disabled while `local` is checked, so fall back instead of trusting
    // whatever's left in it.
    const modelName = localEl.checked ? modelNameEl.value.trim() || "local" : modelNameEl.value.trim();
    const apiUrl = apiUrlEl.value.trim() || undefined;
    const local = localEl.checked;
    const targetKey = JSON.stringify({ modelName, apiUrl, local });

    if (reactor && targetKey !== reactorTargetKey) {
      log("connection target changed — disposing the previous instance first");
      reactor[Symbol.dispose]();
      reactor = undefined;
    }

    // Reuse the existing instance across a disconnect/connect cycle — only
    // disposing it (`reactor[Symbol.dispose]()`) needs a fresh one.
    if (!reactor) {
      reactor = new Reactor({ modelName, apiUrl, local, jwt: jwtEl.value || undefined });
      reactorTargetKey = targetKey;
      reactor.on("statusChanged", (status) => {
        statusEl.textContent = status;
        log(`statusChanged -> ${status}`);
      });
      reactor.on("sessionIdChanged", (sessionId) => log(`sessionIdChanged -> ${sessionId}`));
      reactor.on("error", (error) => log(`error -> ${error.code}: ${error.message}`));
    } else {
      // The instance survived — still pick up whatever's in the JWT field
      // now, in case it was regenerated since the last connect.
      await reactor.setJwt(jwtEl.value || undefined);
    }

    log("connecting...");
    try {
      await reactor.connect();
      log(`connected. sessionId=${reactor.getSessionId()}`);
    } catch (error) {
      log(`connect failed: ${String(error)}`);
    }
  })();
});

document.querySelector("#disconnect")!.addEventListener("click", () => {
  void (async () => {
    if (!reactor) return;
    // Default (recoverable = false): ends the session and frees the wasm
    // client in one step — the instance stays around, but a subsequent
    // connect() rebuilds the client from scratch.
    await reactor.disconnect();
    log("disconnected");
  })();
});
