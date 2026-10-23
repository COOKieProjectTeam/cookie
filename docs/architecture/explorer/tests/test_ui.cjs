/* Run: node --test docs/architecture/explorer/tests/test_ui.cjs
 *
 * Executes the unchanged browser script through its DOM events and URL entry
 * points. This small DOM records rendered markup; browser QA still owns layout,
 * focus behavior and the accessibility tree.
 */
"use strict";

const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const explorer = path.resolve(__dirname, "..");
const appScript = readFileSync(path.join(explorer, "app.js"), "utf8");
const dataScript = readFileSync(path.join(explorer, "data.js"), "utf8");

function decode(value) {
  return value.replace(/&(?:amp|lt|gt|quot|#39);/g, entity => ({
    "&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&#39;": "'",
  })[entity]);
}

function visibleText(markup) {
  // Raw JSON is a useful fallback, but must not mask omissions in the readable UI.
  return decode(markup.replace(/<details class="raw-schema">[\s\S]*?<\/details>/g, "")
    .replace(/<[^>]*>/g, " ")).replace(/\s+/g, " ").trim();
}

function dataSnapshot() {
  const context = vm.createContext({ window: {} });
  vm.runInContext(dataScript, context);
  return JSON.parse(JSON.stringify(context.window.ARCHITECTURE_DATA));
}

function harness(data = dataSnapshot(), initialHash = "#map") {
  const byId = new Map();
  const pending = [];
  let document;

  class Element {
    constructor(tagName = "div", attributes = {}) {
      this.tagName = tagName.toUpperCase();
      this.attributes = attributes;
      this.dataset = Object.fromEntries(Object.entries(attributes)
        .filter(([name]) => name.startsWith("data-"))
        .map(([name, value]) => [name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase()), value]));
      this.listeners = new Map();
      this.innerHTML = "";
      this.textContent = "";
      this.value = "";
      this.checked = false;
      this.open = false;
      this.disabled = Object.hasOwn(attributes, "disabled");
      this.classList = { toggle() {}, add() {}, remove() {} };
    }
    addEventListener(type, listener) {
      this.listeners.set(type, [...(this.listeners.get(type) || []), listener]);
    }
    dispatchEvent(event) {
      event.target ||= this;
      event.preventDefault ||= () => { event.defaultPrevented = true; };
      for (const listener of this.listeners.get(event.type) || []) listener(event);
      return !event.defaultPrevented;
    }
    getAttribute(name) { return this.attributes[name] ?? null; }
    setAttribute(name, value) { this.attributes[name] = String(value); }
    matches(selector) {
      const tag = selector.match(/^[a-z][\w-]*/i)?.[0];
      if (tag && tag.toUpperCase() !== this.tagName) return false;
      for (const [, name] of selector.matchAll(/\.([\w-]+)/g)) {
        if (!(this.attributes.class || "").split(/\s+/).includes(name)) return false;
      }
      for (const [, name, operator, double, single, bare] of selector.matchAll(
        /\[([\w-]+)(?:(\^?=)(?:"([^"]*)"|'([^']*)'|([^\]]+)))?\]/g,
      )) {
        const actual = this.getAttribute(name);
        const expected = double ?? single ?? bare;
        if (actual === null || (operator === "=" && actual !== expected)
          || (operator === "^=" && !actual.startsWith(expected))) return false;
      }
      return true;
    }
    closest(selector) { return this.matches(selector) ? this : null; }
    querySelectorAll(selector) { return tags(this.innerHTML).filter(node => node.matches(selector)); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    focus() { document.activeElement = this; }
    showModal() { this.open = true; }
    close() { this.open = false; }
    scrollIntoView() {}
  }

  function element(id) {
    if (!byId.has(id)) byId.set(id, new Element("div", { id }));
    return byId.get(id);
  }

  function tags(markup) {
    return [...markup.matchAll(/<([a-z][\w-]*)(\s[^>]*|)>/gi)].map(([, tag, rest]) => {
      const attributes = {};
      for (const [, name, double, single] of rest.matchAll(
        /([\w:-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'))?/g,
      )) attributes[name] = decode(double ?? single ?? "");
      return new Element(tag, attributes);
    });
  }

  document = new Element("document");
  document.body = new Element("body");
  document.activeElement = document.body;
  document.getElementById = element;
  document.querySelectorAll = selector => [...byId.values()]
    .flatMap(node => tags(node.innerHTML)).filter(node => node.matches(selector));
  document.querySelector = selector => document.querySelectorAll(selector)[0] || null;
  const window = new Element("window");
  window.ARCHITECTURE_DATA = data;
  let hash = initialHash;
  const location = {
    get hash() { return hash; },
    set hash(value) {
      if (value !== hash) {
        hash = value;
        pending.push(() => window.dispatchEvent({ type: "hashchange" }));
      }
    },
  };
  const context = vm.createContext({
    window, document, location, URLSearchParams, console,
    setTimeout: () => 1, clearTimeout() {},
    requestAnimationFrame: callback => { pending.push(callback); },
    MouseEvent: class { constructor(type, options) { Object.assign(this, { type }, options); } },
    CSS: { escape: value => String(value) },
  });
  vm.runInContext(appScript, context, { filename: "app.js" });

  function flush() {
    for (let count = 0; pending.length; count++) {
      assert.ok(count < 30, "UI event queue should settle");
      pending.shift()();
    }
  }
  function click(selector, container = "main") {
    const target = tags(element(container).innerHTML).find(node => node.matches(selector));
    assert.ok(target, `Rendered control ${selector} exists in ${container}`);
    const event = { type: "click", target };
    document.dispatchEvent(event);
    if (!event.defaultPrevented && target.tagName === "A") location.hash = target.getAttribute("href");
    flush();
  }
  flush();
  return {
    data, element, click,
    markup: () => element("main").innerHTML,
    hash: () => location.hash,
    navigate(value) { location.hash = value; flush(); },
    togglePlan(checked) {
      element("show-planned").checked = checked;
      element("show-planned").dispatchEvent({ type: "change" });
      flush();
    },
    search(value) {
      element("search").value = value;
      element("search").dispatchEvent({ type: "input" });
      flush();
    },
  };
}

function catalogMarkup(ui) {
  return ui.markup().split('<div class="catalog-list">')[1]?.split('<article class="detail-panel">')[0] || "";
}

function catalogIds(ui) {
  return [...catalogMarkup(ui).matchAll(/<a href="([^"]+)" class="catalog-item/g)]
    .map(([, href]) => new URLSearchParams(decode(href).split("?")[1]).get("item"));
}

function catalogGroups(ui) {
  return [...catalogMarkup(ui).matchAll(/<section class="catalog-group" data-owner="([^"]+)"[^>]*>([\s\S]*?)<\/section>/g)]
    .map(([, owner, markup]) => ({
      owner: decode(owner), markup,
      ids: [...markup.matchAll(/<a href="([^"]+)" class="catalog-item/g)]
        .map(([, href]) => new URLSearchParams(decode(href).split("?")[1]).get("item")),
    }));
}

