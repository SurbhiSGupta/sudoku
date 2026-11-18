// Photographs one page as a phone: 432 x 864 at 2.5x = 1080 x 2160.
// Usage: node capture.mjs <url> <out.png> <port>
import { writeFileSync } from "node:fs";

const [url, out, port = "9333"] = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let targets;
for (let i = 0; i < 50; i++) {
  try {
    targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
    if (targets.some((t) => t.type === "page")) break;
  } catch (e) {}
  await sleep(200);
}
const page = targets.find((t) => t.type === "page");
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener("open", r));
let id = 0;
const pending = new Map();
ws.addEventListener("message", (e) => {
  const msg = JSON.parse(e.data);
  if (pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});
const send = (method, params = {}) =>
  new Promise((resolve) => {
    pending.set(++id, resolve);
    ws.send(JSON.stringify({ id, method, params }));
  });

await send("Emulation.setDeviceMetricsOverride", {
  width: 432,
  height: 864,
  deviceScaleFactor: 2.5,
  mobile: true,
});
await send("Page.enable");
await send("Page.navigate", { url });
await sleep(4500); // let the scene set up and the animations settle
const shot = await send("Page.captureScreenshot", { format: "png" });
writeFileSync(out, Buffer.from(shot.result.data, "base64"));
ws.close();
