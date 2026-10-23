let state, floor = 0, sel = null, shown = '', timer;
const $ = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"']/g, c => '&#' + c.charCodeAt(0) + ';');
const tag = s => `<span class="tag ${s}">${s.toLowerCase()}</span>`;
const cells = () => state.floors.flat(2).filter(Boolean);      // every bed on every floor
const bedOf = id => cells().find(b => b.id === id);
const api = async (url, method = 'GET') => (await fetch(url, { method })).json();
const DOWN = 'Cannot reach the server. Is HospitalServer running?';

function toast(msg, ok = true) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'show' + (ok ? '' : ' bad');
  clearTimeout(t.h);
  t.h = setTimeout(() => (t.className = ''), 2800);
}

async function load() {
  try { state = await api('/api/state'); } catch (e) { return toast(DOWN, false); }
  render();
}

async function act(path, params = {}) {
  try {
    const r = await api('/api/' + path + '?' + new URLSearchParams(params), 'POST');
    toast(r.msg, r.ok);
    load();
  } catch (e) { toast(DOWN, false); }
}

function setFloor(i) { floor = i; render(); }
function pick(id) { floor = bedOf(id).f; sel = id; shown = ''; render(); }
function assign() { act('assign', { id: sel, patient: $('pn').value }); }
function transfer() { act('transfer', { id: sel, toWard: $('tw').value }); }

function render() {
  const beds = cells(), n = s => beds.filter(b => b.status === s).length;
  const free = a => a.filter(b => b.status === 'AVAILABLE').length;
  const icu = beds.filter(b => b.ward === 'ICU'), vent = beds.filter(b => b.vent);
  const chip = (cls, t) => `<span class="chip">${cls ? `<span class="dot ${cls}"></span>` : ''}${t}</span>`;
  $('stats').innerHTML = chip('AVAILABLE', n('AVAILABLE') + ' available') + chip('OCCUPIED', n('OCCUPIED') + ' occupied')
    + chip('CLEANING', n('CLEANING') + ' cleaning') + chip('', `ICU ${free(icu)}/${icu.length} free`)
    + chip('', `Ventilators ${free(vent)}/${vent.length} free`);

  $('tabs').innerHTML = state.floors.map((_, i) =>
    `<button class="btn${i === floor ? ' on' : ''}" onclick="setFloor(${i})">Floor ${i + 1}</button>`).join('');
  renderMap();

  // only rebuild the detail panel when the selected bed changes, so typing is never interrupted by the live refresh
  const b = sel && bedOf(sel), key = b ? b.id + b.status : '';
  if (key !== shown) { shown = key; renderDetail(b); }

  $('recent').innerHTML = state.recent.map(r =>
    `<li onclick="pick('${r.id}')"><b>${r.id}</b> ${tag(r.event)} <span class="dot ${bedOf(r.id).status}"></span><small>${r.mins < 1 ? 'just now' : r.mins + ' min ago'}</small></li>`).join('')
    || '<li class="mute">No ICU bed freed or assigned in the last hour.</li>';

  $('transfers').innerHTML = state.transfers.map(t =>
    `<li><b>#${t.id}</b> ${esc(t.patient)} from ${t.from} to ${t.toWard} ${tag(t.status)}`
    + (t.note ? `<br><small>${esc(t.note)}</small>` : '')
    + (t.status === 'PENDING' ? `<div class="row"><button class="btn" onclick="act('approve',{id:${t.id}})">Approve</button><button class="btn ghost" onclick="act('reject',{id:${t.id}})">Reject</button></div>` : '')
    + '</li>').join('') || '<li class="mute">No requests. Select an occupied bed to request a transfer.</li>';
}

/** Draws the 2D grid: each cell is a bed button (coordinates = row/column) or a corridor. */
function renderMap() {
  const g = state.floors[floor], cols = g[0].length;
  const colWard = c => (g.map(r => r[c]).find(Boolean) || {}).ward;      // undefined = corridor column
  $('map').style.gridTemplateColumns = [...Array(cols).keys()].map(c => colWard(c) ? 'minmax(0,1fr)' : 'minmax(0,.4fr)').join(' ');
  let html = '';
  for (let c = 0, k; c < cols; c += k) {                                 // ward name labels above their columns
    k = 1;
    while (c + k < cols && colWard(c + k) === colWard(c)) k++;
    html += `<div class="wl" style="grid-column:span ${k}">${colWard(c) || ''}</div>`;
  }
  g.forEach(row => row.forEach(b => {
    html += b
      ? `<button class="bed ${b.status}${b.id === sel ? ' sel' : ''}" data-id="${b.id}" title="${b.id}: ${b.status.toLowerCase()}">${b.id.slice(-3)}${b.vent ? '<i>V</i>' : ''}</button>`
      : '<div class="gap"></div>';
  }));
  $('map').innerHTML = html;
}

function renderDetail(b) {
  if (!b) { $('detail').textContent = 'Select a bed on the map to assign, release or transfer.'; return; }
  const wards = [...new Set(cells().map(x => x.ward))].filter(w => w !== b.ward);
  let h = `<b>${b.id}</b>, ${b.ward} ward, floor ${b.f + 1}, row ${b.r + 1} column ${b.c + 1}${b.vent ? ', ventilator fitted' : ''}<br>${tag(b.status)} ${esc(b.patient)}<div class="row">`;
  if (b.status === 'AVAILABLE') h += `<input id="pn" placeholder="Patient name"><button class="btn" onclick="assign()">Assign bed</button>`;
  if (b.status === 'OCCUPIED') h += `<button class="btn" onclick="act('release',{id:'${b.id}'})">Release bed</button>`
    + `<select id="tw">${wards.map(w => `<option>${w}</option>`).join('')}</select><button class="btn" onclick="transfer()">Request transfer</button>`;
  if (b.status === 'CLEANING') h += `<button class="btn" onclick="act('clean',{id:'${b.id}'})">Mark clean</button>`;
  $('detail').innerHTML = h + '</div>';
}

$('map').onclick = e => {
  const el = e.target.closest('.bed');
  if (el) { sel = el.dataset.id; render(); }
};

// Autocomplete: each keystroke asks the Java Trie for words starting with the typed prefix
$('q').oninput = () => {
  clearTimeout(timer);
  timer = setTimeout(async () => {
    const text = $('q').value.trim();
    if (!text) { $('results').innerHTML = ''; return; }
    try {
      const items = await api('/api/search?q=' + encodeURIComponent(text));
      $('results').innerHTML = items.map(i =>
        `<li data-k="${esc(i.key)}"><b><mark>${esc(i.key.slice(0, text.length))}</mark>${esc(i.key.slice(text.length))}</b> ${tag(i.type)}<br><small>${esc(i.desc)}, quantity ${i.qty}</small></li>`).join('')
        || '<li class="mute">No match. Try a different spelling or ID prefix.</li>';
    } catch (e) { toast(DOWN, false); }
  }, 120);
};
$('results').onclick = e => {
  const li = e.target.closest('li[data-k]');
  if (li) { $('q').value = li.dataset.k; $('results').innerHTML = li.outerHTML; }
};

$('rbtn').onclick = () => act('reassign', { patient: $('rp').value });

load();
setInterval(load, 5000);   // live refresh