function detailMarkup(ui) {
  return ui.markup().split('<article class="detail-panel">')[1]?.split("</article>")[0] || "";
}

function scenarioSnapshot(data) {
  return data.details.flatMap(detail => detail.flows.map(flow => ({ ...flow, service: detail.serviceId })));
}

function propertyNames(schema) {
  if (!schema || typeof schema !== "object") return [];
  return [
    ...Object.keys(schema.properties || {}),
    ...Object.values(schema.properties || {}).flatMap(propertyNames),
    ...propertyNames(schema.items),
    ...["allOf", "oneOf", "anyOf"].flatMap(key => (schema[key] || []).flatMap(propertyNames)),
  ];
}

test("deep links honor service filters across API, events and infrastructure facts", () => {
  const ui = harness();
  for (const service of ui.data.services.filter(value => value.status === "implemented")) {
    ui.navigate(`#api?service=${service.id}`);
    assert.deepEqual(catalogIds(ui).sort(),
      ui.data.operations.filter(value => value.service === service.id).map(value => value.id).sort());
    ui.navigate(`#events?service=${service.id}&plan=1`);
    assert.deepEqual(catalogIds(ui).sort(), ui.data.events
      .filter(value => value.producer === service.id || value.consumers.includes(service.id))
      .map(value => value.type).sort());
  }
  ui.navigate("#facts?service=infrastructure");
  const shownFacts = [...ui.markup().matchAll(/class="fact" id="fact-([^"]+)"/g)].map(([, id]) => id);
  assert.deepEqual(shownFacts.sort(),
    ui.data.details.find(value => value.serviceId === "infrastructure").facts.map(value => value.id).sort());
  ui.navigate("#unknown?service=nonexistent");
  assert.match(ui.markup(), /<h1>Карта системы<\/h1>/);
});

