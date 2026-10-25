// The admin page: one EventSource on this node's /admin/stream, which carries every node's stats and events.
const $ = (id) => document.getElementById(id);
const nodes = new Map(); // name -> { status, stats, seen }
const history = []; // the cluster's transfers a second, one a second, oldest first
const HISTORY = 300;
const FRESH_MS = 3000;
const FEED = 20;
let loading = false;

function node(name) {
  if (!nodes.has(name)) nodes.set(name, { status: "Up", stats: null, seen: 0 });
  return nodes.get(name);
}

// Stats from a node whose last word is older than FRESH_MS are no longer the cluster's.
function fresh() {
  const now = Date.now();
  return [...nodes.values()].filter((n) => n.stats && now - n.seen < FRESH_MS).map((n) => n.stats);
}

function tiles() {
  const stats = fresh();
  $("tps").textContent = stats.reduce((sum, s) => sum + s.transfersPerSecond, 0);
  $("refused").textContent = stats.reduce((sum, s) => sum + s.refused, 0);
  $("p99").textContent = stats.reduce((most, s) => Math.max(most, s.p99Ms), 0);
}

function cell(text, className) {
  const td = document.createElement("td");
  td.textContent = text;
  if (className) td.className = className;
  return td;
}

function table() {
  const rows = [...nodes.keys()].sort().map((name) => {
    const n = nodes.get(name);
    const s = n.stats || {};
    const row = document.createElement("tr");
    row.id = `node-${name}`;
    row.append(
      cell(name),
      cell(n.status, `status ${n.status}`),
      cell(s.shards ?? "", "number"),
      cell(s.entities ?? "", "number"),
      cell(s.unconfirmed ?? "", "number"),
      cell(s.deadLetters ?? "", "number"),
    );
    return row;
  });
  $("nodes").replaceChildren(...rows);
}

function feed(transfer) {
  const row = document.createElement("tr");
  row.append(
    cell(transfer.from, "id"),
    cell(transfer.to, "id"),
    cell(transfer.amount, "number"),
    cell(transfer.outcome, `status ${transfer.outcome}`),
    cell(transfer.ms, "number"),
  );
  const body = $("feed");
  body.prepend(row);
  while (body.rows.length > FEED) body.deleteRow(body.rows.length - 1);
}

// A line of the last five minutes, drawn by hand, scaled to the busiest second in it.
function sparkline() {
  const canvas = $("sparkline");
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const height = canvas.clientHeight;
  canvas.width = width * ratio;
  canvas.height = height * ratio;
  const g = canvas.getContext("2d");
  g.scale(ratio, ratio);
  g.clearRect(0, 0, width, height);
  const style = getComputedStyle(document.documentElement);
  const most = Math.max(1, ...history);
  g.strokeStyle = style.getPropertyValue("--line");
  g.beginPath();
  g.moveTo(0, height - 0.5);
  g.lineTo(width, height - 0.5);
  g.stroke();
  g.fillStyle = style.getPropertyValue("--muted");
  g.font = "11px system-ui, sans-serif";
  g.fillText(`${most}/s`, 4, 12);
  if (history.length < 2) return;
  const step = width / (HISTORY - 1);
  const x0 = width - (history.length - 1) * step;
  g.strokeStyle = style.getPropertyValue("--accent");
  g.lineWidth = 1.5;
  g.beginPath();
  history.forEach((value, i) => {
    const x = x0 + i * step;
    const y = height - 2 - (value / most) * (height - 18);
    if (i === 0) g.moveTo(x, y);
    else g.lineTo(x, y);
  });
  g.stroke();
}

const source = new EventSource("/admin/stream");
source.onopen = () => ($("stream").textContent = "live");
source.onerror = () => ($("stream").textContent = "reconnecting…");
source.addEventListener("stats", (event) => {
  const stats = JSON.parse(event.data);
  const n = node(stats.node);
  n.stats = stats;
  n.seen = Date.now();
  tiles();
  table();
});
source.addEventListener("member", (event) => {
  const member = JSON.parse(event.data);
  node(member.node).status = member.status === "Reachable" ? "Up" : member.status;
  table();
});
source.addEventListener("transfer", (event) => feed(JSON.parse(event.data)));

setInterval(() => {
  tiles();
  history.push(Number($("tps").textContent));
  if (history.length > HISTORY) history.shift();
  sparkline();
}, 1000);

async function post(path, body) {
  const response = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return response.status;
}

$("load").addEventListener("click", async () => {
  if ((await post("/api/load", { on: !loading })) === 202) {
    loading = !loading;
    $("load").textContent = loading ? "Stop load" : "Start load";
  }
});
$("crash").addEventListener("click", async () => {
  $("crash").disabled = (await post("/api/crash", { node: "n3" })) === 202;
});
sparkline();
