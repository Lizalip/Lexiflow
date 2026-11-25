"use strict";
const element = id => document.getElementById(id);
const identity = JSON.parse(element("identity").textContent);
const controls = [...document.querySelectorAll("article")].map(article => ({
  article, choice: article.querySelector("select"), text: article.querySelector("input"),
  reason: article.querySelector("textarea")
}));
const storageKey = "lexiflow-editor-v1:" + batch;
const proposalKinds = new Set(["", "candidate_translation", "unsuitable"]);

function snapshot() {
  const rows = [];
  controls.forEach(({choice, text, reason}, index) => {
    if (choice.value || reason.value || text.value !== text.defaultValue) {
      rows.push({index, kind: choice.value, translation: text.value, reason: reason.value});
    }
  });
  return {version: 1, batch, reviewer: element("reviewer").value, rows};
}

function restore(draft) {
  const seen = new Set();
  if (!draft || draft.version !== 1 || draft.batch !== batch || typeof draft.reviewer !== "string"
      || draft.reviewer.length > 200 || !Array.isArray(draft.rows) || draft.rows.length > controls.length) {
    throw new Error("Файл относится к другой версии очереди или имеет неверный формат");
  }
  for (const row of draft.rows) {
    if (!row || !Number.isInteger(row.index) || row.index < 0 || row.index >= controls.length
        || seen.has(row.index) || !proposalKinds.has(row.kind)
        || typeof row.translation !== "string" || row.translation.length > 1000
        || typeof row.reason !== "string" || row.reason.length > 2000) {
      throw new Error("В черновике обнаружена некорректная строка");
    }
    seen.add(row.index);
  }
  for (const {choice, text, reason} of controls) {
    choice.value = "";
    text.value = text.defaultValue;
    reason.value = "";
  }
  element("reviewer").value = draft.reviewer;
  for (const row of draft.rows) {
    const {choice, text, reason} = controls[row.index];
    choice.value = row.kind;
    text.value = row.translation;
    reason.value = row.reason;
  }
}

function update(persist = false) {
  const state = element("state").value;
  const priority = element("priority").value;
  const query = element("query").value.trim().toLocaleLowerCase();
  let visible = 0;
  const draft = snapshot();
  const drafted = new Set(draft.rows.map(row => row.index));
  controls.forEach(({article, text}, index) => {
    article.hidden = (priority && priority !== article.dataset.priority)
      || (state === "pending" && drafted.has(index)) || (state === "draft" && !drafted.has(index))
      || (query && !article.textContent.toLocaleLowerCase().includes(query)
         && !text.value.toLocaleLowerCase().includes(query));
    if (!article.hidden) visible++;
  });
  document.querySelectorAll("section").forEach(section => {
    section.hidden = !section.querySelector("article:not([hidden])");
  });
  element("counts").textContent = "Показано " + visible + " из " + controls.length
    + " · с черновиком " + drafted.size + " · без черновика " + (controls.length - drafted.size);
  if (persist) {
    try { localStorage.setItem(storageKey, JSON.stringify(draft)); }
    catch (_) { /* File URLs can disable localStorage. Explicit export still works. */ }
  }
}

function download(content, filename) {
  const url = URL.createObjectURL(new Blob([content], {type:"text/plain;charset=utf-8"}));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

document.addEventListener("input", event => update(!["query", "priority", "state", "open-draft"].includes(event.target.id)));
element("save-draft").onclick = () => download(JSON.stringify(snapshot(), null, 2), "lexiflow-review-session.json");
element("open-draft").onchange = async event => {
  try {
    const file = event.target.files[0];
    if (!file || file.size > 3 * 1024 * 1024) throw new Error("Выберите черновик размером до 3 МиБ");
    restore(JSON.parse(await file.text()));
    update(true);
    element("feedback").textContent = "Черновик восстановлен";
  } catch (failure) {
    element("feedback").textContent = failure.message;
  } finally {
    event.target.value = "";
  }
};

element("export").onclick = () => {
  const draft = snapshot();
  const reviewer = draft.reviewer.trim();
  const feedback = element("feedback");
  const lines = [];
  if (!reviewer) { feedback.textContent = "Введите имя редактора"; return; }
  for (const row of draft.rows) {
    if (!row.kind) continue;
    const reason = row.reason.trim();
    const proposed = row.kind === "candidate_translation" ? row.translation.trim() : null;
    if (!reason || proposed === "") {
      feedback.textContent = "Заполните комментарий и перевод для выбранных строк"; return;
    }
    lines.push(JSON.stringify({...identity[row.index], classification:row.kind, reason, reviewer,
      proposed_translation:proposed,decision:"proposed",method:"human"}));
  }
  if (!lines.length) { feedback.textContent = "Выберите хотя бы одно предложение"; return; }
  download(lines.join("\n") + "\n", "lexiflow-proposals.jsonl");
  feedback.textContent = "Экспортировано предложений: " + lines.length;
};

try {
  const saved = localStorage.getItem(storageKey);
  if (saved) restore(JSON.parse(saved));
} catch (_) { /* An unavailable or invalid cache does not affect the source rows. */ }
update();