test("catalogs group every item once by its owner and distinguish API visibility", () => {
  const ui = harness();
  const catalogs = [
    ["api", ui.data.operations.map(operation => ({ id: operation.id, owner: operation.service }))],
    ["events", ui.data.events.map(event => ({ id: event.type, owner: event.producer }))],
    ["flows", scenarioSnapshot(ui.data).map(flow => ({ id: flow.id, owner: flow.service }))],
  ];
  for (const [view, expected] of catalogs) {
    ui.navigate(`#${view}?plan=1`);
    const groups = catalogGroups(ui);
    assert.deepEqual(groups.map(group => group.owner).sort(), [...new Set(expected.map(item => item.owner))].sort());
    assert.deepEqual(groups.flatMap(group => group.ids).sort(), expected.map(item => item.id).sort(),
      `${view}: no missing or duplicated entries`);
    for (const group of groups) {
      assert.deepEqual(group.ids.sort(), expected.filter(item => item.owner === group.owner).map(item => item.id).sort(),
        `${view}: items belong to their group`);
      if (view === "api") {
        const subgroups = [...group.markup.matchAll(/<h3 class="catalog-subheading">[\s\S]*?<\/h3>([\s\S]*?)(?=<h3 class="catalog-subheading">|$)/g)];
        const publicIds = ui.data.operations.filter(operation => operation.service === group.owner && operation.visibility !== "internal").map(operation => operation.id);
        const probeIds = ui.data.operations.filter(operation => operation.service === group.owner && operation.visibility === "internal").map(operation => operation.id);
        assert.equal(subgroups.length, Number(publicIds.length > 0) + Number(probeIds.length > 0));
        const actual = subgroups.map(([, markup]) => [...markup.matchAll(/<a href="([^"]+)"/g)]
          .map(([, href]) => new URLSearchParams(decode(href).split("?")[1]).get("item")));
        assert.deepEqual(actual, [publicIds, probeIds].filter(ids => ids.length),
          "Public operations and operational probes have separate lists within each service");
      }
    }
  }
});

test("visible service filters select, survive reload and reset each catalog", () => {
  const ui = harness();
  const scenarios = scenarioSnapshot(ui.data);
  for (const view of ["api", "events", "flows"]) {
    const expected = service => view === "api"
      ? ui.data.operations.filter(item => !service || item.service === service).map(item => item.id)
      : view === "events"
        ? ui.data.events.filter(item => item.status === "implemented"
          && (!service || item.producer === service || item.consumers.includes(service))).map(item => item.type)
        : scenarios.filter(item => !service || item.service === service).map(item => item.id);
    ui.navigate(`#${view}`);
    for (const service of view === "flows" ? ["identity", "notification", "infrastructure"] : ["identity", "notification"]) {
      ui.click(`[data-action="catalog-filter"][data-service="${service}"]`);
      assert.deepEqual(catalogIds(ui).sort(), expected(service).sort(), `${view}: ${service}`);
      const params = new URLSearchParams(ui.hash().split("?")[1]);
      assert.equal(params.get("service"), service);
      assert.equal(params.get("item"), null, "Changing the service clears the old selection");
      assert.equal(params.get("direction"), null, "Default direction is omitted from URLs");
      assert.match(ui.markup(), new RegExp(`data-action="catalog-filter" data-service="${service}" aria-pressed="true"`));
      assert.deepEqual(catalogIds(harness(ui.data, ui.hash())), catalogIds(ui), "Shared URL restores the selection");
    }
    ui.click('[data-action="catalog-filter"][data-service=""]');
    assert.equal(new URLSearchParams(ui.hash().split("?")[1]).get("service"), null);
    assert.deepEqual(catalogIds(ui).sort(), expected("").sort());
  }
});

