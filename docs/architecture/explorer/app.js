/* COOKie architecture explorer. No requests to services, analytics, CDN or storage. */
(() => {
  "use strict";
  const D = window.ARCHITECTURE_DATA;
  if (!D) throw new Error("Architecture data is missing. Run build.py.");
  const $ = (id) => document.getElementById(id);
  const esc = (value) => String(value ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
  const text = (value) => typeof value === "string" ? value : JSON.stringify(value, null, 2);
  const asArray = (value) => Array.isArray(value) ? value : [];
  const serviceById = new Map(D.services.map(s => [s.id, s]));
  const details = asArray(D.details);
  const flows = details.flatMap(d => asArray(d.flows).map(f => ({...f, service: d.serviceId})));
  const facts = details.flatMap(d => asArray(d.facts).map(f => ({...f, service: d.serviceId})));
  const notes = Object.assign({}, ...details.map(d => d.operationNotes || {}));
  const publicOps = D.operations.filter(o => o.visibility !== "internal");
  const views = ["map", "api", "events", "flows", "facts"];
  const viewNames = {map:"Карта системы",api:"HTTP-запросы",events:"События",flows:"Сценарии",facts:"Таймауты и правила"};
  let state = {view:"map", item:"notification", service:"", planned:false, direction:"all", flow:"", query:"", response:"", step:-1, zoom:1};
  let toastTimer;
  let filterFocus = "";
  const titles = {mobile_client:"Мобильный клиент",gateway:"API Gateway",identity:"Identity",notification:"Notification",user:"User",user_db:"User DB",identity_db:"Identity DB",notification_db:"Notification DB",nats:"NATS JetStream",redis:"Redis",smtp:"SMTP",apns:"APNs",fcm:"FCM",object_storage:"Object storage",infrastructure:"Инфраструктура"};
  function title(id) { return titles[id] || serviceById.get(id)?.name?.replace(/ Service$/, "") || asArray(D.externalSystems).find(e => e.id === id)?.name || id; }
  function serviceMark(id) { return `<span class="service-dot ${esc(id)}"></span>${esc(title(id))}`; }
  function statusPill(status) { return `<span class="pill ${esc(status)}">${status === "implemented" ? "Реализовано" : "План"}</span>`; }
  function methodPill(method) { return `<span class="pill method ${esc(method.toLowerCase())}">${esc(method.toUpperCase())}</span>`; }
  function sourceLink(source, label = "Открыть источник") {
    if (!source?.path) return "";
    return `<button type="button" class="source-link" data-source="${esc(source.path)}" data-line="${Number(source.line) || 1}"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m5 4-4 4 4 4M11 4l4 4-4 4M9 2 7 14"/></svg>${esc(label)}</button>`;
  }
  function actionButton(action, label, attrs = "", cls = "button") { return `<button type="button" class="${cls}" data-action="${action}" ${attrs}>${label}</button>`; }
  function linkButton(view, item, label, cls = "button", extra = {}) { return `<a class="${cls}" href="${esc(hashFor(view,item,extra))}">${label}</a>`; }
  function hashFor(view, item = "", extra = {}) {
    const params = new URLSearchParams();
    const values = {item, service:state.service, plan:state.planned ? "1" : "", direction:view==="events"&&state.direction!=="all"?state.direction:"", ...extra};
    if(view!=="events"||!values.service||values.direction==="all")delete values.direction;
    Object.entries(values).forEach(([k,v]) => { if (v !== "" && v != null) params.set(k, v); });
    return `#${view}${params.size ? "?" + params : ""}`;
  }
  function go(view, item = "", extra = {}) {
    state.query = ""; $("search").value = "";
    const next = hashFor(view,item,extra);
    if(location.hash===next)readHash();else location.hash=next;
  }
  function readHash() {
    const [view, query = ""] = location.hash.slice(1).split("?");
    const params = new URLSearchParams(query);
    state = {...state, view:views.includes(view) ? view : "map", item:params.get("item") || "", service:params.get("service") || "", planned:params.get("plan") === "1", direction:["published","consumed"].includes(params.get("direction"))?params.get("direction"):"all", flow:params.get("flow") || "", response:"",step:-1,query:""};
    if (!serviceById.has(state.service) && !details.some(d => d.serviceId === state.service)) state.service = "";
    if (!state.service) state.direction = "all";
    $("search").value = "";
    render();
    $("main").scrollTop = 0;
    const list = document.querySelector(".catalog-list");
    const active = list?.querySelector(".selected");
    if (active && state.item) {
      const group = active.closest(".catalog-group");
      const first = group?.querySelector(".catalog-item");
      const headingHeight = group?.querySelector(".catalog-group-heading")?.offsetHeight || 0;
      list.scrollTop = Math.max(0, (first===active?group.offsetTop:active.offsetTop-headingHeight) - list.offsetTop - 15);
    }
    if(state.view==="map")panToSelectedNode();
    if(filterFocus){document.querySelector(filterFocus)?.focus({preventScroll:true});filterFocus="";}
  }
  const icons = {
    map:'<rect x="2" y="3" width="7" height="6" rx="1"/><rect x="15" y="14" width="7" height="6" rx="1"/><path d="M5.5 9v8H15M9 6h9v8"/>',
    api:'<path d="m8 5-6 7 6 7M16 5l6 7-6 7M14 3l-4 18"/>',
    events:'<path d="M3 7h14l-3-3M21 17H7l3 3M17 7l-3 3M7 17l3-3"/>',
    flows:'<circle cx="5" cy="4" r="2"/><circle cx="18" cy="12" r="2"/><circle cx="5" cy="20" r="2"/><path d="M5 6v4c0 2 11 0 11 2M16 12c-11 0-11 2-11 6"/>',
    facts:'<circle cx="12" cy="12" r="9"/><path d="M12 6v6l4 3"/>'
  };
  function renderNav() {
    const counts = {api:publicOps.length, events:D.events.filter(e => state.planned || e.status === "implemented").length, flows:flows.length, facts:facts.length};
    $("navigation").innerHTML = views.map(v => `<a href="${esc(hashFor(v))}" class="nav-button ${state.view === v && !state.query ? "active" : ""}" ${state.view === v ? 'aria-current="page"' : ""}><svg viewBox="0 0 24 24" aria-hidden="true">${icons[v]}</svg>${viewNames[v]}${counts[v] ? `<span class="count">${counts[v]}</span>` : ""}</a>`).join("");
    $("service-filters").innerHTML = D.services.filter(s => s.status === "implemented" || state.planned).map(s => `<button type="button" class="service-filter ${s.status} ${state.service === s.id ? "active" : ""}" data-action="filter" data-service="${esc(s.id)}" aria-pressed="${state.service === s.id}">${serviceMark(s.id)}<span class="mini-status">${s.status === "implemented" ? publicOps.filter(o => o.service === s.id).length + " API" : "план"}</span></button>`).join("");
    $("show-planned").checked = state.planned;
    $("snapshot").innerHTML = `${D.services.filter(s => s.status === "implemented").length} сервиса реализовано · ${publicOps.length} ручек<br><code>${esc(D.meta?.revision || D.meta?.fingerprint?.slice(0,12) || "local")}</code><button type="button" data-action="about">Источники и обновление</button>`;
  }
  function heading(name, description, actions = "") {
    return `<div class="page-heading"><div><h1>${esc(name)}</h1><p>${esc(description)}</p></div>${actions ? `<div class="heading-actions">${actions}</div>` : ""}</div>`;
  }
  function matchesService(owner) { return !state.service || owner === state.service; }
  function orderedServices(ids) {
    const order = D.services.map(s=>s.id).concat("infrastructure");
    return [...new Set(ids)].sort((a,b)=>{
      const rank=id=>order.includes(id)?order.indexOf(id):order.length;
      return rank(a)-rank(b)||title(a).localeCompare(title(b),"ru");
    });
  }
  function catalogFilters(items, servicesFor, description, includes=(item,id)=>servicesFor(item).includes(id)) {
    const ids = orderedServices(items.flatMap(servicesFor).filter(id=>state.planned||serviceById.get(id)?.status!=="planned").concat(state.service||[]));
    const countFor=id=>items.filter(item=>includes(item,id)).length;
    return `<div class="catalog-filters"><div class="catalog-filter-row" role="group" aria-label="Фильтр по сервису"><span class="filter-label">Сервис</span>${["",...ids].map(id=>`<button type="button" class="filter-chip ${state.service===id?"active":""}" data-action="catalog-filter" data-service="${esc(id)}" aria-pressed="${state.service===id}">${id?serviceMark(id):"Все сервисы"}<span class="filter-count">${id?countFor(id):items.length}</span></button>`).join("")}</div><p class="filter-description">${esc(description)}</p></div>`;
  }
  function catalogGroups(items, owner, renderItems, prefix="") {
    return orderedServices(items.map(owner)).map(id=>{
      const group=items.filter(item=>owner(item)===id);
      return `<section class="catalog-group" data-owner="${esc(id)}" aria-label="${esc(prefix+title(id))}"><h2 class="catalog-group-heading">${prefix?`<span class="group-prefix">${esc(prefix)}</span>`:""}${serviceMark(id)}<span class="count">${group.length}</span></h2>${renderItems(group)}</section>`;
    }).join("");
  }
  function render() {
    renderNav();
    if (state.query) { renderSearch(); return; }
    if (state.view === "map") renderMap();
    if (state.view === "api") renderApi();
    if (state.view === "events") renderEvents();
    if (state.view === "flows") renderFlows();
    if (state.view === "facts") renderFacts();
  }
  const baseNodes = [
    {id:"user",x:276,y:582,w:182,sub:"Local :8083 · без deploy",kind:"service"},
    {id:"user_db",x:516,y:582,w:188,sub:"PostgreSQL · goals / inbox",kind:"store"},
    {id:"mobile_client",x:18,y:80,w:176,sub:"KMP · публичный HTTP",kind:"client"},
    {id:"gateway",x:18,y:258,w:176,sub:"Yandex API Gateway",kind:"infra"},
    {id:"identity",x:276,y:154,w:182,sub:"Kotlin / JVM · :8080",kind:"service"},
    {id:"identity_db",x:276,y:420,w:182,sub:"PostgreSQL · outbox",kind:"store"},
    {id:"nats",x:516,y:420,w:188,sub:"COOKIE_EVENTS",kind:"broker"},
    {id:"notification",x:748,y:154,w:190,sub:"Host :8082 · JVM :8080",kind:"service"},
    {id:"notification_db",x:770,y:420,w:190,sub:"PostgreSQL · inbox / jobs",kind:"store"},
    {id:"redis",x:770,y:582,w:190,sub:"Индекс сроков · sorted set",kind:"store"},
    {id:"smtp",x:1047,y:35,w:165,sub:"Письма · TLS",kind:"external"},
    {id:"fcm",x:1047,y:184,w:165,sub:"Push · Android / iOS",kind:"external"},
    {id:"apns",x:1047,y:333,w:165,sub:"iOS · через Firebase",kind:"external"}
  ];
  const baseEdges = [
    {id:"user-db",from:"user",to:"user_db",kind:"store",label:"state + outbox / inbox",path:"M458 619H516",lx:465,ly:674},
    {id:"user-jwks",from:"user",to:"identity",kind:"http",label:"JWKS",path:"M276 605H247V214H276",lx:207,ly:530},
    {id:"user-publish",from:"user",to:"nats",kind:"event",label:"User events",path:"M415 582V540H610V494",lx:438,ly:529},
    {id:"user-consume",from:"nats",to:"user",kind:"event",label:"Identity lifecycle",path:"M550 494V515H330V582",lx:340,ly:503},
    {id:"client-gateway",from:"mobile_client",to:"gateway",kind:"http",label:"HTTPS",path:"M106 154V258",lx:118,ly:210},
    {id:"gateway-identity",from:"gateway",to:"identity",kind:"http",label:"/v1/auth/*",path:"M194 282H227V191H276",lx:200,ly:166},
    {id:"gateway-notification",from:"gateway",to:"notification",kind:"http",label:"/v1/notifications/*",path:"M194 313H720V210H748",lx:440,ly:301},
    {id:"identity-db",from:"identity",to:"identity_db",kind:"store",label:"Транзакция + outbox",path:"M367 228V420",lx:380,ly:367},
    {id:"identity-nats",from:"identity",to:"nats",kind:"event",label:"3 события · publish ACK",path:"M458 209H552V420",lx:493,ly:386},
    {id:"nats-notification",from:"nats",to:"notification",kind:"event",label:"Pull · ACK после commit",path:"M704 449H735V253H817V228",lx:745,ly:350},
    {id:"notification-jwks",from:"notification",to:"identity",kind:"http",label:"GET /v1/auth/jwks",path:"M788 154V72H415V154",lx:518,ly:60},
    {id:"notification-db",from:"notification",to:"notification_db",kind:"store",label:"Inbox · расписание · jobs",path:"M885 228V420",lx:902,ly:389},
    {id:"notification-redis",from:"notification",to:"redis",kind:"store",label:"После commit",path:"M938 210H996V619H960",lx:1006,ly:550},
    {id:"notification-smtp",from:"notification",to:"smtp",kind:"http",label:"SMTP",path:"M938 174H1008V72H1047",lx:976,ly:115},
    {id:"notification-fcm",from:"notification",to:"fcm",kind:"http",label:"FCM HTTP v1",path:"M938 192H1023V221H1047",lx:951,ly:181},
    {id:"fcm-apns",from:"fcm",to:"apns",kind:"http",label:"Доставка iOS",path:"M1129 258V333",lx:1018,ly:300}
  ];
  function topology() {
    const nodes = baseNodes.map(n => ({...n, h:74}));
    const present = new Set(nodes.map(n => n.id));
    const flowNodes = new Set(flows.find(f => f.id === state.flow)?.steps.map(s=>s.node) || []);
    let extra = D.services.filter(s => !present.has(s.id) && (state.planned || s.status === "implemented" || flowNodes.has(s.id)));
    if (state.planned) extra = extra.concat(asArray(D.externalSystems).filter(s => !present.has(s.id) && s.id !== "mobile_client").map(s => ({...s,status:"planned"})));
    extra.forEach((s,i) => { if (present.has(s.id)) return; present.add(s.id); nodes.push({id:s.id,x:25+(i%5)*244,y:775+Math.floor(i/5)*160,w:202,h:78,sub:s.status === "implemented" ? "Реализованный сервис" : "Целевая архитектура",kind:"service",planned:s.status !== "implemented"}); });
    const edges = baseEdges.map(e => ({...e}));
    const nodeMap = new Map(nodes.map(n => [n.id,n]));
    const selected = state.item || state.service;
    if (state.planned || extra.length) {
      const targets = [];
      D.calls.forEach(c => {
        if (c.caller !== selected && c.callee !== selected && !(flowNodes.has(c.caller)&&flowNodes.has(c.callee))) return;
        if (!nodeMap.has(c.caller) || !nodeMap.has(c.callee)) return;
        if (baseEdges.some(e => e.from === c.caller && e.to === c.callee)) return;
        targets.push({from:c.caller,to:c.callee,label:"sync",kind:c.status === "implemented" ? "http" : "planned"});
      });
      D.events.forEach(e => {
        if ((e.producer === selected || flowNodes.has(e.producer)) && nodeMap.has(e.producer) && !baseEdges.some(edge=>edge.from===e.producer&&edge.to==="nats")) targets.push({from:e.producer,to:"nats",label:"События",kind:e.status === "implemented" ? "event" : "planned"});
        if (asArray(e.consumers).includes(selected) && nodeMap.has(selected) && !["notification"].includes(selected)) targets.push({from:"nats",to:selected,label:e.type,kind:asArray(e.implementedConsumers).includes(selected) && e.status === "implemented" ? "event" : "planned"});
      });
      const selectedService=serviceById.get(selected);
      [...asArray(selectedService?.outbound),...asArray(selectedService?.external_dependencies)].forEach(to=>{
        if(nodeMap.has(to))targets.push({from:selected,to,label:"Целевая связь",kind:"planned"});
      });
      if(asArray(selectedService?.redis).length&&!baseEdges.some(e=>e.from===selected&&e.to==="redis"))
        targets.push({from:selected,to:"redis",label:"Redis · модель",kind:"planned"});
      const seen = new Set();
      targets.forEach(e => {
        const key = `${e.from}:${e.to}:${e.kind}`; if (seen.has(key)) return; seen.add(key);
        const a=nodeMap.get(e.from),b=nodeMap.get(e.to); if(!a||!b)return;
        edges.push({...e,id:"model-"+key,path:`M${a.x+a.w/2} ${a.y+a.h}C${a.x+a.w/2} ${a.y+a.h+55} ${b.x+b.w/2} ${b.y-55} ${b.x+b.w/2} ${b.y}`,lx:(a.x+b.x)/2,ly:(a.y+b.y)/2});
      });
    }
    return {nodes,edges,height:extra.length ? 775+Math.ceil(extra.length/5)*160 : 704};
  }
  function renderMap() {
    const {nodes,edges,height} = topology();
    const selectedFlow = flows.find(f => f.id === state.flow);
    const flowNodes = new Set(selectedFlow?.steps.map(s => s.node) || []);
    const flowPairs = new Set(selectedFlow?.steps.slice(1).map((s,i) => selectedFlow.steps[i].node+":"+s.node) || []);
    const chosenEdge = edges.find(e => state.item === "edge:"+e.id);
    const selected = selectedFlow ? "" : state.item || state.service;
    const relevant = new Set([selected]);
    edges.filter(e => e.from === selected || e.to === selected).forEach(e => { relevant.add(e.from); relevant.add(e.to); });
    const selectedStep = selectedFlow?.steps[state.step];
    function edgeClass(e) {
      if(selectedFlow) return flowPairs.has(e.from+":"+e.to)||(flowNodes.has(e.from)&&flowNodes.has(e.to)) ? "highlighted" : "dimmed";
      if(chosenEdge) return e.id === chosenEdge.id ? "highlighted" : "dimmed";
      return selected ? (e.from===selected || e.to===selected ? "highlighted" : "dimmed") : "";
    }
    $("main").innerHTML = heading("Карта системы",state.planned ? "Реализованные компоненты и целевая модель. Выберите сервис, чтобы раскрыть его связи." : "От входящего запроса до базы и провайдера. Нажмите на компонент, связь или откройте сценарий.",linkButton("flows","","Проследить сценарий")) +
      `<div class="map-layout ${!selectedFlow&&!chosenEdge&&!selected?"overview":""}"><section class="map-area" aria-label="Интерактивная схема сервисов"><div class="map-toolbar"><span class="map-caption">${selectedFlow ? esc(selectedFlow.title) : "Текущая реализация · начальная production-топология"}</span><div class="map-tools">${actionButton("zoom-out","−",'aria-label="Уменьшить масштаб"',"icon-button")}${actionButton("fit","⌖",'aria-label="Вписать схему"',"icon-button")}${actionButton("zoom-in","+",'aria-label="Увеличить масштаб"',"icon-button")}</div></div><div class="map-stage"><svg class="map-svg" viewBox="0 0 1240 ${height}" role="group" aria-label="Компоненты и направления взаимодействия" style="width:${100*state.zoom}%">
      <defs>${["http","event","store","planned"].map(k=>`<marker id="arrow-${k}" markerWidth="7" markerHeight="7" refX="6" refY="3" orient="auto" markerUnits="strokeWidth"><path d="M0 0L6 3L0 6" fill="none" stroke="${({http:"#386aab",event:"#a16b22",store:"#9daec2",planned:"#9daabd"})[k]}" stroke-width="1.2"/></marker>`).join("")}</defs>
      ${edges.map(e=>`<g data-action="edge" data-edge="${esc(e.id)}" tabindex="0" role="button" aria-label="${esc(title(e.from)+" → "+title(e.to)+": "+e.label)}"><path d="${e.path}" fill="none" stroke="transparent" stroke-width="16"/><path d="${e.path}" class="map-edge ${e.kind} ${edgeClass(e)}" marker-end="url(#arrow-${e.kind})"/><text x="${e.lx}" y="${e.ly}" class="edge-label ${edgeClass(e)==="dimmed"?"dimmed":""}">${esc(e.label)}</text></g>`).join("")}
      ${nodes.some(n=>n.y>=775) ? '<text x="25" y="739" class="node-title">Модель сервисов</text><text x="205" y="739" class="node-subtitle">Плановые компоненты отмечены пунктирной рамкой</text>' : ""}
      ${nodes.map(n=>`<g transform="translate(${n.x},${n.y})" class="diagram-node ${n.id} ${n.planned?"planned":""} ${n.id===selected||n.id===selectedStep?.node?"selected":""} ${(selectedFlow&&!flowNodes.has(n.id)) || (!selectedFlow&&selected&&!relevant.has(n.id)&&!chosenEdge) ? "dimmed":""}" data-action="node" data-node="${esc(n.id)}" tabindex="0" role="button" aria-label="${esc(title(n.id))}${n.planned?", запланирован":""}"><rect width="${n.w}" height="${n.h}" rx="${n.kind==="store"?6:10}"/><text x="15" y="26" class="node-title">${esc(title(n.id).length>23?title(n.id).slice(0,21)+"…":title(n.id))}</text><text x="15" y="47" class="node-subtitle">${esc(n.sub)}</text>${n.kind==="service"&&!n.planned?'<circle cx="15" cy="62" r="2.5" fill="#448477"/><text x="23" y="66" class="node-status">реализовано</text>':""}</g>`).join("")}</svg></div><div class="map-legend"><span><i class="legend-line"></i>HTTP / SMTP</span><span><i class="legend-line event"></i>События</span><span><i class="legend-line store"></i>Хранилище</span>${state.planned?'<span><i class="legend-line planned"></i>План</span>':""}</div></section><aside class="inspector" aria-label="Детали выбранного элемента">${selectedFlow ? flowInspector(selectedFlow) : `${actionButton("clear-flow","Свернуть детали","","text-button")}<div style="height:15px"></div>${chosenEdge ? edgeInspector(chosenEdge) : nodeInspector(selected)}`}</aside></div>`;
  }
  function nodeInspector(id) {
    if (!id) return `<div class="empty-inspector"><svg viewBox="0 0 48 48" aria-hidden="true"><rect x="3" y="5" width="16" height="13" rx="3"/><rect x="29" y="29" width="16" height="13" rx="3"/><path d="M11 18v17h18M19 11h18v18"/></svg><h2>Архитектура в деталях</h2><p class="muted">Выберите сервис или связь. Здесь появятся запросы, события и настройки из текущего кода.</p>${linkButton("flows",flows[0]?.id||"","Начать со сценария")}</div>`;
    const service = serviceById.get(id);
    const owner = id.endsWith("_db") ? id.replace(/_db$/,"") : id;
    const ops = publicOps.filter(o=>o.service===id);
    const events = D.events.filter(e=>(state.planned||e.status==="implemented")&&(e.producer===id||asArray(e.consumers).includes(id)));
    const infraDesc = {
      mobile_client:"Kotlin Multiplatform client и generated API client. Экранные сценарии и обработчик verification-ссылки ещё требуют реализации.",
      gateway:"Начальный production ingress — Yandex API Gateway. Маршрутизирует /v1/auth/* в Identity и /v1/notifications/* в Notification. Caddy — отдельный компонент целевой модели.",
      identity_db:"Изолированная PostgreSQL БД Identity. Владеет аккаунтами, попытками регистрации, сессиями, лимитами и transactional outbox. Другие сервисы не читают её таблицы.",
      notification_db:"Источник истины для настроек, устройств, inbox, проекций, tombstones, расписания, quarantine и заданий доставки.",
      nats:"Durable stream COOKIE_EVENTS, at-least-once. Реальный producer — Identity. Notification использует durable pull consumer; будущие producers пока представлены контрактами и fixtures.",
      redis:"Эфемерный индекс сроков Notification: только идентификаторы, версии и timestamps. Потеря Redis компенсируется PostgreSQL sweep и восстановлением индекса.",
      smtp:"Email provider. Сохраняется исходный JWE; адрес и одноразовый токен расшифровываются только в памяти. Неопределённый результат после SMTP_SUBMITTING заканчивается UNKNOWN без повторов.",
      apns:"Apple Push Notification service получает iOS-сообщения от Firebase. Ключ Apple загружается в Firebase; Notification не подключается к APNs напрямую. Подтверждение FCM означает ACCEPTED, а не прочтение.",
      fcm:"Единый push-провайдер для Android и iOS: FCM HTTP v1. Оба клиента регистрируют непрозрачные FCM-токены. Для iOS Firebase пересылает сообщение в APNs. Требуются credentials и включение; устаревший токен отключается только для отправленной revision."
    };
    const relevantFacts = facts.filter(f=> f.service===owner || (["gateway","nats","redis"].includes(id) && f.service==="infrastructure") || (["smtp","apns","fcm"].includes(id)&&new RegExp(id,"i").test(JSON.stringify(f)))).slice(0,4);
    return `<div class="eyeline">${service ? statusPill(service.status) : '<span class="pill">Компонент</span>'}</div><h2>${esc(title(id))}</h2><p class="muted">${esc(infraDesc[id]||service?.purpose||"Внешняя зависимость целевой модели. Реализация и параметры уточняются вместе с сервисом.")}</p>${service?sourceLink(service.source,"Модель и descriptor"):""}
      ${ops.length?`<section class="inspector-section"><h3>Публичные запросы <span class="count">${ops.length}</span></h3>${ops.map(o=>linkButton("api",o.id,`${methodPill(o.method)}<code>${esc(o.path)}</code>`,"mini-link",{service:""})).join("")}</section>`:""}
      ${service?.status==="planned"?'<div class="callout warning">Активного service descriptor нет. Контракты roadmap не считаются работающим API.</div>':""}
      ${D.calls.some(c=>c.caller===id)?`<section class="inspector-section"><h3>Синхронные зависимости</h3>${D.calls.filter(c=>c.caller===id).map(c=>`<div class="fact"><strong>${esc(title(c.callee))}</strong><p class="fact-description">${esc(c.purpose)}</p>${sourceLink(c.source)}</div>`).join("")}</section>`:""}
      ${service?.status==="implemented"?'<section class="inspector-section"><h3>Слои сервиса</h3><p class="muted">Generated OpenAPI transport → handwritten MVC controller → application use case → domain и ports → JDBC / инфраструктурные адаптеры. Domain и application не зависят от Spring.</p></section>':""}
      ${events.length?`<section class="inspector-section"><h3>События</h3>${events.map(e=>linkButton("events",e.type,`<span>${e.producer===id?"↗":"↙"}</span><code>${esc(e.type)}</code>`,"mini-link",{service:""})).join("")}</section>`:""}
      ${relevantFacts.length?`<section class="inspector-section compact-facts"><h3>Параметры</h3>${relevantFacts.map(factMarkup).join("")}${linkButton("facts","","Все таймауты и правила","mini-link",{service:service?owner:""})}</section>`:""}
      ${service?.dbml?`<section class="inspector-section">${sourceLink({path:service.dbml,line:1},"Схема данных · DBML")}</section>`:""}`;
  }
  function edgeInspector(edge) {
    const eventEdge=edge.kind==="event";
    const relevantEvents = D.events.filter(e=>(state.planned||e.status==="implemented")&&(edge.to==="nats" ? e.producer===edge.from : asArray(e.consumers).includes(edge.to)));
    return `<div class="eyeline"><span class="pill">${eventEdge?"JetStream":edge.kind==="store"?"Хранилище":edge.kind==="planned"?"Целевая связь":"Синхронный вызов"}</span></div><h2>${esc(title(edge.from))} → ${esc(title(edge.to))}</h2><p class="muted">${esc(edge.label)}</p>
      ${eventEdge?`<div class="callout">At-least-once. Дубли допустимы; порядок не гарантирован. Consumer фиксирует inbox и изменения в одной транзакции до ACK.</div><h3>Точные subjects</h3>${relevantEvents.map(e=>linkButton("events",e.type,`<code>${esc(e.subject)}</code>`,"mini-link",{service:""})).join("")}`:
      edge.id==="notification-jwks"?`<div class="callout">Публичное получение ключей с фиксированного origin. Это не вызов доменного internal API.</div>${linkButton("api","getIdentityJwks","Открыть JWKS-контракт")}`:
      edge.from==="gateway"?publicOps.filter(o=>o.service===edge.to).map(o=>linkButton("api",o.id,`${methodPill(o.method)}<code>${esc(o.path)}</code>`,"mini-link",{service:""})).join(""):
      edge.kind==="planned"?'<div class="callout warning">Разрешённая целевая зависимость. Реализация вызова, активный internal contract и его таймауты ещё не определены.</div>':
      `<p class="muted">Подробные условия обработки и значения таймаутов связаны с исходным кодом.</p>${linkButton("facts","","Открыть параметры","button",{service:edge.from==="notification"?"notification":""})}`}
      <section class="inspector-section">${linkButton("map",edge.from,"Компонент отправителя")}</section>`;
  }
  function flowInspector(flow) {
    return `<div class="eyeline"><span class="pill">Путь обработки</span>${actionButton("clear-flow","Закрыть","", "text-button")}</div><h2>${esc(flow.title)}</h2><p class="muted">${esc(flow.description)}</p><div class="heading-actions">${actionButton("prev-step","Назад",state.step<=0?"disabled":"")}${actionButton("next-step","Следующий шаг",state.step>=flow.steps.length-1?"disabled":"")}</div>${timeline(flow,true)}${continuations(flow)}`;
  }
  function timeline(flow,interactive=false) {
    return `<ol class="timeline">${asArray(flow.steps).map((s,i)=>`<li class="timeline-step ${state.step===i?"active":""}"><span class="step-number">${i+1}</span>${interactive?`<button type="button" class="step-title" data-action="step" data-step="${i}">${esc(s.label)}<span class="step-node">${esc(title(s.node))}</span></button>`:`<div class="step-title">${esc(s.label)}<span class="step-node">${esc(title(s.node))}</span></div>`}<p class="step-detail">${esc(s.detail)}</p>${sourceLink(s.source)}</li>`).join("")}</ol>`;
  }
  function operationFlow(op) { return flows.find(f=>f.id===notes[op.id]?.flowId) || flows.find(f=>asArray(f.operationIds).includes(op.id)); }
  function mapFlowLink(flow,label="Показать на карте",cls="button") {
    const planned=flow.steps.some(s=>serviceById.get(s.node)?.status==="planned");
    return linkButton("map","",label,cls,{flow:flow.id,service:"",plan:planned||state.planned?"1":""});
  }
  function continuations(flow) {
    const next=asArray(flow.nextFlows).map(id=>flows.find(f=>f.id===id)).filter(Boolean);
    return next.length?`<section class="detail-section"><h3>Продолжение обработки</h3>${next.map(f=>linkButton("flows",f.id,esc(f.title),"mini-link",{service:""})).join("")}</section>`:"";
  }
  function renderApi() {
    const ops=D.operations.filter(o=>matchesService(o.service));
    const op=ops.find(o=>o.id===state.item) || ops[0];
    const renderOperation=o=>`<a href="${esc(hashFor("api",o.id))}" class="catalog-item ${op?.id===o.id?"selected":""}" ${op?.id===o.id?'aria-current="true"':""}><span class="item-heading">${methodPill(o.method)}<code>${esc(o.path)}</code></span><span class="item-description">${esc(o.summary)}</span></a>`;
    const groups=catalogGroups(ops,o=>o.service,group=>[
      [group.filter(o=>o.visibility!=="internal"),"Публичный API"],
      [group.filter(o=>o.visibility==="internal"),"Закрытые probes"]
    ].filter(([items])=>items.length).map(([items,label])=>`<h3 class="catalog-subheading">${label}<span>${items.length}</span></h3>${items.map(renderOperation).join("")}`).join(""));
    $("main").innerHTML=heading("HTTP-запросы","Активные OpenAPI-контракты. Полные поля, ограничения, заголовки и ответы; operational probes выделены отдельно.")+
      catalogFilters(D.operations,o=>[o.service],"Группы по сервису-владельцу. В счётчики входят публичные операции и закрытые probes.")+
      `<div class="catalog"><div class="catalog-list"><div class="list-note">${ops.length} операций</div>${groups||emptyMarkup("Активных ручек пока нет","Выберите другой сервис или «Все сервисы».")}</div><article class="detail-panel">${op?operationDetail(op):emptyMarkup("Выберите сервис","Контракт появится после добавления service descriptor.")}</article></div>`;
  }
  function emptyMarkup(titleText,description) { return `<div class="empty"><strong>${esc(titleText)}</strong>${esc(description)}</div>`; }
  function operationDetail(op) {
    const flow=operationFlow(op);
    const security=op.security||[];
    const statuses=Object.keys(op.responses||{});
    const selected=statuses.includes(state.response)?state.response:statuses[0];
    const body=op.requestBody;
    const opNotes=asArray(notes[op.id]?.notes);
    const route=notes[op.id]?.route;
    const configuredFacts=notes[op.id]?.factIds;
    const missingSecurity=[...new Set(security.flatMap(requirement=>Object.keys(requirement)))].filter(name=>!Object.hasOwn(op.securitySchemes||{},name));
    return `<div class="eyeline">${serviceMark(op.service)}<span class="pill ${op.visibility==="internal"?"internal":"implemented"}">${op.visibility==="internal"?"Закрыт на ingress":"Публичный маршрут"}</span><code>${esc(op.id)}</code></div>
      <div class="operation-path">${methodPill(op.method)}<code>${esc(op.path)}</code></div><h2>${esc(op.summary)}</h2><p class="description">${esc(op.description||"")}</p>${sourceLink(op.source,op.source?.path||"OpenAPI")}
      <div class="flow-strip">${op.visibility==="internal"?`Оркестратор / оператор <span class="arrow">→</span> ${esc(title(op.service))} <span class="pill internal">без публичного gateway</span>`:`${route?esc(route):`Клиент <span class="arrow">→</span> API Gateway <span class="arrow">→</span> ${esc(title(op.service))}`} ${flow?`<span class="arrow">·</span>${mapFlowLink(flow,"Путь запроса","")}`:""}`}</div>
      ${opNotes.length?`<ul class="note-list">${opNotes.map(n=>`<li>${esc(n)}</li>`).join("")}</ul>`:""}
      <section class="detail-section"><h3>Авторизация</h3>${security.length?`<div class="callout">${security.map(requirement=>Object.keys(requirement).map(name=>`<code>${esc(name)}</code>${op.securitySchemes?.[name]?.scheme==="bearer"?" · Authorization: Bearer &lt;access JWT&gt;":""}`).join(" + ")).join(" или ")}</div>${missingSecurity.length?`<div class="callout warning">Пробел контракта: ${esc(missingSecurity.join(", "))} используется в security, но не объявлен в components.securitySchemes этого файла.${op.service==="notification"?" Реализация требует Authorization: Bearer &lt;access JWT&gt;; проверка описана в сценарии и исходниках ниже.":""}</div>`:""}${rawDetails("Security schemes",op.securitySchemes||{})}`:`<p class="response-description">${op.visibility==="internal"?"Без пользовательского токена на уровне приложения. Доступ ограничен deployment-сетью.":"Bearer JWT не требуется. Proof, refresh credentials и rate limits могут проверяться самим handler."}</p>`}</section>
      <section class="detail-section"><h3>Параметры пути, query и headers <span class="count">${asArray(op.parameters).length}</span></h3>${parametersMarkup(op.parameters)}</section>
      <section class="detail-section"><h3>Тело запроса ${body?`<span class="pill">${body.required?"Обязательно":"Необязательно"}</span>`:""}</h3>${body?contentMarkup(body.content):'<p class="response-description">Тело запроса не определено контрактом.</p>'}</section>
      <section class="detail-section"><h3>Ответы</h3><div class="response-tabs" role="tablist" aria-label="Коды ответа">${statuses.map(s=>`<button type="button" role="tab" aria-selected="${s===selected}" class="response-tab ${s===selected?"active":""}" data-action="response" data-response="${esc(s)}">${esc(s)}</button>`).join("")}</div><div id="response-body">${responseMarkup(op.responses?.[selected])}</div></section>
      ${op.visibility!=="internal"?`<section class="detail-section"><div class="code-heading"><span>Пример запроса · замените placeholders</span>${actionButton("copy-curl","Скопировать cURL",`data-operation="${esc(op.id)}"`,"text-button")}</div><div class="code-block"><pre>${esc(curlExample(op))}</pre></div></section>`:""}
      <section class="detail-section"><h3>Таймауты и ограничения</h3><div class="facts-grid">${facts.filter(f=>(configuredFacts||(op.visibility==="internal"?[op.service==="identity"?"identity-probes":"notification-readiness"]:["infra-gateway-deadlines","infra-app-deadline",...(op.service==="identity"?["identity-db-defaults"]:["notification-database","notification-jwks-http"])])).includes(f.id)).map(factMarkup).join("")}</div>${linkButton("facts","","Все параметры сервиса","mini-link",{service:op.service})}</section>
      ${flow?`<section class="detail-section"><h3>Что происходит внутри</h3>${timeline(flow)}${mapFlowLink(flow)}</section>${continuations(flow)}`:""}
      ${rawDetails("Полная операция OpenAPI",op)}`;
  }
  function parametersMarkup(parameters) {
    if(!asArray(parameters).length)return '<p class="response-description">Дополнительные параметры не объявлены.</p>';
    return `<div class="table-wrap"><table class="parameter-table"><thead><tr><th>Параметр</th><th>Где</th><th>Тип и ограничения</th></tr></thead><tbody>${parameters.map(p=>`<tr><td><code>${esc(p.name)}</code>${p.required?'<div class="field-name"><span class="required">обязательный</span></div>':""}<div class="field-type">${esc(p.description||"")}</div></td><td>${esc(p.in)}</td><td>${esc(schemaType(p.schema||{}))}${constraintsMarkup(p.schema||{})}${p.content?contentMarkup(p.content):""}</td></tr>`).join("")}</tbody></table></div>`;
  }
  function schemaType(s) { return s.type || (s.properties?"object":s.allOf?"allOf":s.oneOf?"oneOf":s.anyOf?"anyOf":s.$ref||"любой"); }
  function constraintsMarkup(s) {
    const names={format:"format",minimum:"min",maximum:"max",exclusiveMinimum:"exclusiveMin",exclusiveMaximum:"exclusiveMax",minLength:"minLength",maxLength:"maxLength",minItems:"minItems",maxItems:"maxItems",minProperties:"minProperties",maxProperties:"maxProperties",pattern:"pattern",multipleOf:"multipleOf",default:"default",example:"example",nullable:"nullable",additionalProperties:"additionalProperties",uniqueItems:"uniqueItems",readOnly:"readOnly",writeOnly:"writeOnly",deprecated:"deprecated"};
    let result=Object.entries(names).filter(([k])=>Object.hasOwn(s,k)).map(([k,name])=>`<span class="constraint">${esc(name)}: ${esc(text(s[k]))}</span>`).join("");
    if(s.enum) result+=`<span class="constraint">enum: ${esc(s.enum.map(text).join(" | "))}</span>`;
    return result ? `<div>${result}</div>` : "";
  }
  function schemaMarkup(schema,depth=0) {
    if(!schema)return '<p class="response-description">Схема не задана.</p>';
    if(depth>12)return rawDetails("Вложенная схема",schema);
    let html = schema.description?`<p class="response-description">${esc(schema.description)}</p>`:"";
    html+=constraintsMarkup(schema);
    if(schema.properties) {
      html+=`<table class="schema-table"><thead><tr><th>Поле</th><th>Описание и ограничения</th></tr></thead><tbody>${Object.entries(schema.properties).map(([name,s])=>`<tr><td><div class="field-name"><code>${esc(name)}</code>${asArray(schema.required).includes(name)?'<span class="required">*</span>':""}</div><div class="field-type">${esc(schemaType(s))}${s.format?" · "+esc(s.format):""}${s.nullable?" | null":""} · ${asArray(schema.required).includes(name)?"обязательное":"необязательное"}</div></td><td>${esc(s.description||"")}${constraintsMarkup(s)}${s.properties||s.items||s.allOf||s.oneOf||s.anyOf?`<div class="schema-nesting">${schemaMarkup(Object.fromEntries(Object.entries(s).filter(([k])=>["type","properties","required","items","allOf","oneOf","anyOf"].includes(k))),depth+1)}</div>`:""}</td></tr>`).join("")}</tbody></table>`;
    } else if(schema.type==="array"||schema.items) html+=`<div class="schema-nesting"><p class="field-type">Элемент массива</p>${schemaMarkup(schema.items,depth+1)}</div>`;
    else if(!schema.allOf&&!schema.oneOf&&!schema.anyOf) html+=`<p class="field-type">${esc(schemaType(schema))}</p>`;
    ["allOf","oneOf","anyOf"].forEach(comb=>{ if(schema[comb]) html+=asArray(schema[comb]).map((s,i)=>`<details class="schema-variant" open><summary>${comb} · ${i+1}</summary>${schemaMarkup(s,depth+1)}</details>`).join(""); });
    return html;
  }
  function contentMarkup(content) {
    return Object.entries(content||{}).map(([mime,c])=>`<div class="code-heading"><code>${esc(mime)}</code></div><div class="table-wrap">${schemaMarkup(c.schema)}</div>${c.example?`<div class="code-block"><pre>${esc(text(c.example))}</pre></div>`:""}${c.examples?rawDetails("Примеры",c.examples):""}${rawDetails("JSON Schema",c.schema||{})}`).join("")||'<p class="response-description">Без тела.</p>';
  }
  function responseMarkup(response) {
    if(!response)return "";
    return `<p class="response-description">${esc(response.description||"")}</p>${response.headers?`<h3>Заголовки ответа</h3>${parametersMarkup(Object.entries(response.headers).map(([name,h])=>({name,in:"header",...h})))}`:""}${contentMarkup(response.content)}`;
  }
  function rawDetails(label,obj) { return `<details class="raw-schema"><summary>${esc(label)}</summary><div class="code-block"><pre>${esc(JSON.stringify(obj,null,2))}</pre></div></details>`; }
  function schemaExample(s,depth=0,key="") {
    if(!s||depth>8)return "...";
    if(Object.hasOwn(s,"example"))return s.example;
    if(Object.hasOwn(s,"default"))return s.default;
    if(s.enum)return s.enum[0];
    if(s.allOf)return Object.assign({},...s.allOf.map(x=>schemaExample(x,depth+1)));
    if(s.oneOf||s.anyOf)return schemaExample((s.oneOf||s.anyOf)[0],depth+1);
    if(s.type==="object"||s.properties)return Object.fromEntries(Object.entries(s.properties||{}).map(([k,v])=>[k,schemaExample(v,depth+1,k)]));
    if(s.type==="array")return [schemaExample(s.items,depth+1)];
    if(s.type==="boolean")return true;
    if(["integer","number"].includes(s.type))return s.minimum??1;
    if(/timezone/i.test(key))return "Europe/Moscow";
    if(s.format==="email"||/email/i.test(key))return "developer@example.test";
    if(s.format==="uuid")return "11111111-1111-4111-8111-111111111111";
    if(s.format==="date-time")return "2026-10-20T09:00:00Z";
    if(s.format==="date")return "2026-10-20";
    return `<${key||s.type||"value"}>`;
  }
  function curlExample(op) {
    const lines=[`curl --request ${op.method.toUpperCase()} 'https://<api-host>${op.path}'`];
    if(asArray(op.security).length)lines.push("  --header 'Authorization: Bearer <access-token>'");
    asArray(op.parameters).filter(p=>p.in==="header").forEach(p=>lines.push(`  --header '${p.name}: <${p.name}>'`));
    const content=op.requestBody?.content?.["application/json"];
    if(content){lines.push("  --header 'Content-Type: application/json'");lines.push(`  --data '${JSON.stringify(schemaExample(content.schema),null,2).replace(/'/g,"'\\''")}'`);}
    return lines.join(" \\\n");
  }
  function eventInvolves(e,service,direction="all") {
    if(!service)return true;
    return (direction!=="consumed"&&e.producer===service)||(direction!=="published"&&asArray(e.consumers).includes(service));
  }
  function eventMatches(e) { return (state.planned||e.status==="implemented")&&eventInvolves(e,state.service,state.direction); }
  function renderEvents() {
    const available=D.events.filter(e=>state.planned||e.status==="implemented");
    const events=available.filter(eventMatches),selected=events.find(e=>e.type===state.item)||events[0];
    const groups=catalogGroups(events,e=>e.producer,group=>group.map(e=>`<a href="${esc(hashFor("events",e.type))}" class="catalog-item ${selected?.type===e.type?"selected":""}" ${selected?.type===e.type?'aria-current="true"':""}><code class="item-title">${esc(e.type)}</code><span class="item-description">Получают: ${asArray(e.consumers).map(c=>esc(title(c))+(asArray(e.implementedConsumers).includes(c)?"":" (план)")).join(", ")||"не указаны"}</span><div class="eyeline" style="margin:10px 0 0">${statusPill(e.status)}<span>v${esc(e.version)}</span></div></a>`).join(""),"Публикует ");
    $("main").innerHTML=heading("События","Точные JetStream subjects, producer, consumers и payload. Событие из модели отдельно от наличия работающего producer.")+
      catalogFilters(available,e=>[e.producer,...asArray(e.consumers)],"Фильтр учитывает публикацию и подписки. Список сгруппирован по сервису, который публикует событие.",(e,id)=>eventInvolves(e,id,state.direction))+
      (state.service?`<div class="event-direction" role="group" aria-label="Направление событий"><span class="filter-label">${esc(title(state.service))}</span>${[["all","Все связи"],["published","Публикует"],["consumed","Получает"]].map(([id,label])=>`<button type="button" class="filter-chip ${state.direction===id?"active":""}" data-action="event-direction" data-direction="${id}" aria-pressed="${state.direction===id}">${label}<span class="filter-count">${available.filter(e=>eventInvolves(e,state.service,id)).length}</span></button>`).join("")}</div>`:"")+
      `<div class="catalog"><div class="catalog-list"><div class="list-note">${events.length} событий · ${esc(D.transport?.stream||"COOKIE_EVENTS")}</div>${groups||emptyMarkup("Нет событий в фильтре","Измените направление, сервис или включите план.")}</div><article class="detail-panel">${selected?eventDetail(selected):emptyMarkup("Нет событий в фильтре","Включите план или выберите другой сервис.")}</article></div>`;
  }
  function eventDetail(e) {
    const consumers=asArray(e.consumers);
    return `<div class="eyeline">${statusPill(e.status)}<span>Event version ${esc(e.version)}</span></div><h2><code>${esc(e.type)}</code></h2><div class="code-block"><pre>${esc(e.subject)}</pre></div>${sourceLink(e.source,"Контракт события")}
      ${e.status!=="implemented"?'<div class="callout warning">Producer ещё не реализован. Notification может содержать consumer и тестовые fixtures для этого контракта.</div>':""}
      <section class="detail-section"><h3>Маршрут события</h3><div class="flow-strip">${linkButton("map",e.producer,esc(title(e.producer)),"",{plan:e.status==="implemented"?state.planned?"1":"":"1",service:""})}<span class="arrow">→</span>${linkButton("map","nats","COOKIE_EVENTS","",{service:""})}<span class="arrow">→</span>${consumers.map(c=>linkButton("map",c,esc(title(c))+(asArray(e.implementedConsumers).includes(c)?"":" (план)"),"",{plan:asArray(e.implementedConsumers).includes(c)?"":"1",service:""})).join(", ")}</div></section>
      <section class="detail-section"><h3>Payload</h3><div class="event-payload">${Array.isArray(e.payload)?e.payload.map(p=>`<code>${esc(typeof p==="string"?p:text(p))}</code>`).join(""):`<pre>${esc(text(e.payload))}</pre>`}</div>${e.aggregate_version_required?'<div class="callout">aggregate_version обязателен. Проекция применяет только более новую версию, удаления сохраняют минимальный tombstone.</div>':""}${e.security_note?`<div class="callout warning">${esc(e.security_note)}</div>`:""}${e.implementation_note?`<p class="response-description">${esc(e.implementation_note)}</p>`:""}</section>
      <section class="detail-section"><h3>Envelope</h3>${Object.entries(D.envelope||{}).map(([group,fields])=>`<h3 class="muted">${group==="required"?"Обязательные":"Необязательные"} поля</h3><table class="routing-table">${Object.entries(fields||{}).map(([k,v])=>`<tr><td><code>${esc(k)}</code></td><td>${esc(text(v))}</td></tr>`).join("")}</table>`).join("")}</section>
      <section class="detail-section"><h3>Доставка и ошибки</h3><p class="response-description">${e.status==="implemented"?`${esc(title(e.producer))} публикует после commit через outbox.`:"Планируемый producer должен публиковать после commit через outbox."} Consumer сохраняет inbox и изменения в одной транзакции. ACK отправляется после commit. Инфраструктурная ошибка допускает повтор, некорректное событие попадает в metadata-only quarantine.</p>${linkButton("facts","","ACK, backoff и retention","button",{service:""})}</section>${rawDetails("Полный контракт события",e)}`;
  }
  function renderFlows() {
    const list=flows.filter(f=>matchesService(f.service)),flow=list.find(f=>f.id===state.item)||list[0];
    const groups=catalogGroups(list,f=>f.service,group=>group.map(f=>`<a href="${esc(hashFor("flows",f.id))}" class="catalog-item ${f.id===flow?.id?"selected":""}" ${f.id===flow?.id?'aria-current="true"':""}><span class="item-title">${esc(f.title)}</span><span class="item-description">${f.steps.length} шагов</span></a>`).join(""));
    $("main").innerHTML=heading("Сценарии","Последовательность обработки, транзакционные границы и развилки. Каждый шаг связан с исходным кодом.")+
      catalogFilters(flows,f=>[f.service],"Группы и фильтр — по владельцу сценария. Межсервисный путь целиком остаётся в шагах; общие сценарии — в «Инфраструктуре».")+
      `<div class="catalog"><div class="catalog-list"><div class="list-note">${list.length} сценариев</div>${groups||emptyMarkup("Сценариев пока нет","Выберите другой сервис или «Все сервисы».")}</div><article class="detail-panel">${flow?`<div class="eyeline">${serviceMark(flow.service)}</div><h2>${esc(flow.title)}</h2><p>${esc(flow.description)}</p>${mapFlowLink(flow,"Показать путь на карте","button primary")}${asArray(flow.operationIds).length?`<section class="detail-section"><h3>Связанные операции</h3>${flow.operationIds.map(id=>{const op=D.operations.find(o=>o.id===id);return op?linkButton("api",id,`${methodPill(op.method)}<code>${esc(op.path)}</code>`,"mini-link",{service:""}):"";}).join("")}</section>`:""}${timeline(flow)}${continuations(flow)}`:emptyMarkup("Сценарии ещё не описаны","Добавьте сценарий в details/<service>.json.")}</article></div>`;
  }
  function factMarkup(f) { return `<div class="fact" id="fact-${esc(f.id)}"><div class="fact-label">${esc(f.label)}</div><div class="fact-value">${esc(text(f.value))}</div><p class="fact-description">${esc(f.description)}</p>${sourceLink(f.source,f.source?.path?.split("/").pop()+":"+f.source?.line)}</div>`; }
  function renderFacts() {
    const selected=facts.find(f=>f.id===state.item);
    const list=facts.filter(f=>matchesService(f.service));
    $("main").innerHTML=heading("Таймауты и правила","Настроенные значения и ограничения из кода. Это defaults и конфигурация, а не измеренные latency или SLA.")+
      `<div class="filter-bar">${[["","Все"],...details.map(d=>[d.serviceId,title(d.serviceId)==="infrastructure"?"Инфраструктура":title(d.serviceId)])].map(([id,label])=>`<button type="button" class="filter-chip ${state.service===id?"active":""}" data-action="fact-filter" data-service="${esc(id)}">${esc(label)}</button>`).join("")}</div><article class="detail-panel">${selected?`<div class="callout">${esc(title(selected.service))} · результат поиска</div>${factMarkup(selected)}<h3 style="margin-top:28px">Остальные параметры</h3>`:""}<div class="facts-grid">${list.filter(f=>f!==selected).map(factMarkup).join("")}</div>${!list.length&&!selected?emptyMarkup("Параметры пока не описаны","Новый сервис требует source-linked details; неизвестные значения не считаются заданными."):""}</article>`;
  }
  function renderSearch() {
    const q=state.query.toLocaleLowerCase("ru");
    const contains=obj=>JSON.stringify(obj).toLocaleLowerCase("ru").includes(q);
    const groups=[
      {label:"HTTP-запросы",view:"api",items:D.operations.filter(o=>matchesService(o.service)&&contains(o)).map(o=>({id:o.id,title:o.method.toUpperCase()+" "+o.path,description:o.summary}))},
      {label:"События",view:"events",items:D.events.filter(e=>eventMatches(e)&&contains(e)).map(e=>({id:e.type,title:e.subject,description:title(e.producer)+" → "+e.consumers.map(title).join(", ")}))},
      {label:"Сценарии",view:"flows",items:flows.filter(f=>matchesService(f.service)&&contains(f)).map(f=>({id:f.id,title:f.title,description:f.description}))},
      {label:"Параметры",view:"facts",items:facts.filter(f=>matchesService(f.service)&&contains(f)).map(f=>({id:f.id,title:f.label,description:text(f.value)}))}
    ];
    const count=groups.reduce((a,g)=>a+g.items.length,0);
    $("main").innerHTML=heading("Поиск по архитектуре",`«${state.query}» · ${count} результатов${state.planned?"":" · только реализованные producers"}`)+
      `<div class="search-results">${groups.filter(g=>g.items.length).map(g=>`<section class="search-group"><h3>${g.label} <span class="search-count">${g.items.length}</span></h3>${g.items.map(item=>`<a href="${esc(hashFor(g.view,item.id))}" class="search-result"><div><code>${esc(item.title)}</code><small>${esc(item.description)}</small></div></a>`).join("")}</section>`).join("")||emptyMarkup("Ничего не найдено","Попробуйте имя поля, часть URL, subject или «timeout». Для будущих producers включите план.")}</div>`;
  }
  function showSource(path,line) {
    const source=D.sources?.[path];
    $("source-title").textContent=path;
    $("source-content").innerHTML=source==null?'<p class="muted">Источник не включён в снимок. Откройте этот путь в репозитории.</p>':`<p class="muted">Снимок из репозитория · строка ${Number(line)||1}</p><pre class="source-code">${String(source).split("\n").map((t,i)=>`<span class="source-line ${i+1===Number(line)?"target":""}" ${i+1===Number(line)?'id="source-target"':""}><span class="line-no">${i+1}</span>${esc(t)}</span>`).join("")}</pre>`;
    $("source-dialog").showModal();
    requestAnimationFrame(()=>$("source-target")?.scrollIntoView({block:"center"}));
  }
  function about() {
    $("source-title").textContent="Как устроен этот атлас";
    $("source-content").innerHTML=`<div class="about-body"><h2>Архитектура рядом с кодом</h2><p>Снимок показывает реализацию в рабочем дереве. Он не опрашивает сервисы и не подтверждает состояние production. Файл работает без сети и не отправляет запросы к API.</p><h3>Источники</h3><ul><li><code>backend/services/*/service.yaml</code> — перечень реализованных сервисов.</li><li><code>contracts/openapi/public/*.yaml</code> — ручки, все параметры, ответы и схемы.</li><li><code>docs/architecture/model/*.yaml</code> — события, разрешённые синхронные связи и целевая модель.</li><li><code>details/*.json</code> — пути обработки, таймауты и правила со ссылками на код.</li></ul><p>Значения из кода — конфигурационные defaults или явно отмеченные production overrides. Они не являются latency, SLA или сведениями о запущенном окружении. Запланированные сервисы и потребители обозначены отдельно.</p><h3>Обновление</h3><p>Из корня репозитория: <code>make architecture-build</code>. Проверка актуальности: <code>make architecture-check</code>. Новый service descriptor добавляет сервис и OpenAPI автоматически; сценарии и пояснения добавляются отдельным JSON.</p><h3>Навигация</h3><p><kbd>/</kbd> — поиск. <kbd>Esc</kbd> — закрыть источник или поиск. Нажимайте на узлы и связи, открывайте шаги сценария. Текущий раздел и фильтр сохраняются в URL; ссылку с fragment можно переслать вместе с HTML.</p><h3>Версия данных</h3><code>${esc(D.meta?.fingerprint||D.meta?.revision)}</code><p class="muted">Дата архитектурной модели: ${esc(D.meta?.modelUpdatedAt||"не указана")}. Fingerprint меняется вместе с источниками. Полный снимок встроен в этот HTML.</p></div>`;
    $("source-dialog").showModal();
  }
  function notify(message) { clearTimeout(toastTimer);$("toast").textContent=message;$("toast").classList.add("visible");toastTimer=setTimeout(()=>$("toast").classList.remove("visible"),2200); }
  async function copy(value) {
    try { await navigator.clipboard.writeText(value);notify("Скопировано"); }
    catch { const input=document.createElement("textarea");input.value=value;input.style.position="fixed";input.style.left="-9999px";document.body.append(input);input.select();const success=document.execCommand("copy");input.remove();notify(success?"Скопировано":"Выделите и скопируйте пример вручную"); }
  }
  function redrawMap(action,step) {
    const inspectorScroll=document.querySelector(".inspector")?.scrollTop||0;
    const stage=document.querySelector(".map-stage");
    const stageScroll=[stage?.scrollLeft||0,stage?.scrollTop||0];
    renderMap();
    const inspector=document.querySelector(".inspector");if(inspector)inspector.scrollTop=inspectorScroll;
    const newStage=document.querySelector(".map-stage");if(newStage){newStage.scrollLeft=stageScroll[0];newStage.scrollTop=stageScroll[1];}
    const selector=`[data-action="${action}"]${step!=null?`[data-step="${Number(step)}"]`:""}`;
    const control=document.querySelector(selector);
    if(control&&!control.disabled)control.focus({preventScroll:true});
    else document.querySelector('[data-action="prev-step"]:not(:disabled)')?.focus({preventScroll:true});
    if(["step","prev-step","next-step"].includes(action))panToSelectedNode();
  }
  function panToSelectedNode() {
    const stage=document.querySelector(".map-stage"),node=document.querySelector(".diagram-node.selected");
    if(!stage||!node)return;
    const box=node.getBoundingClientRect(),viewport=stage.getBoundingClientRect();
    if(box.top<viewport.top||box.bottom>viewport.bottom)stage.scrollTop+=box.top-viewport.top-40;
    if(box.left<viewport.left||box.right>viewport.right)stage.scrollLeft+=box.left-viewport.left-40;
  }
  document.addEventListener("click",event=>{
    const anchor=event.target.closest('a[href^="#"]');
    if(anchor && anchor.getAttribute("href")===location.hash && state.query){event.preventDefault();readHash();return;}
    const source=event.target.closest("[data-source]");if(source){showSource(source.dataset.source,source.dataset.line);return;}
    const el=event.target.closest("[data-action]");if(!el)return;
    switch(el.dataset.action){
      case "about":about();break;
      case "close-dialog":$("source-dialog").close();break;
      case "filter":go(state.view,state.view==="map"?el.dataset.service:"",{service:state.service===el.dataset.service?"":el.dataset.service});break;
      case "catalog-filter":filterFocus=`[data-action="catalog-filter"][data-service="${CSS.escape(el.dataset.service)}"]`;go(state.view,"",{service:el.dataset.service,direction:el.dataset.service?state.direction:""});break;
      case "event-direction":filterFocus=`[data-action="event-direction"][data-direction="${CSS.escape(el.dataset.direction)}"]`;go("events","",{direction:el.dataset.direction==="all"?"":el.dataset.direction});break;
      case "fact-filter":state.service=el.dataset.service;go("facts","",{service:state.service});break;
      case "reset-filter":go(state.view,"",{service:""});break;
      case "node":go("map",el.dataset.node);break;
      case "edge":go("map","edge:"+el.dataset.edge);break;
      case "clear-flow":go("map");break;
      case "step":state.step=Number(el.dataset.step);redrawMap("step",state.step);break;
      case "next-step":state.step++;redrawMap("next-step");break;
      case "prev-step":state.step=Math.max(0,state.step-1);redrawMap("prev-step");break;
      case "zoom-in":state.zoom=Math.min(2.5,state.zoom+.25);redrawMap("zoom-in");break;
      case "zoom-out":state.zoom=Math.max(.75,state.zoom-.25);redrawMap("zoom-out");break;
      case "fit":state.zoom=1;redrawMap("fit");break;
      case "response":state.response=el.dataset.response;{const ops=D.operations.filter(o=>matchesService(o.service));const op=ops.find(o=>o.id===state.item)||ops[0];if(op){$("response-body").innerHTML=responseMarkup(op.responses[state.response]);document.querySelectorAll(".response-tab").forEach(b=>{b.classList.toggle("active",b.dataset.response===state.response);b.setAttribute("aria-selected",String(b.dataset.response===state.response));});}}break;
      case "copy-curl":{const op=D.operations.find(o=>o.id===el.dataset.operation);if(op)copy(curlExample(op));}break;
    }
  });
  document.addEventListener("keydown",event=>{
    if((event.key==="Enter"||event.key===" ")&&event.target.matches("g[role=button]")){event.preventDefault();event.target.dispatchEvent(new MouseEvent("click",{bubbles:true}));}
    if(event.key==="/"&&!event.ctrlKey&&!event.metaKey&&!/INPUT|TEXTAREA|SELECT/.test(document.activeElement.tagName)&&!$("source-dialog").open){event.preventDefault();$("search").focus();}
    if(event.key==="Escape"&&state.query&&!$("source-dialog").open){state.query="";$("search").value="";render();}
  });
  $("source-dialog").addEventListener("click",event=>{if(event.target===$("source-dialog"))$("source-dialog").close();});
  $("search").addEventListener("input",event=>{state.query=event.target.value.trim();render();});
  $("show-planned").addEventListener("change",event=>{const item=!event.target.checked&&state.view==="events"&&D.events.find(e=>e.type===state.item)?.status!=="implemented"?"":state.item;go(state.view,item,{plan:event.target.checked?"1":"",flow:state.flow,service:!event.target.checked&&serviceById.get(state.service)?.status==="planned"?"":state.service});});
  window.addEventListener("hashchange",readHash);
  readHash();
})();
