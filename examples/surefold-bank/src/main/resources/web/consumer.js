// The consumer page: pick or open an account, send money from it, and follow each transfer by asking until it ends.
const $ = (id) => document.getElementById(id);
let current = null;

async function call(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: response.status, json: await response.json() };
}

function say(text) {
  $("message").textContent = text || "";
}

function render(statement) {
  current = statement.id;
  $("account").hidden = false;
  $("account-name").textContent = statement.id;
  $("balance").textContent = statement.balance;
  const rows = statement.movements.map((movement) => {
    const row = document.createElement("tr");
    const id = document.createElement("td");
    id.className = "id";
    id.textContent = movement.transfer;
    const amount = document.createElement("td");
    amount.className = "number " + (movement.amount < 0 ? "out" : "in");
    amount.textContent = (movement.amount > 0 ? "+" : "") + movement.amount;
    row.append(id, amount);
    return row;
  });
  $("movements").tBodies[0].replaceChildren(...rows);
}

async function show(id) {
  const { status, json } = await call("GET", `/api/accounts/${encodeURIComponent(id)}`);
  if (status === 200) {
    say("");
    render(json);
  } else {
    say(json.error);
  }
}

async function open() {
  const id = $("account-id").value.trim();
  const amount = Number($("open-amount").value || 0);
  const { status, json } = await call("POST", "/api/accounts", { id, amount });
  if (status === 200) await show(id);
  else say(json.error);
}

function status(text) {
  const shown = $("transfer-status");
  shown.textContent = text;
  shown.className = "status " + text;
}

const ended = (text) => text === "Done" || text === "Refused";

async function follow(id) {
  let now = "Requested";
  while (!ended(now)) {
    await new Promise((wake) => setTimeout(wake, 200));
    const { status: code, json } = await call("GET", `/api/transfers/${id}`);
    if (code === 200) now = json.status;
    status(now);
  }
  await show(current);
}

async function send(event) {
  event.preventDefault();
  const button = $("send");
  button.disabled = true;
  const body = { from: current, to: $("to").value.trim(), amount: Number($("amount").value) };
  const { status: code, json } = await call("POST", "/api/transfers", body);
  if (code === 202) {
    say("");
    status("Requested");
    await follow(json.id);
  } else {
    say(json.error);
  }
  button.disabled = false;
}

$("pick").addEventListener("submit", (event) => {
  event.preventDefault();
  show($("account-id").value.trim());
});
$("open").addEventListener("click", open);
$("send-form").addEventListener("submit", send);