test("event filters distinguish publishing and consuming, including empty results", () => {
  const ui = harness();
  ui.navigate("#events?plan=1");
  for (const service of ["identity", "notification", "progress"]) {
    ui.click(`[data-action="catalog-filter"][data-service="${service}"]`);
    for (const direction of ["published", "consumed", "all"]) {
      ui.click(`[data-action="event-direction"][data-direction="${direction}"]`);
      const expected = ui.data.events.filter(event =>
        (direction !== "consumed" && event.producer === service)
        || (direction !== "published" && event.consumers.includes(service)));
      assert.deepEqual(catalogIds(ui).sort(), expected.map(event => event.type).sort(), `${service}: ${direction}`);
      const params = new URLSearchParams(ui.hash().split("?")[1]);
      assert.equal(params.get("direction"), direction === "all" ? null : direction);
      assert.deepEqual(catalogIds(harness(ui.data, ui.hash())), catalogIds(ui));
      if (!expected.length) assert.doesNotMatch(detailMarkup(ui), /<h2><code>/,
        "No stale event detail is shown for an empty direction");
    }
  }
  ui.click('[data-action="event-direction"][data-direction="consumed"]');
  ui.click('[data-action="catalog-filter"][data-service=""]');
  assert.equal(new URLSearchParams(ui.hash().split("?")[1]).get("direction"), null);
  assert.doesNotMatch(ui.markup(), /data-action="event-direction"/, "Direction requires a selected service");
  assert.deepEqual(catalogIds(ui).sort(), ui.data.events.map(event => event.type).sort());
  const noService = harness(ui.data, "#events?direction=consumed&plan=1");
  assert.deepEqual(catalogIds(noService).sort(), ui.data.events.map(event => event.type).sort());
});

test("an item outside the chosen service never replaces the filtered detail", () => {
  const data = dataSnapshot();
  const identityOperation = data.operations.find(operation => operation.service === "identity");
  const notificationOperation = data.operations.find(operation => operation.service === "notification");
  const status = Object.keys(notificationOperation.responses)[0];
  identityOperation.responses[status] = { description: "OUTSIDE_FILTER_RESPONSE" };
  notificationOperation.responses[status] = { description: "SELECTED_SERVICE_RESPONSE" };
  const ui = harness(data, `#api?service=notification&item=${identityOperation.id}`);
  assert.ok(!catalogIds(ui).includes(identityOperation.id));
  assert.ok(visibleText(detailMarkup(ui)).includes(notificationOperation.id));
  assert.ok(!visibleText(detailMarkup(ui)).includes(identityOperation.id));
  ui.click(`[data-action="response"][data-response="${status}"]`);
  assert.ok(visibleText(ui.element("response-body").innerHTML).includes("SELECTED_SERVICE_RESPONSE"),
    "Response switching uses the rendered fallback operation, not the stale URL item");
  assert.doesNotMatch(ui.element("response-body").innerHTML, /OUTSIDE_FILTER_RESPONSE/);

  const identityFlow = scenarioSnapshot(data).find(flow => flow.service === "identity");
  const infraFlow = scenarioSnapshot(data).find(flow => flow.service === "infrastructure");
  ui.navigate(`#flows?service=infrastructure&item=${identityFlow.id}`);
  assert.ok(!catalogIds(ui).includes(identityFlow.id));
  const selectedHeading = visibleText(detailMarkup(ui).match(/<h2>([\s\S]*?)<\/h2>/)?.[1] || "");
  assert.equal(selectedHeading, infraFlow.title);

  const outsideEvent = data.events.find(event => event.producer !== "notification" && !event.consumers.includes("notification"));
  ui.navigate(`#events?service=notification&item=${encodeURIComponent(outsideEvent.type)}&plan=1`);
  assert.ok(!catalogIds(ui).includes(outsideEvent.type));
  assert.ok(!visibleText(detailMarkup(ui)).includes(outsideEvent.type));
});

test("service filters discover new owners from data and do not duplicate a self-consumed event", () => {
  const data = dataSnapshot();
  const id = "test_new_service";
  data.services.push({ id, name: "Test new service", status: "implemented" });
  const operation = { ...data.operations[0], id: "testNewOperation", service: id };
  data.operations.push(operation);
  const flow = { ...data.details[0].flows[0], id: "test-new-flow", title: "Test new flow", operationIds: [operation.id] };
  data.details.push({ serviceId: id, flows: [flow], facts: [], operationNotes: {} });
  const event = { ...data.events[0], type: "test.event.created", subject: "cookie.events.test.event.created.v1",
    producer: id, consumers: [id, "notification"], implementedConsumers: [id, "notification"] };
  data.events.push(event);
  const ui = harness(data);
  for (const [view, expected] of [["api", operation.id], ["events", event.type], ["flows", flow.id]]) {
    ui.navigate(`#${view}`);
    ui.click(`[data-action="catalog-filter"][data-service="${id}"]`);
    assert.deepEqual(catalogIds(ui), [expected]);
    assert.deepEqual(catalogGroups(ui).map(group => group.owner), [id]);
  }
  ui.navigate(`#events?service=${id}`);
  for (const direction of ["published", "consumed"]) {
    ui.click(`[data-action="event-direction"][data-direction="${direction}"]`);
    assert.deepEqual(catalogIds(ui), [event.type]);
  }
});

