// Tiny Chrome DevTools Protocol client for the app's WebView (Node 22, no deps).
// Needs `adb forward tcp:9222 ...` (phone.sh devtools does it).
//   node cdp.mjs eval '<js>'      run JS in the page, print the value
//   node cdp.mjs console [secs]   print console output and exceptions for N seconds (default 10)
const BASE = "http://localhost:9222";
const [cmd, arg] = process.argv.slice(2);
const die = (msg) => { console.error(msg); process.exit(1); };

if (!["eval", "console"].includes(cmd) || (cmd === "eval" && !arg)) die("usage: cdp.mjs eval '<js>' | console [seconds]");

let targets;
try { targets = await (await fetch(`${BASE}/json`, { signal: AbortSignal.timeout(5000) })).json(); }
catch (e) { die(`no DevTools at ${BASE} (${e.message}); run: phone.sh devtools`); }
const page = targets.find((t) => t.type === "page" && t.webSocketDebuggerUrl);
if (!page) die("no page target; is the app open? targets: " + JSON.stringify(targets.map((t) => [t.type, t.url])));

const ws = new WebSocket(page.webSocketDebuggerUrl);
let nextId = 1;
const pending = new Map();
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});
const fmtArg = (a) => a.value !== undefined ? (typeof a.value === "string" ? a.value : JSON.stringify(a.value)) : (a.description ?? a.type);
const excText = (d) => d?.exception?.description ?? d?.text ?? "exception";

ws.onmessage = (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id); pending.delete(m.id);
    m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result);
  } else if (m.method === "Runtime.consoleAPICalled") {
    console.log(`[${m.params.type}] ${m.params.args.map(fmtArg).join(" ")}`);
  } else if (m.method === "Runtime.exceptionThrown") {
    console.log(`[exception] ${excText(m.params.exceptionDetails)}`);
  }
};
ws.onerror = () => die("websocket error (is another DevTools client attached?)");
await new Promise((r) => (ws.onopen = r));

if (cmd === "eval") {
  const timer = setTimeout(() => die("timeout after 20 s"), 20000);
  try {
    const r = await send("Runtime.evaluate", { expression: arg, awaitPromise: true, returnByValue: true });
    clearTimeout(timer);
    if (r.exceptionDetails) die("exception: " + excText(r.exceptionDetails));
    const v = r.result;
    console.log(v.type === "undefined" ? "undefined" : JSON.stringify(v.value ?? v.description ?? null, null, 2));
    process.exit(0);
  } catch (e) { die("error: " + e.message); }
} else {
  await send("Runtime.enable");
  const secs = Number(arg) || 10;
  console.error(`listening ${secs}s on ${page.url}`);
  setTimeout(() => process.exit(0), secs * 1000);
}
