"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

test("filters synthetic review rows and exports only explicit proposals", () => {
  const identity = [
    {entry_id:"fixture",sense_id:"sense",snapshot_sha256:"a".repeat(64),locator:"quote/1"},
    {entry_id:"fixture",sense_id:"sense",snapshot_sha256:"a".repeat(64),locator:"quote/2"},
    {entry_id:"other",sense_id:"second",snapshot_sha256:"a".repeat(64),locator:"quote/3"}
  ];
  const fields = new Map();
  const element = id => {
    if (!fields.has(id)) fields.set(id,{value:""});
    return fields.get(id);
  };
  element("identity").textContent = JSON.stringify(identity);
  const rows = identity.map((_, index) => {
    const choice = {value:""};
    const text = {value:"fixture",defaultValue:"fixture"};
    const reason = {value:""};
    return {
      dataset:{pos:index === 2 ? "n" : "v",severity:index === 0 ? "high" : "low",
        priority:"standard",category:"example",suggestion:"false"},
      textContent:"fixture",
      querySelector: tag => ({select:choice,input:text,textarea:reason})[tag]
    };
  });
  let exported;
  const context = {
    document:{
      getElementById:element, addEventListener(){},
      querySelectorAll: tag => tag === "article" ? rows : [],
      createElement: () => ({click(){}})
    },
    URL:{createObjectURL(blob){exported = blob.parts.join(""); return "blob:synthetic";},
      revokeObjectURL(){}},
    Blob:class {constructor(parts){this.parts=parts;}},
    setTimeout(){},
    localStorage:{getItem(){return null;},setItem(){}}
  };
  const source = fs.readFileSync(path.join(__dirname,"editor_browser.js"),"utf8");
  vm.createContext(context);
  vm.runInContext('const batch = "fixture";\n'+source,context);
  element("pos").value = "v";
  element("severity").value = "high";
  vm.runInContext("update()",context);
  assert.deepEqual(rows.map(row => Boolean(row.hidden)),[false,true,true]);
  element("reviewer").value = "Synthetic reviewer";
  ["candidate_translation","unsuitable","defer"].forEach((kind,index) => {
    rows[index].querySelector("select").value = kind;
    rows[index].querySelector("textarea").value = "Synthetic rationale";
  });
  rows[0].querySelector("input").value = "Fixture replacement";
  vm.runInContext("update()",context);
  assert.match(element("counts").textContent,/отложено 1/);
  element("export").onclick();
  const proposals = exported.trim().split("\n").map(line => JSON.parse(line));
  assert.deepEqual(proposals,[
    {...identity[0],classification:"candidate_translation",reason:"Synthetic rationale",reviewer:"Synthetic reviewer",
      proposed_translation:"Fixture replacement",decision:"proposed",method:"human"},
    {...identity[1],classification:"unsuitable",reason:"Synthetic rationale",reviewer:"Synthetic reviewer",
      proposed_translation:null,decision:"proposed",method:"human"}
  ]);
});