test("every operation renders readable parameters, request fields and selectable responses", async t => {
  const ui = harness();
  assert.ok(ui.data.operations.some(operation => operation.visibility === "internal"), "Include private probes");
  for (const operation of ui.data.operations) {
    await t.test(operation.id, () => {
      ui.navigate(`#api?item=${encodeURIComponent(operation.id)}`);
      const visible = visibleText(ui.markup());
      assert.ok(visible.includes(operation.path), "Route is visible");
      assert.ok(visible.includes(operation.id), "Exact operation ID is visible");
      for (const parameter of operation.parameters || []) {
        assert.ok(visible.includes(parameter.name), `Parameter ${parameter.name} is visible`);
      }
      const bodyStart = ui.markup().indexOf("<h3>Тело запроса");
      const bodyEnd = ui.markup().indexOf('<section class="detail-section"><h3>Ответы', bodyStart);
      const request = visibleText(ui.markup().slice(bodyStart, bodyEnd));
      for (const content of Object.values(operation.requestBody?.content || {})) {
        for (const field of propertyNames(content.schema)) {
          assert.ok(request.includes(field), `Request field ${field} is visible outside raw JSON`);
        }
      }
      for (const [status, response] of Object.entries(operation.responses)) {
        ui.click(`[data-action="response"][data-response="${status}"]`);
        const rendered = visibleText(ui.element("response-body").innerHTML);
        assert.ok(rendered.includes((response.description || "").replace(/\s+/g, " ").trim()),
          `Response ${status} description is visible`);
        for (const header of Object.keys(response.headers || {})) {
          assert.ok(rendered.includes(header), `Response ${status} header ${header} is visible`);
        }
        for (const content of Object.values(response.content || {})) {
          for (const field of propertyNames(content.schema)) {
            assert.ok(rendered.includes(field), `Response ${status} field ${field} is visible outside raw JSON`);
          }
        }
      }
    });
  }
});

test("turning off the plan removes the selected planned event from the catalog", () => {
  const ui = harness();
  const event = ui.data.events.find(value => value.status === "planned");
  assert.ok(event, "Fixture includes a planned producer");
  ui.navigate(`#events?item=${encodeURIComponent(event.type)}&plan=1`);
  assert.ok(catalogIds(ui).includes(event.type));
  ui.togglePlan(false);
  const displayed = catalogIds(ui);
  assert.ok(!displayed.includes(event.type), "Selected planned event must be hidden too");
  assert.ok(displayed.every(id => ui.data.events.find(value => value.type === id).status === "implemented"));
});

test("plan toggle preserves an implemented consumer filter and hides its selected planned event detail", () => {
  const ui = harness();
  const event = ui.data.events.find(value => value.status === "planned" && value.consumers.includes("notification"));
  ui.navigate(`#events?service=notification&direction=consumed&item=${encodeURIComponent(event.type)}&plan=1`);
  assert.ok(visibleText(detailMarkup(ui)).includes(event.type));
  ui.togglePlan(false);
  const params = new URLSearchParams(ui.hash().split("?")[1]);
  assert.equal(params.get("service"), "notification");
  assert.equal(params.get("direction"), "consumed");
  assert.equal(params.get("item"), null);
  assert.ok(!visibleText(detailMarkup(ui)).includes(event.type));
  assert.deepEqual(catalogIds(ui).sort(), ui.data.events.filter(value =>
    value.status === "implemented" && value.consumers.includes("notification")).map(value => value.type).sort());
  ui.togglePlan(true);
  assert.deepEqual(catalogIds(ui).sort(), ui.data.events.filter(value => value.consumers.includes("notification"))
    .map(value => value.type).sort());

  ui.click('[data-action="catalog-filter"][data-service="progress"]');
  ui.togglePlan(false);
  const reset = new URLSearchParams(ui.hash().split("?")[1]);
  assert.equal(reset.get("service"), null, "Hiding the plan removes a planned service filter");
  assert.equal(reset.get("direction"), null, "Direction is removed together with its service");
  assert.deepEqual(catalogIds(ui).sort(), ui.data.events.filter(value => value.status === "implemented")
    .map(value => value.type).sort());
});

test("toggling planned components preserves the scenario being traced", () => {
  const ui = harness();
  const flow = ui.data.details.find(value => value.serviceId === "identity").flows[0];
  ui.navigate(`#map?flow=${flow.id}`);
  for (const checked of [true, false]) {
    ui.togglePlan(checked);
    assert.equal(new URLSearchParams(ui.hash().split("?")[1]).get("flow"), flow.id);
    assert.ok(visibleText(ui.markup()).includes(flow.title));
    assert.match(ui.markup(), /data-action="next-step"/, "Scenario controls remain available");
  }
});

test("push path reaches APNs through FCM and remains navigable on the map", () => {
  const ui = harness();
  const pushCalls = ui.data.calls.filter(call => call.caller === "notification"
    && ["fcm", "apns"].includes(call.callee));
  assert.deepEqual(pushCalls.map(call => call.callee), ["fcm"]);
  ui.navigate("#map?flow=notification-reminder-delivery");
  assert.match(ui.markup(), /data-edge="notification-fcm"/);
  assert.match(ui.markup(), /data-edge="fcm-apns"/);
  assert.doesNotMatch(ui.markup(), /data-edge="notification-apns"/);
  const steps = scenarioSnapshot(ui.data).find(flow => flow.id === "notification-reminder-delivery").steps;
  const fcmIndex = steps.findIndex(step => step.node === "fcm");
  assert.equal(steps[fcmIndex + 1].node, "apns");
  ui.click('[data-edge="fcm-apns"]');
  assert.ok(visibleText(ui.markup()).includes("FCM → APNs"));
});

test("opening a search result for the current URL restores its operation view", () => {
  const ui = harness();
  const operation = ui.data.operations.find(value => value.visibility !== "internal");
  const hash = `#api?item=${encodeURIComponent(operation.id)}`;
  ui.navigate(hash);
  ui.search(operation.id);
  assert.match(ui.markup(), /<h1>Поиск по архитектуре<\/h1>/);
  ui.click(`a[href="${hash}"]`);
  assert.match(ui.markup(), /<h1>HTTP-запросы<\/h1>/);
  assert.equal(ui.element("search").value, "");
});

test("schema values and embedded source text remain inert markup", () => {
  const data = dataSnapshot();
  const attack = '<img src=x onerror="globalThis.compromised=true">';
  const sourcePath = `contracts/openapi/public/quote"><svg onload=alert(1)>.yaml`;
  const operation = data.operations.find(value => value.requestBody);
  operation.summary = attack;
  operation.source = { path: sourcePath, line: 1 };
  operation.requestBody = {
    required: true,
    content: { "application/json": { schema: {
      type: "object",
      properties: { unsafeValue: { type: "string", description: attack, enum: [attack] } },
    } } },
  };
  data.sources[sourcePath] = `${attack}\n</script><script>alert(1)</script>`;
  const ui = harness(data, `#api?item=${encodeURIComponent(operation.id)}`);
  assert.ok(!ui.markup().includes(attack));
  assert.ok(!ui.markup().includes('<svg onload=alert(1)>'));
  assert.ok(visibleText(ui.markup()).includes(attack), "Escaping preserves readable schema text");
  ui.click("[data-source]");
  assert.equal(ui.element("source-dialog").open, true);
  assert.equal(ui.element("source-title").textContent, sourcePath);
  const rendered = ui.element("source-content").innerHTML;
  assert.ok(!rendered.includes("<img"));
  assert.ok(!rendered.includes("<script"));
  assert.ok(visibleText(rendered).includes(attack), "Source viewer displays escaped source, not executable tags");
});

test("User operations use their own facts and do not claim active production ingress", () => {
  const data = dataSnapshot();
  const ui = harness(data, "#api?item=getProfile&service=user");
  assert.ok(ui.markup().includes('id="fact-user-jwks"'));
  assert.ok(!ui.markup().includes('id="fact-notification-jwks-http"'));
  assert.ok(!ui.markup().includes('id="fact-infra-gateway-deadlines"'));
  assert.ok(visibleText(ui.markup()).includes("production ingress"));
});
