/* Klar TV im Browser. Ohne Bibliotheken außer mpegts.js für die Wiedergabe. */
'use strict';

// ---------- Hilfen ----------

const $app = document.getElementById('app');

/** Baut ein Element. Texte immer als Text, nie als HTML: Namen kommen vom Anbieter. */
function h(tag, attrs, ...kids) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'icon') el.insertAdjacentHTML('afterbegin', ICONS[v]);
      else if (k === 'value') el.value = v;
      // Über das Stil-Objekt: Stil-Attribute sperrt die Sicherheitsregel der Seite
      else if (k === 'style') el.style.cssText = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue;
    el.append(kid instanceof Node ? kid : document.createTextNode(String(kid)));
  }
  return el;
}

const svg = (d) => `<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="${d}"/></svg>`;
const ICONS = {
  home: svg('M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z'),
  live: svg('M21 6h-7.59l3.29-3.29L16 2l-4 4-4-4-.71.71L10.59 6H3c-1.1 0-2 .89-2 2v12c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.11-.9-2-2-2zm0 14H3V8h18v12zM9 10v8l7-4z'),
  movie: svg('M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z'),
  series: svg('M4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm16-4H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8 12.5v-9l6 4.5-6 4.5z'),
  search: svg('M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z'),
  settings: svg('M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.48.48 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z'),
  play: svg('M8 5v14l11-7z'),
  pause: svg('M6 19h4V5H6v14zm8-14v14h4V5h-4z'),
  back: svg('M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z'),
  star: svg('M12 17.27L18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z'),
  starOff: svg('M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z'),
  full: svg('M7 14H5v5h5v-2H7v-3zm-2-4h2V7h3V5H5v5zm12 7h-3v2h5v-5h-2v3zM14 5v2h3v3h2V5h-5z'),
  up: svg('M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z'),
  down: svg('M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z'),
  list: svg('M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z'),
  audio: svg('M12 3v9.28c-.47-.17-.97-.28-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z'),
  rew: svg('M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z'),
  fwd: svg('M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z'),
  lock: svg('M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z'),
  guide: svg('M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z'),
  check: svg('M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z'),
  checkCircle: svg('M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z'),
  checkOff: svg('M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm-2-5.83l-2.59-2.58L6 13l4 4 8-8-1.41-1.42z'),
  close: svg('M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z'),
  logo: '<svg viewBox="0 0 108 108" aria-hidden="true"><path d="M34 36h40a4 4 0 0 1 4 4v28a4 4 0 0 1-4 4H34a4 4 0 0 1-4-4V40a4 4 0 0 1 4-4z" fill="none" stroke="#38BDF8" stroke-width="4" stroke-linejoin="round"/><path d="M48 45l14 9-14 9z" fill="#38BDF8"/><path d="M44 79h20" stroke="#38BDF8" stroke-width="4" stroke-linecap="round"/></svg>',
};

class HttpError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

async function api(path, body) {
  const opt = body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Klar': '1' }, body: JSON.stringify(body),
  };
  let r;
  try {
    r = await fetch('/api/' + path, opt);
  } catch (e) {
    throw new HttpError(0, 'Keine Verbindung zum Klar-TV-Server.');
  }
  let data = {};
  try { data = await r.json(); } catch (e) { /* leer */ }
  if (r.status === 401 && path !== 'login') { showLogin(); throw new HttpError(401, data.error || 'Bitte anmelden.'); }
  if (!r.ok) throw new HttpError(r.status, data.error || `Fehler ${r.status}`);
  return data;
}

const img = (u) => (u ? '/img?u=' + encodeURIComponent(u) : null);
const pad = (n) => String(n).padStart(2, '0');
const clock = (ms) => { const d = new Date(ms); return `${pad(d.getHours())}:${pad(d.getMinutes())}`; };
function duration(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
  return hh ? `${hh}:${pad(mm)}:${pad(ss)}` : `${mm}:${pad(ss)}`;
}
const fold = (s) => (s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/ß/g, 'ss');

function toast(text) {
  const t = h('div', { class: 'toast' }, text);
  document.body.append(t);
  setTimeout(() => t.remove(), 3500);
}

/** Bild mit Platzhalter: Fehlt es oder lädt es nicht, bleibt der Name stehen. */
function picture(url, title, cls) {
  const ph = h('div', { class: 'ph' }, title || '');
  if (!url) return [ph];
  const i = h('img', { src: img(url), alt: '', loading: 'lazy', decoding: 'async', class: cls });
  i.addEventListener('load', () => ph.remove());
  i.addEventListener('error', () => i.remove());
  return [ph, i];
}

/** Lange Listen nach und nach aufbauen, sonst hängt der Browser bei zehntausend Einträgen. */
function lazyList(container, items, render, step = 80) {
  let shown = 0;
  const sentinel = h('div', { class: 'sentinel' });
  const more = () => {
    const end = Math.min(items.length, shown + step);
    const frag = document.createDocumentFragment();
    for (; shown < end; shown++) frag.append(render(items[shown], shown));
    container.append(frag);
    if (shown >= items.length) { observer.disconnect(); sentinel.remove(); }
  };
  const observer = new IntersectionObserver((e) => { if (e.some((x) => x.isIntersecting)) more(); }, { rootMargin: '800px' });
  container.after(sentinel);
  more();
  if (shown < items.length) observer.observe(sentinel);
  return observer;
}

// ---------- Zustand ----------

const S = {
  state: null,
  catalogs: {},
  now: new Map(),
  hevc: false,
};

try {
  S.hevc = !!(window.MediaSource && (MediaSource.isTypeSupported('video/mp4; codecs="hvc1.1.6.L120.90"') ||
    MediaSource.isTypeSupported('video/mp4; codecs="hev1.1.6.L120.90"')));
} catch (e) { S.hevc = false; }

async function loadState() {
  S.state = await api('state');
  return S.state;
}

function resetCatalogs() {
  S.catalogs = {};
  S.now.clear();
}

/** Liste einer Art vom Server, einmal geladen bis zum nächsten Wechsel. */
function catalog(kind, refresh) {
  if (!S.catalogs[kind] || refresh) {
    S.catalogs[kind] = api(`catalog?kind=${kind}${refresh ? '&refresh=1' : ''}`).then((c) => {
      c.byKey = new Map(c.streams.map((s) => [s.k, s]));
      c.byCat = new Map();
      for (const s of c.streams) {
        if (!c.byCat.has(s.c)) c.byCat.set(s.c, []);
        c.byCat.get(s.c).push(s);
      }
      return c;
    }).catch((e) => { delete S.catalogs[kind]; throw e; });
  }
  return S.catalogs[kind];
}

const isFav = (key) => (S.state?.favorites || []).includes(key);

// ---------- Gesehen ----------

const isWatched = (key) => (S.state?.watched || []).includes(key);

/** Gesehene Folgen der gerade offenen Serie. */
const watchedEps = new Set();

/** Haken für gesehene Filme und fertige Serien, „3/10“ für angefangene Serien. */
function watchMark(s) {
  if (s.k.startsWith('MOVIE:')) return isWatched(s.k) ? h('span', { class: 'seen-mark', icon: 'check', title: 'Gesehen' }) : null;
  const p = S.state?.seriesSeen?.[s.k];
  if (!p || !p[0]) return null;
  if (p[0] >= p[1]) return h('span', { class: 'seen-mark', icon: 'check', title: 'Gesehen' });
  return h('span', { class: 'seen-count' }, `${p[0]}/${p[1]}`);
}

async function setWatched(keys, on) {
  await api('watched', { keys, on });
  keys.forEach((k) => (on ? watchedEps.add(k) : watchedEps.delete(k)));
  await loadState();
}

function watchedButton(keys, label, labelDone, after) {
  const done = () => keys.length > 0 && keys.every((k) => (k.startsWith('MOVIE:') ? isWatched(k) : watchedEps.has(k)));
  const b = h('button', { class: 'btn' });
  const paint = () => {
    const d = done();
    b.innerHTML = ICONS[d ? 'checkCircle' : 'checkOff'];
    b.classList.toggle('seen', d);
    b.append(d ? labelDone : label);
  };
  b.onclick = async () => {
    await setWatched(keys, !done());
    paint();
    if (after) after();
  };
  paint();
  return b;
}
async function toggleFav(key) {
  const r = await api('favorite', { key });
  S.state.favorites = r.favorites;
  return r.favorites.includes(key);
}

// ---------- Laufende Sendungen ----------

const nowQueue = new Set();
let nowTimer = null;

/** Jetzt-läuft für sichtbare Sender, gesammelt abgefragt. */
function wantNow(id) {
  const c = S.now.get(id);
  // Leer: bald wieder fragen, der Programmführer wird vielleicht gerade eingelesen
  const maxAge = c && c.list.length ? 5 * 60000 : 45000;
  if (c && Date.now() - c.time < maxAge && (!c.list[0] || c.list[0].e > Date.now())) return;
  nowQueue.add(id);
  if (!nowTimer) nowTimer = setTimeout(flushNow, 120);
}

async function flushNow() {
  nowTimer = null;
  const ids = [...nowQueue].slice(0, 60);
  ids.forEach((i) => nowQueue.delete(i));
  if (nowQueue.size) nowTimer = setTimeout(flushNow, 120);
  if (!ids.length) return;
  try {
    const r = await api('now?ids=' + ids.map(encodeURIComponent).join(','));
    for (const id of ids) {
      S.now.set(id, { time: Date.now(), list: r[id] || [] });
      document.querySelectorAll(`[data-now="${CSS.escape(id)}"]`).forEach((el) => fillNow(el, id));
    }
  } catch (e) { /* ohne Programm weiter */ }
}

const nowObserver = new IntersectionObserver((entries) => {
  for (const e of entries) if (e.isIntersecting) { wantNow(e.target.dataset.now); nowObserver.unobserve(e.target); }
}, { rootMargin: '200px' });

// Sichtbare Sender jede Minute auffrischen: Sendungen wechseln, Balken wandern
setInterval(() => {
  if (document.hidden) return;
  const h0 = window.innerHeight;
  document.querySelectorAll('[data-now]').forEach((el) => {
    const r = el.getBoundingClientRect();
    if (r.bottom < 0 || r.top > h0 || !r.width) return;
    wantNow(el.dataset.now);
    if (S.now.has(el.dataset.now)) fillNow(el, el.dataset.now);
  });
}, 60000);

function fillNow(el, id) {
  const c = S.now.get(id);
  el.textContent = '';
  const cur = c?.list.find((p) => p.s <= Date.now() && p.e > Date.now());
  if (!cur) return;
  el.append(`${clock(cur.s)}  ${cur.t}`);
  const share = Math.min(100, Math.max(0, ((Date.now() - cur.s) / (cur.e - cur.s)) * 100));
  el.append(h('div', { class: 'bar' }, h('i', { style: `width:${share}%` })));
}

// ---------- Anmeldung ----------

let session = null;

async function boot() {
  try {
    session = await api('session');
  } catch (e) {
    $app.replaceChildren(h('div', { class: 'login' }, h('div', { class: 'card' }, h('p', null, e.message), h('button', { class: 'btn', onclick: boot }, 'Erneut versuchen'))));
    return;
  }
  if (!session.loggedIn) return showLogin();
  await loadState();
  if (S.state.chooseProfile) return showProfiles();
  shell();
  route();
}

// ---------- Profile ----------

const PROFILE_COLORS = ['#38BDF8', '#F472B6', '#34D399', '#FBBF24', '#A78BFA', '#FB7185'];
const profileName = (p) => p.name || 'Hauptprofil';

function avatar(p, size) {
  const el = h('span', { class: 'avatar' }, profileName(p).slice(0, 1).toUpperCase());
  el.style.cssText = `width:${size}px;height:${size}px;font-size:${Math.round(size * 0.45)}px;background:${PROFILE_COLORS[((p.color % 6) + 6) % 6]}`;
  return el;
}

async function chooseProfile(id) {
  S.state = await api('profile', { id });
  resetCatalogs();
  shell();
  route();
}

/** „Wer schaut?“ – Profile kommen aus der App (Einstellungen → Profile) und werden per QR-Code übernommen. */
function showProfiles() {
  player.close(true);
  const list = h('div', { class: 'profiles' });
  for (const p of S.state.profiles) {
    list.append(h('button', { class: 'profile' + (p.id === S.state.profile ? ' current' : ''), onclick: () => chooseProfile(p.id) },
      avatar(p, 96), h('span', null, profileName(p))));
  }
  $app.replaceChildren(h('div', { class: 'login' }, h('div', { class: 'card wide' },
    h('h1', { style: 'text-align:center;margin-top:0' }, 'Wer schaut?'), list,
    h('p', { class: 'dim small', style: 'text-align:center' }, 'Profile legst du in der Klar-TV-App an und überträgst sie per QR-Code.'))));
  setTimeout(() => list.querySelector('.current, .profile')?.focus(), 50);
}

function showLogin() {
  player.close(true);
  const setup = session && session.setupNeeded;
  const msg = h('div');
  const user = h('input', { type: 'text', autocomplete: 'username', autocapitalize: 'off', placeholder: 'Benutzername' });
  const pass = h('input', { type: 'password', autocomplete: setup ? 'new-password' : 'current-password', placeholder: 'Passwort' });
  const pass2 = h('input', { type: 'password', autocomplete: 'new-password', placeholder: 'Passwort wiederholen' });
  const go = async (e) => {
    e.preventDefault();
    msg.replaceChildren();
    if (setup && pass.value !== pass2.value) { msg.append(h('div', { class: 'msg error' }, 'Die Passwörter sind verschieden.')); return; }
    try {
      await api(setup ? 'setup' : 'login', { user: user.value, password: pass.value });
      boot();
    } catch (err) {
      msg.append(h('div', { class: 'msg error' }, err.message));
    }
  };
  let body;
  if (setup && !session.setupAllowed) {
    body = [h('p', null, 'Der Server ist noch nicht eingerichtet.'),
      h('p', { class: 'dim' }, 'Die Ersteinrichtung geht aus Sicherheitsgründen nur aus dem eigenen Netz. Öffne die Seite einmal auf einem Gerät zu Hause, z. B. unter http://<Rechnername>:8095.')];
  } else {
    body = h('form', { onsubmit: go },
      setup ? h('p', { class: 'dim' }, 'Lege Benutzername und Passwort für den Zugriff an. Das Passwort braucht mindestens 8 Zeichen.') : null,
      h('label', { class: 'field' }, h('span', null, 'Benutzername'), user),
      h('label', { class: 'field' }, h('span', null, 'Passwort'), pass),
      setup ? h('label', { class: 'field' }, h('span', null, 'Passwort wiederholen'), pass2) : null,
      msg,
      h('button', { class: 'btn primary', type: 'submit', style: 'width:100%' }, setup ? 'Einrichten' : 'Anmelden'));
  }
  $app.replaceChildren(h('div', { class: 'login' }, h('div', { class: 'card' },
    h('div', { class: 'logo', icon: 'logo' }, 'Klar TV'), body)));
  setTimeout(() => user.focus(), 50);
}

// ---------- Gerüst und Navigation ----------

const NAV = [
  ['start', 'home', 'Start'],
  ['live', 'live', 'Live-TV'],
  ['movies', 'movie', 'Filme'],
  ['series', 'series', 'Serien'],
  ['search', 'search', 'Suche'],
  ['settings', 'settings', 'Einstellungen'],
];
let $main;

function shell() {
  $main = h('main', { class: 'main' });
  const rail = h('nav', { class: 'rail' }, h('div', { class: 'logo', icon: 'logo' }));
  const me = (S.state.profiles || []).find((p) => p.id === S.state.profile);
  if (me && S.state.profiles.length > 1) {
    rail.append(h('button', { class: 'nav me', title: 'Profil wechseln', onclick: showProfiles }, avatar(me, 28), h('span', null, profileName(me))));
  }
  NAV.forEach(([id, icon, label], i) => {
    if (i === NAV.length - 1) rail.append(h('div', { class: 'spacer' }));
    rail.append(h('a', { class: 'nav', href: '#' + id, 'data-nav': id, icon }, label));
  });
  $app.replaceChildren(h('div', { class: 'shell' }, rail, $main));
}

window.addEventListener('hashchange', () => route());

let routeId = 0;
async function route() {
  if (!$main) return;
  const id = ++routeId;
  const [page, ...rest] = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  const top = { movie: 'movies', serie: 'series', channel: 'live' }[page] || page || 'start';
  document.querySelectorAll('[data-nav]').forEach((a) => a.classList.toggle('on', a.dataset.nav === top));
  $main.className = 'main';
  $main.scrollTop = 0;
  $main.replaceChildren();
  if (!S.state.accounts.length && page !== 'settings') return noProvider();
  try {
    switch (page || 'start') {
      case 'start': return await startPage(id);
      case 'live': return await livePage(id, rest[0]);
      case 'movies': return await gridPage(id, 'MOVIE', rest[0]);
      case 'series': return await gridPage(id, 'SERIES', rest[0]);
      case 'movie': return await moviePage(id, rest[0]);
      case 'serie': return await seriesPage(id, rest[0]);
      case 'channel': return await channelPage(id, rest[0]);
      case 'search': return await searchPage(id);
      case 'settings': return await settingsPage(id);
      default: location.hash = '#start';
    }
  } catch (e) {
    if (id !== routeId || e.status === 401) return;
    $main.replaceChildren(h('div', { class: 'msg error' }, e.message), h('button', { class: 'btn', onclick: () => route() }, 'Erneut versuchen'));
  }
}

function noProvider() {
  $main.append(h('h1', null, 'Willkommen bei Klar TV'),
    h('div', { class: 'card' }, h('p', null, 'Noch ist kein Anbieter eingerichtet.'),
      h('p', { class: 'dim' }, 'Trage deinen IPTV-Zugang ein oder übernimm alles per QR-Code aus der Klar-TV-App.'),
      h('a', { class: 'btn primary', href: '#settings' }, 'Zu den Einstellungen')));
}

const loading = () => h('p', { class: 'dim' }, 'Wird geladen …');

// ---------- Start ----------

async function startPage(id) {
  await loadState();
  if (id !== routeId) return;
  const st = S.state;
  $main.append(h('h1', null, 'Start'));
  const cont = st.continue || [];
  if (cont.length) {
    const shelf = h('div', { class: 'shelf wide' });
    for (const c of cont) {
      const share = c.duration ? (c.position / c.duration) * 100 : 0;
      shelf.append(h('button', { class: 'tile', onclick: () => playContinue(c) },
        h('div', { class: 'img' }, ...picture(c.image, c.title), h('div', { class: 'bar' }, h('i', { style: `width:${share}%` }))),
        h('div', { class: 't' }, c.title), h('div', { class: 's' }, c.subtitle || `noch ${duration(c.duration - c.position)}`)));
    }
    $main.append(h('h2', null, 'Weiterschauen'), shelf);
  }
  const favs = st.favorites || [];
  const recent = st.recent || [];
  const kinds = new Set([...favs, ...recent].map((k) => k.split(':')[0]).filter((k) => ['LIVE', 'MOVIE', 'SERIES'].includes(k)));
  const cats = {};
  for (const k of kinds) cats[k] = await catalog(k).catch(() => null);
  if (id !== routeId) return;
  const section = (title, keys, kind) => {
    const list = keys.filter((k) => k.startsWith(kind + ':')).map((k) => cats[kind]?.byKey.get(k)).filter(Boolean);
    if (!list.length) return;
    $main.append(h('h2', null, title));
    if (kind === 'LIVE') {
      const shelf = h('div', { class: 'shelf wide' });
      list.forEach((s, i) => shelf.append(h('button', { class: 'tile', onclick: () => playChannel(s, list, i) },
        h('div', { class: 'img' }, ...picture(s.l, s.n, 'contain')), h('div', { class: 't' }, s.n),
        h('div', { class: 's', 'data-now': s.i }))));
      $main.append(shelf);
      shelf.querySelectorAll('[data-now]').forEach((el) => nowObserver.observe(el));
    } else {
      const shelf = h('div', { class: 'shelf' });
      list.forEach((s) => shelf.append(posterCard(s)));
      $main.append(shelf);
    }
  };
  section('Lieblingssender', favs, 'LIVE');
  section('Lieblingsfilme', favs, 'MOVIE');
  section('Lieblingsserien', favs, 'SERIES');
  section('Zuletzt gesehen: Sender', recent, 'LIVE');
  section('Zuletzt gesehen: Filme', recent, 'MOVIE');
  section('Zuletzt gesehen: Serien', recent, 'SERIES');
  if ($main.children.length === 1) {
    $main.append(h('p', { class: 'dim' }, 'Hier erscheinen Favoriten, zuletzt Gesehenes und angefangene Filme und Folgen.'),
      h('a', { class: 'btn primary', href: '#live' }, 'Zum Live-TV'));
  }
}

function playContinue(c) {
  const [kind, id] = c.parent.split(':');
  let ref = 'CONT:' + c.key;
  if (c.key.startsWith('MOVIE:')) ref = c.key;
  else if (c.key.startsWith('EP:') && kind === 'SERIES') ref = `EP:${id}:${c.key.slice(3)}`;
  player.open({ ref, title: c.title, subtitle: c.subtitle, image: c.image, parent: c.parent, recent: c.parent, live: false, start: c.position });
}

// ---------- Live-TV ----------

async function livePage(id, catId) {
  $main.append(loading());
  const c = await catalog('LIVE');
  if (id !== routeId) return;
  const cats = c.categories.filter((x) => x.id === '*' || c.byCat.has(x.id) || (c.locked || []).includes(x.id));
  // Favoriten als eigene Gruppe vorn
  const favs = (S.state.favorites || []).filter((k) => k.startsWith('LIVE:')).map((k) => c.byKey.get(k)).filter(Boolean);
  const all = [{ id: '*', name: 'Alle' }];
  if (favs.length) all.push({ id: 'fav', name: 'Favoriten' });
  all.push(...cats.filter((x) => x.id !== '*'));
  const current = catId || (favs.length ? 'fav' : '*');
  const list = current === 'fav' ? favs : current === '*' ? c.streams : c.byCat.get(current) || [];
  const locked = (c.locked || []).includes(current);
  const name = all.find((x) => x.id === current)?.name || 'Sender';

  $main.className = 'main split';
  $main.replaceChildren();
  const groups = h('div', { class: 'groups' });
  for (const g of all) {
    const n = g.id === 'fav' ? favs.length : g.id === '*' ? c.streams.length : c.byCat.get(g.id)?.length || 0;
    const isLocked = (c.locked || []).includes(g.id);
    groups.append(h('a', { class: 'group' + (g.id === current ? ' on' : ''), href: `#live/${encodeURIComponent(g.id)}` },
      isLocked ? h('span', { icon: 'lock', style: 'width:18px;display:inline-flex' }) : null, g.name, h('span', { class: 'n' }, isLocked ? '' : n)));
  }
  const select = h('select', { class: 'group-select', onchange: (e) => { location.hash = `#live/${encodeURIComponent(e.target.value)}`; } },
    all.map((g) => h('option', { value: g.id, selected: g.id === current }, g.name)));
  const box = h('div');
  const channels = h('div', { class: 'channels' }, h('div', { class: 'head' }, h('h1', null, name), select), box);
  $main.append(groups, channels);
  groups.querySelector('.on')?.scrollIntoView({ block: 'center' });
  if (locked) {
    box.append(h('div', { class: 'card' }, h('p', null, 'Diese Gruppe ist durch die Kindersicherung gesperrt.'),
      h('button', { class: 'btn primary', onclick: unlockDialog }, 'Mit PIN entsperren')));
    return;
  }
  if (!list.length) { box.append(h('p', { class: 'dim' }, current === 'fav' ? 'Noch keine Lieblingssender.' : 'Keine Sender.')); return; }
  lazyList(box, list, (s, i) => channelRow(s, () => playChannel(s, list, i)));
}

function channelRow(s, onPlay, active) {
  const now = h('div', { class: 'now', 'data-now': s.i });
  const row = h('button', { class: 'channel' + (active ? ' on' : ''), onclick: onPlay },
    h('div', { class: 'no' }, s.no || ''),
    h('div', { class: 'logo-box' }, ...picture(s.l, s.n)),
    h('div', { class: 'info' }, h('div', { class: 'name' }, s.n), now),
    isFav(s.k) ? h('span', { class: 'star', icon: 'star' }) : null);
  // Programm des Senders: lange drücken oder Rechtsklick
  let timer;
  row.addEventListener('contextmenu', (e) => { e.preventDefault(); location.hash = `#channel/${encodeURIComponent(s.i)}`; });
  row.addEventListener('pointerdown', () => { timer = setTimeout(() => { timer = 'long'; }, 600); });
  row.addEventListener('pointerup', () => { if (timer !== 'long') clearTimeout(timer); });
  row.addEventListener('click', (e) => { if (timer === 'long') { e.stopImmediatePropagation(); timer = null; location.hash = `#channel/${encodeURIComponent(s.i)}`; } }, true);
  if (S.now.has(s.i)) fillNow(now, s.i);
  nowObserver.observe(now);
  return row;
}

function playChannel(s, list, index) {
  player.open({ ref: s.k, title: s.n, image: s.l, live: true, stream: s, list, index });
}

// ---------- Programm eines Senders ----------

async function channelPage(id, streamId) {
  $main.append(loading());
  const c = await catalog('LIVE');
  const s = c.byKey.get('LIVE:' + streamId);
  if (!s) throw new HttpError(404, 'Sender nicht gefunden.');
  const r = await api('epg?id=' + encodeURIComponent(streamId));
  if (id !== routeId) return;
  const fav = h('button', { class: 'icon-btn' + (isFav(s.k) ? ' on' : ''), icon: isFav(s.k) ? 'star' : 'starOff', title: 'Favorit' });
  fav.onclick = async () => { const on = await toggleFav(s.k); fav.className = 'icon-btn' + (on ? ' on' : ''); fav.innerHTML = ICONS[on ? 'star' : 'starOff']; };
  $main.replaceChildren(
    h('div', { class: 'toolbar' }, h('button', { class: 'icon-btn', icon: 'back', onclick: () => history.back() }),
      h('div', { class: 'logo-box' }, ...picture(s.l, s.n)), h('h1', null, s.n), fav,
      h('button', { class: 'btn primary', icon: 'play', onclick: () => playChannel(s, [s], 0) }, 'Live ansehen')));
  const box = h('div', { class: 'epg-list' });
  $main.append(box);
  if (!r.list.length) { box.append(h('p', { class: 'dim' }, 'Kein Programm verfügbar.')); return; }
  const now = Date.now();
  let lastDay = '';
  let current = null;
  for (const p of r.list) {
    const day = new Date(p.s).toLocaleDateString('de-DE', { weekday: 'long', day: 'numeric', month: 'long' });
    if (day !== lastDay) { box.append(h('div', { class: 'day' }, day)); lastDay = day; }
    const past = p.e <= now;
    const isNow = p.s <= now && p.e > now;
    const canCatchup = r.archive > 0 && p.s < now && p.s > now - r.archive * 86400000;
    const row = h('button', { class: 'prog' + (isNow ? ' now' : past ? ' past' : '') },
      h('div', { class: 'time' }, `${clock(p.s)} – ${clock(p.e)}`),
      h('div', null, h('div', { class: 't' }, p.t), p.d ? h('div', { class: 'd' }, p.d) : null),
      canCatchup ? h('span', { class: 'cu' }, isNow ? 'Von vorn' : 'Ansehen') : null);
    if (canCatchup) {
      row.onclick = () => player.open({ ref: `CU:${s.i}:${p.s}:${Math.min(p.e, now)}`, title: p.t, subtitle: `${s.n} · ${clock(p.s)}`, image: s.l, live: false, catchup: true });
    } else if (isNow) {
      row.onclick = () => playChannel(s, [s], 0);
    }
    if (isNow) current = row;
    box.append(row);
  }
  current?.scrollIntoView({ block: 'center' });
}

// ---------- Filme und Serien ----------

function posterCard(s) {
  const kind = s.k.split(':')[0];
  return h('a', { class: 'poster', href: kind === 'MOVIE' ? `#movie/${encodeURIComponent(s.i)}` : `#serie/${encodeURIComponent(s.i)}` },
    h('div', { class: 'img' }, ...picture(s.l, s.n), s.r ? h('span', { class: 'rating' }, String(s.r).slice(0, 3)) : null, watchMark(s)),
    h('div', { class: 't' }, s.n));
}

const SORTS = [['', 'Wie beim Anbieter'], ['new', 'Neueste zuerst'], ['az', 'A – Z'], ['rating', 'Beste Bewertung']];

async function gridPage(id, kind, catId) {
  $main.append(loading());
  const [c] = await Promise.all([catalog(kind), loadState()]);
  if (id !== routeId) return;
  const base = kind === 'MOVIE' ? 'movies' : 'series';
  const favs = (S.state.favorites || []).filter((k) => k.startsWith(kind + ':')).map((k) => c.byKey.get(k)).filter(Boolean);
  const cats = [{ id: '*', name: 'Alle' }];
  if (favs.length) cats.push({ id: 'fav', name: 'Favoriten' });
  cats.push(...c.categories.filter((x) => x.id !== '*' && (c.byCat.has(x.id) || (c.locked || []).includes(x.id))));
  const current = catId || '*';
  let list = current === 'fav' ? favs : current === '*' ? c.streams : c.byCat.get(current) || [];
  const sort = sessionStorage.getItem('sort') || '';
  if (sort === 'new') list = [...list].sort((a, b) => (b.a || 0) - (a.a || 0));
  if (sort === 'az') list = [...list].sort((a, b) => a.n.localeCompare(b.n, 'de'));
  if (sort === 'rating') list = [...list].sort((a, b) => (parseFloat(b.r) || 0) - (parseFloat(a.r) || 0));

  const catSelect = h('select', { onchange: (e) => { location.hash = `#${base}/${encodeURIComponent(e.target.value)}`; } },
    cats.map((g) => h('option', { value: g.id, selected: g.id === current }, g.name + ((c.locked || []).includes(g.id) ? ' 🔒' : ''))));
  const sortSelect = h('select', { onchange: (e) => { try { sessionStorage.setItem('sort', e.target.value); } catch (x) { /* egal */ } route(); } },
    SORTS.map(([v, t]) => h('option', { value: v, selected: v === sort }, t)));
  const grid = h('div', { class: 'grid' });
  $main.replaceChildren(h('div', { class: 'toolbar' }, h('h1', null, kind === 'MOVIE' ? 'Filme' : 'Serien'), catSelect, sortSelect), grid);
  if ((c.locked || []).includes(current)) {
    grid.replaceWith(h('div', { class: 'card' }, h('p', null, 'Diese Gruppe ist durch die Kindersicherung gesperrt.'),
      h('button', { class: 'btn primary', onclick: unlockDialog }, 'Mit PIN entsperren')));
    return;
  }
  if (!list.length) { grid.replaceWith(h('p', { class: 'dim' }, 'Hier ist nichts.')); return; }
  lazyList(grid, list, posterCard, 60);
}

function detailHeader(s, info, kind) {
  const back = info.backdrop || info.cover || s.l;
  const fav = h('button', { class: 'icon-btn' + (isFav(s.k) ? ' on' : ''), icon: isFav(s.k) ? 'star' : 'starOff', title: 'Favorit' });
  fav.onclick = async () => { const on = await toggleFav(s.k); fav.className = 'icon-btn' + (on ? ' on' : ''); fav.innerHTML = ICONS[on ? 'star' : 'starOff']; };
  const meta = [info.year || s.y, info.genre || s.g, info.duration, (info.rating || s.r) ? `★ ${String(info.rating || s.r).slice(0, 3)}` : null].filter(Boolean);
  const buttons = h('div', { class: 'row' });
  const text = h('div', { class: 'text' },
    h('h1', null, s.n), h('div', { class: 'meta' }, meta.map((m) => h('span', null, m))), buttons,
    info.plot ? h('p', { class: 'plot' }, info.plot) : null,
    info.director ? h('div', { class: 'people' }, 'Regie: ' + info.director) : null,
    info.cast ? h('div', { class: 'people' }, 'Mit: ' + info.cast) : null);
  const el = h('div', { class: 'detail' },
    h('div', { class: 'backdrop', style: back ? `background-image:url("${img(back).replace(/"/g, '%22')}")` : '' }),
    h('div', { class: 'body' },
      h('div', { class: 'cover' }, ...picture(info.cover || s.l, '')), text));
  buttons.append(h('button', { class: 'icon-btn', icon: 'back', onclick: () => history.back(), title: 'Zurück' }));
  return { el, buttons, fav };
}

async function moviePage(id, movieId) {
  $main.append(loading());
  const c = await catalog('MOVIE');
  const s = c.byKey.get('MOVIE:' + movieId);
  if (!s) throw new HttpError(404, 'Film nicht gefunden.');
  const info = await api('vod?id=' + encodeURIComponent(movieId)).catch(() => ({}));
  if (id !== routeId) return;
  const { el, buttons, fav } = detailHeader(s, info, 'MOVIE');
  const item = { ref: s.k, title: s.n, image: info.cover || s.l, parent: s.k, live: false };
  if (info.position > 5000) {
    buttons.append(h('button', { class: 'btn primary', icon: 'play', onclick: () => player.open({ ...item, start: info.position }) }, `Fortsetzen ab ${duration(info.position)}`),
      h('button', { class: 'btn', onclick: () => player.open({ ...item, start: 0 }) }, 'Von vorn'));
  } else {
    buttons.append(h('button', { class: 'btn primary', icon: 'play', onclick: () => player.open({ ...item, start: 0 }) }, 'Abspielen'));
  }
  await loadState();
  buttons.append(watchedButton([s.k], 'Als gesehen markieren', 'Gesehen'), fav);
  $main.replaceChildren(el);
}

async function seriesPage(id, seriesId, season) {
  $main.append(loading());
  const c = await catalog('SERIES');
  const s = c.byKey.get('SERIES:' + seriesId);
  if (!s) throw new HttpError(404, 'Serie nicht gefunden.');
  const info = await api('series?id=' + encodeURIComponent(seriesId));
  if (id !== routeId) return;
  const { el, buttons, fav } = detailHeader(s, info, 'SERIES');
  const seasons = info.seasons || [];
  // Weiter mit der zuletzt gesehenen Folge
  const cont = (S.state.continue || []).find((x) => x.parent === s.k);
  const all = seasons.flatMap((x) => x.episodes.map((e) => ({ ...e, season: x.season })));
  const epItem = (e) => ({
    ref: `EP:${seriesId}:${e.id}`, title: s.n, subtitle: `S${e.season} E${e.no} · ${e.title}`, image: e.image || info.cover || s.l,
    parent: s.k, recent: s.k, live: false, next: () => {
      const i = all.findIndex((x) => x.id === e.id);
      return all[i + 1] ? epItem(all[i + 1]) : null;
    },
  });
  watchedEps.clear();
  all.forEach((e) => { if (e.watched) watchedEps.add('EP:' + e.id); });
  const contEp = cont && all.find((e) => 'EP:' + e.id === cont.key);
  // Wie bei Kodi: angefangene Folge, sonst die erste ungesehene
  const nextEp = contEp || all.find((e) => !watchedEps.has('EP:' + e.id));
  if (contEp) buttons.append(h('button', { class: 'btn primary', icon: 'play', onclick: () => player.open({ ...epItem(contEp), start: cont.position }) }, `Weiter: S${contEp.season} E${contEp.no}`));
  else if (nextEp && nextEp !== all[0]) buttons.append(h('button', { class: 'btn primary', icon: 'play', onclick: () => player.open({ ...epItem(nextEp), start: 0 }) }, `Weiter: S${nextEp.season} E${nextEp.no}`));
  else if (all.length) buttons.append(h('button', { class: 'btn primary', icon: 'play', onclick: () => player.open({ ...epItem(all[0]), start: 0 }) }, 'Erste Folge'));
  buttons.append(fav);
  const chosen = season ?? (nextEp ? nextEp.season : seasons[0]?.season);
  const tabs = h('div', { class: 'chips' });
  const list = h('div');
  const seasonDone = (x) => x.episodes.length > 0 && x.episodes.every((e) => watchedEps.has('EP:' + e.id));
  const paintTabs = () => tabs.querySelectorAll('.chip').forEach((t) => {
    const x = seasons.find((y) => y.season === Number(t.dataset.s));
    t.replaceChildren(...(seasonDone(x) ? [h('span', { class: 'chip-check', icon: 'check' })] : []), `Staffel ${x.season}`);
  });
  const show = (no) => {
    tabs.querySelectorAll('.chip').forEach((t) => t.classList.toggle('on', Number(t.dataset.s) === no));
    paintTabs();
    list.replaceChildren();
    const sea = seasons.find((x) => x.season === no);
    if (sea && sea.episodes.length) {
      list.append(h('div', { class: 'row', style: 'margin:4px 0 12px' },
        watchedButton(sea.episodes.map((e) => 'EP:' + e.id), 'Staffel als gesehen markieren', 'Staffel gesehen', () => show(no))));
    }
    for (const e of sea ? sea.episodes : []) {
      const ep = { ...e, season: no };
      const share = e.position > 0 ? Math.min(100, (e.position / ((parseInt(e.duration, 10) || 45) * 60000)) * 100) : 0;
      const key = 'EP:' + e.id;
      const seen = watchedEps.has(key);
      const mark = h('button', { class: 'icon-btn ep-mark' + (seen ? ' seen' : ''), icon: seen ? 'checkCircle' : 'checkOff', title: seen ? 'Gesehen' : 'Als gesehen markieren' });
      mark.onclick = async (ev) => { ev.stopPropagation(); await setWatched([key], !watchedEps.has(key)); show(no); };
      list.append(h('div', { class: 'episode' + (seen ? ' watched' : ''), role: 'button', tabindex: 0, onclick: () => player.open({ ...epItem(ep), start: e.position > 5000 ? e.position : 0 }) },
        h('div', { class: 'img' }, ...picture(e.image || info.backdrop || info.cover, ''), share ? h('div', { class: 'bar' }, h('i', { style: `width:${share}%` })) : null,
          seen ? h('span', { class: 'seen-mark', icon: 'check' }) : null),
        h('div', { class: 'grow' }, h('div', { class: 't' }, `${e.no}. ${e.title}`), e.plot ? h('div', { class: 'p' }, e.plot) : null),
        mark));
    }
  };
  seasons.forEach((x) => tabs.append(h('button', { class: 'chip', 'data-s': x.season, onclick: () => show(x.season) }, `Staffel ${x.season}`)));
  $main.replaceChildren(el, h('div', { class: 'episodes' }, seasons.length > 1 ? tabs : null, list));
  if (seasons.length) show(chosen);
  else list.append(h('p', { class: 'dim' }, 'Keine Folgen gefunden.'));
}

// ---------- Suche ----------

async function searchPage(id) {
  const input = h('input', { type: 'search', placeholder: 'Sender, Filme, Serien …', autocomplete: 'off', value: sessionStorage.getItem('q') || '' });
  const out = h('div');
  $main.append(h('h1', null, 'Suche'), input, out);
  setTimeout(() => input.focus(), 50);
  const cats = {};
  let timer;
  const run = async () => {
    const q = fold(input.value.trim());
    try { sessionStorage.setItem('q', input.value); } catch (e) { /* egal */ }
    out.replaceChildren();
    if (q.length < 2) return;
    out.append(loading());
    for (const k of ['LIVE', 'MOVIE', 'SERIES']) cats[k] = cats[k] || await catalog(k).catch(() => null);
    if (id !== routeId) return;
    out.replaceChildren();
    const words = q.split(/\s+/);
    const match = (s) => { const n = fold(s.n); return words.every((w) => n.includes(w)); };
    const live = cats.LIVE ? cats.LIVE.streams.filter(match).slice(0, 50) : [];
    const movies = cats.MOVIE ? cats.MOVIE.streams.filter(match).slice(0, 60) : [];
    const series = cats.SERIES ? cats.SERIES.streams.filter(match).slice(0, 60) : [];
    if (live.length) {
      const box = h('div');
      live.forEach((s, i) => box.append(channelRow(s, () => playChannel(s, live, i))));
      out.append(h('h2', null, `Sender (${live.length})`), box);
    }
    if (movies.length) out.append(h('h2', null, `Filme (${movies.length})`), h('div', { class: 'grid' }, movies.map(posterCard)));
    if (series.length) out.append(h('h2', null, `Serien (${series.length})`), h('div', { class: 'grid' }, series.map(posterCard)));
    if (!live.length && !movies.length && !series.length) out.append(h('p', { class: 'dim' }, 'Nichts gefunden.'));
  };
  input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(run, 300); });
  run();
}

// ---------- Dialoge ----------

function dialog(...content) {
  const back = h('div', { class: 'dialog-back' });
  const box = h('div', { class: 'dialog' }, ...content);
  back.append(box);
  back.addEventListener('click', (e) => { if (e.target === back) back.remove(); });
  document.body.append(back);
  return { close: () => back.remove(), box };
}

function unlockDialog() {
  const pin = h('input', { type: 'password', inputmode: 'numeric', autocomplete: 'off', placeholder: 'PIN' });
  const msg = h('div');
  const d = dialog(h('h2', null, 'Kindersicherung'), h('p', { class: 'dim' }, 'PIN aus der Klar-TV-App eingeben.'),
    h('form', {
      onsubmit: async (e) => {
        e.preventDefault();
        try {
          await api('unlock', { pin: pin.value });
          d.close();
          await loadState();
          resetCatalogs();
          route();
        } catch (err) { msg.replaceChildren(h('div', { class: 'msg error' }, err.message)); }
      },
    }, pin, msg, h('div', { class: 'row', style: 'margin-top:14px' }, h('button', { class: 'btn primary', type: 'submit' }, 'Entsperren'),
      h('button', { class: 'btn', type: 'button', onclick: () => d.close() }, 'Abbrechen'))));
  setTimeout(() => pin.focus(), 50);
}

// ---------- Einstellungen ----------

async function settingsPage(id) {
  await loadState();
  if (id !== routeId) return;
  const st = S.state;
  $main.append(h('h1', null, 'Einstellungen'));

  // Anbieter
  const prov = h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Anbieter'));
  if (!st.accounts.length) prov.append(h('p', { class: 'dim' }, 'Noch kein Anbieter eingerichtet.'));
  for (const a of st.accounts) {
    prov.append(h('div', { class: 'row', style: 'margin-bottom:10px' },
      h('div', { class: 'grow' }, h('b', null, a.name), a.id === st.active ? h('span', { class: 'dim' }, ' · aktiv') : null,
        h('div', { class: 'dim small' }, a.type === 'M3U' ? 'M3U-Playlist' : `${a.server} · ${a.username}`)),
      a.id !== st.active ? h('button', { class: 'btn', onclick: async () => { await api('accounts/activate', { id: a.id }); resetCatalogs(); route(); } }, 'Verwenden') : null,
      h('button', { class: 'btn', onclick: () => accountDialog(a) }, 'Ändern'),
      h('button', { class: 'btn danger', onclick: async () => { if (confirm(`„${a.name}“ entfernen? Favoriten und Verlauf dieses Anbieters gehen verloren.`)) { await api('accounts/delete', { id: a.id }); resetCatalogs(); route(); } } }, 'Entfernen')));
  }
  prov.append(h('div', { class: 'row' }, h('button', { class: 'btn primary', onclick: () => accountDialog(null) }, 'Anbieter hinzufügen'),
    h('button', { class: 'btn', onclick: syncDialog }, 'Aus der App übernehmen (QR-Code)')));
  $main.append(prov);
  if (!st.accounts.length) return;

  // Programmführer
  const epg = st.epg || '';
  let epgText = 'Wird beim Öffnen von Live-TV geladen.';
  if (epg === 'loading') epgText = 'Wird gerade eingelesen …';
  else if (epg === 'none') epgText = 'Der Anbieter hat keinen Programmführer; die Sendungen kommen direkt vom Anbieter.';
  else if (epg.startsWith('ok:')) {
    const [, t, n, f] = epg.split(':');
    epgText = `${Number(n).toLocaleString('de-DE')} Sendungen, Stand ${new Date(Number(t)).toLocaleString('de-DE')}`;
    if (Number(f) > 0) epgText += ` · ${f === '1' ? 'Eine Quelle war' : f + ' Quellen waren'} nicht erreichbar`;
  }
  else if (epg.startsWith('error:')) epgText = 'Fehler: ' + epg.slice(6);
  const acc = st.accounts.find((x) => x.id === st.active) || st.accounts[0];
  const urls = h('textarea', { rows: 3, autocapitalize: 'off', spellcheck: 'false', placeholder: 'http://…/guide.xml.gz' });
  urls.value = acc.epgUrls || '';
  const provider = h('input', { type: 'checkbox', checked: acc.epgProvider !== false });
  const shift = h('select', null, Array.from({ length: 49 }, (_, i) => (i - 24) * 30).map((m) =>
    h('option', { value: m, selected: (acc.epgShift || 0) === m }, m === 0 ? 'Keine' : `${m > 0 ? '+' : '−'}${shiftText(Math.abs(m))}`)));
  const every = h('select', null, [[0, 'Nur von Hand'], [6, 'Alle 6 Stunden'], [12, 'Alle 12 Stunden'], [24, 'Einmal am Tag']]
    .map(([v, t]) => h('option', { value: v, selected: Number(st.refreshHours ?? 12) === v }, t)));
  const epgMsg = h('div');
  $main.append(h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Programmführer'), h('p', { class: 'dim' }, epgText),
    h('label', { class: 'field' }, h('span', null, 'Eigene Programmführer (XMLTV), eine Adresse je Zeile. Kennen mehrere Quellen einen Sender, gilt die obere.'), urls),
    h('label', { class: 'row', style: 'gap:8px;margin-bottom:14px' }, provider, h('span', null, 'Programmführer des Anbieters zusätzlich nutzen')),
    h('label', { class: 'field' }, h('span', null, 'Sendezeiten verschieben, wenn der Anbieter sie falsch liefert'), shift),
    h('label', { class: 'field' }, h('span', null, 'Listen und Programm automatisch neu laden'), every),
    epgMsg,
    h('div', { class: 'row' },
      h('button', { class: 'btn primary', onclick: async () => {
        try {
          await api('epg/settings', { urls: urls.value, provider: provider.checked, shift: Number(shift.value), refreshHours: Number(every.value) });
          await loadState();
          epgMsg.replaceChildren(h('div', { class: 'msg ok' }, 'Gespeichert.'));
        } catch (e) { epgMsg.replaceChildren(h('div', { class: 'msg error' }, e.message)); }
      } }, 'Speichern'),
      h('button', { class: 'btn', onclick: async () => { await api('epg/refresh', {}); toast('Programmführer wird neu eingelesen.'); } }, 'Jetzt neu laden'),
      h('button', { class: 'btn', onclick: async () => { resetCatalogs(); for (const k of ['LIVE', 'MOVIE', 'SERIES']) await catalog(k, true).catch(() => null); toast('Listen neu geladen.'); } }, 'Listen neu laden'))));

  // Wiedergabe
  const lang = h('select', null, [['de', 'Deutsch'], ['en', 'Englisch'], ['fr', 'Französisch'], ['es', 'Spanisch'], ['it', 'Italienisch'], ['tr', 'Türkisch']]
    .map(([v, t]) => h('option', { value: v, selected: st.audioLanguage === v }, t)));
  const fmt = h('select', null, h('option', { value: 'ts', selected: st.liveFormat !== 'm3u8' }, 'MPEG-TS (empfohlen)'), h('option', { value: 'm3u8', selected: st.liveFormat === 'm3u8' }, 'HLS'));
  const ua = h('input', { type: 'text', value: st.userAgent, placeholder: 'Standard' });
  const playMsg = h('div');
  $main.append(h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Wiedergabe'),
    h('label', { class: 'field' }, h('span', null, 'Bevorzugte Tonsprache'), lang),
    h('label', { class: 'field' }, h('span', null, 'Format für Live-TV (Xtream)'), fmt),
    h('label', { class: 'field' }, h('span', null, 'Player-Kennung (User-Agent), nur wenn der Anbieter es verlangt'), ua),
    S.hevc ? null : h('p', { class: 'dim small' }, 'Dieser Browser kann kein H.265. Solche Sender wandelt der Server um; das braucht mehr Rechenleistung.'),
    playMsg,
    h('button', { class: 'btn primary', onclick: async () => {
      await api('settings', { audioLanguage: lang.value, liveFormat: fmt.value, userAgent: ua.value });
      if (fmt.value !== st.liveFormat) resetCatalogs();
      playMsg.replaceChildren(h('div', { class: 'msg ok' }, 'Gespeichert.'));
    } }, 'Speichern')));

  // Kindersicherung
  if (st.hasPin) {
    $main.append(h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Kindersicherung'),
      h('p', { class: 'dim' }, st.unlocked ? 'Gesperrte Gruppen sind in dieser Sitzung sichtbar.' : 'Gesperrte Gruppen sind ausgeblendet. Die PIN stammt aus der Klar-TV-App.'),
      st.unlocked
        ? h('button', { class: 'btn', onclick: async () => { await api('lock', {}); resetCatalogs(); route(); } }, 'Wieder sperren')
        : h('button', { class: 'btn primary', onclick: unlockDialog }, 'Mit PIN entsperren')));
  }
  serverCards(st);
}

function serverCards(st) {
  // Zugang
  const old = h('input', { type: 'password', autocomplete: 'current-password' });
  const neu = h('input', { type: 'password', autocomplete: 'new-password' });
  const neu2 = h('input', { type: 'password', autocomplete: 'new-password' });
  const msg = h('div');
  $main.append(h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Zugang zum Server'),
    h('p', { class: 'dim' }, `Angemeldet als ${st.user}.`),
    h('label', { class: 'field' }, h('span', null, 'Bisheriges Passwort'), old),
    h('label', { class: 'field' }, h('span', null, 'Neues Passwort (mindestens 8 Zeichen)'), neu),
    h('label', { class: 'field' }, h('span', null, 'Neues Passwort wiederholen'), neu2),
    msg,
    h('div', { class: 'row' },
      h('button', { class: 'btn primary', onclick: async () => {
        msg.replaceChildren();
        if (neu.value !== neu2.value) { msg.append(h('div', { class: 'msg error' }, 'Die neuen Passwörter sind verschieden.')); return; }
        try {
          await api('password', { old: old.value, password: neu.value });
          msg.append(h('div', { class: 'msg ok' }, 'Passwort geändert. Andere Geräte sind jetzt abgemeldet.'));
          old.value = neu.value = neu2.value = '';
        } catch (e) { msg.append(h('div', { class: 'msg error' }, e.message)); }
      } }, 'Passwort ändern'),
      h('button', { class: 'btn', onclick: async () => { await api('logout', {}); location.hash = ''; boot(); } }, 'Abmelden'))));

  const port = h('input', { type: 'number', min: 1024, max: 65535, value: st.port });
  const pmsg = h('div');
  $main.append(h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Server'),
    h('p', { class: 'dim' }, `Klar TV Server ${st.version}${st.ffmpeg ? '' : ' – FFmpeg fehlt, bitte neu installieren.'}`),
    h('label', { class: 'field' }, h('span', null, 'Port im Heimnetz (Standard 8095). Danach Cloudflare-Tunnel anpassen.'), port),
    pmsg,
    h('button', { class: 'btn', onclick: async () => {
      try {
        const r = await api('settings', { port: Number(port.value) });
        if (r.restart) pmsg.replaceChildren(h('div', { class: 'msg ok' }, `Der Server startet neu und ist gleich unter Port ${port.value} erreichbar.`));
      } catch (e) { pmsg.replaceChildren(h('div', { class: 'msg error' }, e.message)); }
    } }, 'Port ändern')));
  updateCard();
}

// Updates: der Server holt neue Versionen selbst und installiert sie nachts
async function updateCard() {
  const box = h('div', { class: 'card' }, h('h2', { style: 'margin-top:0' }, 'Updates'));
  $main.append(box);
  let u;
  try { u = await api('update'); } catch (e) { return; }
  const draw = (u) => {
    box.replaceChildren(h('h2', { style: 'margin-top:0' }, 'Updates'));
    if (!u.available) { box.append(h('p', { class: 'dim' }, 'Nur der installierte Dienst aktualisiert sich selbst.')); return; }
    let text = `Installiert: ${u.version}.`;
    if (u.state === 'checking') text += ' Suche nach Updates …';
    else if (u.state === 'downloading') text += ` Version ${u.latest} wird geladen …`;
    else if (u.state === 'ready') text += ` Version ${u.latest} ist geladen und geprüft${u.auto ? ' und wird zwischen 3 und 5 Uhr installiert, wenn niemand schaut' : ''}.`;
    else if (u.state === 'installing') text += ` Version ${u.latest} wird installiert. Der Server ist gleich wieder da.`;
    else if (u.state === 'error') text += ` Fehler: ${u.error}`;
    else if (u.lastCheck) text += ` Aktuell, zuletzt geprüft ${new Date(u.lastCheck).toLocaleString('de-DE')}.`;
    const auto = h('input', { type: 'checkbox', checked: u.auto });
    auto.onchange = async () => draw(await api('update/settings', { auto: auto.checked }));
    box.append(h('p', { class: 'dim' }, text),
      h('label', { class: 'row', style: 'gap:8px;margin-bottom:12px' }, auto, h('span', null, 'Automatisch aktualisieren (prüft alle sechs Stunden bei GitHub, installiert nur signierte Versionen)')),
      h('div', { class: 'row' },
        h('button', { class: 'btn', disabled: u.state === 'checking' || u.state === 'downloading' || u.state === 'installing', onclick: async () => {
          draw(await api('update/check', {}));
          poll();
        } }, 'Jetzt nach Updates suchen'),
        u.state === 'ready' ? h('button', { class: 'btn primary', onclick: async () => {
          if (!confirm(`Version ${u.latest} jetzt installieren? Laufende Wiedergaben brechen ab, der Server ist nach etwa einer Minute wieder da.`)) return;
          await api('update/install', {});
          draw(Object.assign({}, u, { state: 'installing' }));
        } }, 'Jetzt installieren') : null));
  };
  // Solange gesucht oder geladen wird, nachschauen
  const poll = async () => {
    for (let i = 0; i < 120 && box.isConnected; i++) {
      await new Promise((r) => setTimeout(r, 2000));
      const n = await api('update').catch(() => null);
      if (!n) return;
      draw(n);
      if (n.state !== 'checking' && n.state !== 'downloading') return;
    }
  };
  draw(u);
  if (u.state === 'checking' || u.state === 'downloading') poll();
}

/** 90 → „1:30 Std.“ */
function shiftText(m) {
  return m % 60 === 0 ? `${m / 60} Std.` : `${Math.floor(m / 60)}:${String(m % 60).padStart(2, '0')} Std.`;
}

function accountDialog(a) {
  const type = h('select', null, h('option', { value: 'XTREAM', selected: !a || a.type !== 'M3U' }, 'Xtream Codes (Server, Benutzer, Passwort)'),
    h('option', { value: 'M3U', selected: a && a.type === 'M3U' }, 'M3U-Playlist (Adresse)'));
  const name = h('input', { type: 'text', value: a ? a.name : '', placeholder: 'z. B. Mein IPTV' });
  const server = h('input', { type: 'url', value: a ? a.server : '', placeholder: 'http://anbieter.example:8080', autocapitalize: 'off' });
  const user = h('input', { type: 'text', value: a ? a.username : '', autocapitalize: 'off', autocomplete: 'off' });
  const pass = h('input', { type: 'password', autocomplete: 'off', placeholder: a && a.hasPassword ? 'unverändert' : '' });
  const m3u = h('input', { type: 'url', autocapitalize: 'off', placeholder: a && a.hasM3u ? 'unverändert' : 'http://…/playlist.m3u' });
  const xt = h('div', null, h('label', { class: 'field' }, h('span', null, 'Server'), server),
    h('label', { class: 'field' }, h('span', null, 'Benutzername'), user), h('label', { class: 'field' }, h('span', null, 'Passwort'), pass));
  const mu = h('div', null, h('label', { class: 'field' }, h('span', null, 'Adresse der Playlist'), m3u));
  const sw = () => { xt.classList.toggle('hidden', type.value === 'M3U'); mu.classList.toggle('hidden', type.value !== 'M3U'); };
  type.onchange = sw;
  const msg = h('div');
  const save = h('button', { class: 'btn primary', type: 'submit' }, 'Prüfen und speichern');
  const d = dialog(h('h2', null, a ? 'Anbieter ändern' : 'Anbieter hinzufügen'),
    h('form', {
      onsubmit: async (e) => {
        e.preventDefault();
        save.disabled = true;
        msg.replaceChildren(h('div', { class: 'msg' }, 'Zugang wird geprüft …'));
        try {
          await api('accounts/save', { id: a ? a.id : '', type: type.value, name: name.value, server: server.value, username: user.value, password: pass.value, m3uUrl: m3u.value });
          d.close();
          resetCatalogs();
          await loadState();
          route();
          toast('Anbieter gespeichert.');
        } catch (err) {
          msg.replaceChildren(h('div', { class: 'msg error' }, err.message));
        } finally { save.disabled = false; }
      },
    },
    h('label', { class: 'field' }, h('span', null, 'Art'), type),
    h('label', { class: 'field' }, h('span', null, 'Name'), name), xt, mu,
    msg, h('div', { class: 'row' }, save, h('button', { class: 'btn', type: 'button', onclick: () => d.close() }, 'Abbrechen'))));
  sw();
}

async function syncDialog() {
  let r;
  try { r = await api('sync/start', {}); } catch (e) { toast(e.message); return; }
  const qr = h('div', { class: 'qr' });
  qr.innerHTML = r.qr; // vom eigenen Server erzeugt
  const status = h('div', { class: 'msg' }, 'Warte auf die App …');
  const actions = h('div', { class: 'row' });
  let timer;
  const d = dialog(h('h2', null, 'Aus der Klar-TV-App übernehmen'),
    h('p', null, 'In der Klar-TV-App: Einstellungen → Geräte abgleichen → „Code des anderen Geräts scannen“, dann „Stand von hier dorthin senden“. Handy und Server müssen im selben Netz sein.'),
    qr, status, actions,
    h('button', { class: 'btn', style: 'margin-top:12px', onclick: () => { clearInterval(timer); api('sync/stop', {}).catch(() => {}); d.close(); } }, 'Schließen'));
  timer = setInterval(async () => {
    if (!document.body.contains(d.box)) { clearInterval(timer); return; }
    let s;
    try { s = await api('sync/state'); } catch (e) { return; }
    if (s.offer && !actions.children.length) {
      status.textContent = `„${s.offer}“ möchte seinen Stand schicken: Anbieter, Favoriten, Verlauf und Einstellungen. Der bisherige Stand des Servers wird ersetzt.`;
      actions.append(
        h('button', { class: 'btn primary', onclick: async () => { actions.replaceChildren(); await api('sync/answer', { ok: true }); } }, 'Übernehmen'),
        h('button', { class: 'btn', onclick: async () => { actions.replaceChildren(); await api('sync/answer', { ok: false }); status.textContent = 'Abgelehnt.'; } }, 'Ablehnen'));
    }
    if (s.done) {
      clearInterval(timer);
      status.className = 'msg ok';
      status.textContent = s.done;
      qr.remove();
      resetCatalogs();
      await loadState();
      route();
    } else if (s.expired) {
      clearInterval(timer);
      status.className = 'msg error';
      status.textContent = 'Der Code ist abgelaufen. Bitte neu öffnen.';
    }
  }, 1500);
}

// ---------- Player ----------

const player = (() => {
  let el, video, mp, item, offset = 0, dur = 0, probe = null, audio = null, hideTimer, saveTimer, open = false, layer;
  let $title, $sub, $seek, $pos, $dur, $playBtn, $spinner, $error, $nowNext, $drawer, $menu, $seekRow, $liveTools;

  function build() {
    video = h('video', { playsinline: true, autoplay: true });
    $title = h('b');
    $sub = h('span');
    $seek = h('input', { type: 'range', min: 0, max: 1000, value: 0, step: 1 });
    $pos = h('span', null, '0:00');
    $dur = h('span', null, '0:00');
    $playBtn = h('button', { class: 'icon-btn', icon: 'pause', onclick: (e) => { e.stopPropagation(); toggle(); } });
    $spinner = h('div', { class: 'spinner' });
    $error = h('div', { class: 'error hidden' });
    $nowNext = h('div', { class: 'now-next' });
    $drawer = h('div', { class: 'drawer', onclick: (e) => e.stopPropagation() });
    $menu = h('div', { class: 'menu hidden', onclick: (e) => e.stopPropagation() });
    $seekRow = h('div', { class: 'seek' }, $pos, $seek, $dur);
    $liveTools = h('div', { class: 'row' },
      h('button', { class: 'icon-btn', icon: 'up', title: 'Sender zurück', onclick: (e) => { e.stopPropagation(); zap(-1); } }),
      h('button', { class: 'icon-btn', icon: 'down', title: 'Sender weiter', onclick: (e) => { e.stopPropagation(); zap(1); } }),
      h('button', { class: 'icon-btn', icon: 'list', title: 'Senderliste', onclick: (e) => { e.stopPropagation(); showDrawer(); } }));
    const rew = h('button', { class: 'icon-btn', icon: 'rew', onclick: (e) => { e.stopPropagation(); jump(-15000); } });
    const fwd = h('button', { class: 'icon-btn', icon: 'fwd', onclick: (e) => { e.stopPropagation(); jump(30000); } });
    layer = h('div', { class: 'layer' },
      h('div', { class: 'top' }, h('button', { class: 'icon-btn', icon: 'back', onclick: (e) => { e.stopPropagation(); history.back(); } }),
        h('div', { class: 'title' }, $title, $sub)),
      h('div', { class: 'middle' }, rew, $playBtn, fwd),
      h('div', { class: 'bottom' }, $nowNext, $seekRow,
        h('div', { class: 'tools' }, $liveTools, h('div', { class: 'grow' }),
          h('button', { class: 'icon-btn', icon: 'audio', title: 'Tonspur', onclick: (e) => { e.stopPropagation(); audioMenu(); } }),
          h('button', { class: 'icon-btn', icon: 'full', title: 'Vollbild', onclick: (e) => { e.stopPropagation(); fullscreen(); } }))));
    el = h('div', { class: 'player hidden' }, video, $spinner, $error, layer, $drawer, $menu);
    el.addEventListener('click', () => { if ($menu.classList.contains('hidden') && !$drawer.classList.contains('open')) { el.classList.contains('idle') ? wake() : (video.paused ? wake() : hide()); } else { $menu.classList.add('hidden'); $drawer.classList.remove('open'); wake(); } });
    el.addEventListener('pointermove', (e) => { if (e.pointerType === 'mouse') wake(); });
    el._rew = rew;
    el._fwd = fwd;
    $seek.addEventListener('input', () => { $pos.textContent = duration(($seek.value / 1000) * dur); wake(); });
    $seek.addEventListener('change', () => start(item, ($seek.value / 1000) * dur, audio));
    $seek.addEventListener('click', (e) => e.stopPropagation());
    video.addEventListener('playing', () => { $spinner.classList.add('hidden'); $playBtn.innerHTML = ICONS.pause; schedule(); });
    video.addEventListener('waiting', () => $spinner.classList.remove('hidden'));
    video.addEventListener('pause', () => { $playBtn.innerHTML = ICONS.play; wake(); });
    video.addEventListener('timeupdate', tick);
    document.body.append(el);
  }

  const position = () => offset + (video ? video.currentTime * 1000 : 0);

  function tick() {
    if (!item || item.live) return;
    const p = position();
    if (document.activeElement !== $seek) {
      $pos.textContent = duration(p);
      $seek.value = dur ? Math.round((p / dur) * 1000) : 0;
    }
    // Folge zu Ende: nächste
    if (dur && p >= dur - 1500 && video.ended && item.next) {
      const n = item.next();
      if (n) start(n, 0);
    }
  }

  function wake() {
    el.classList.remove('idle');
    schedule();
  }
  function hide() { if (!video.paused) el.classList.add('idle'); }
  function schedule() {
    clearTimeout(hideTimer);
    hideTimer = setTimeout(hide, 4000);
  }

  function toggle() {
    if (video.paused) {
      // Live: nach einer Pause wieder an der aktuellen Stelle einsteigen
      if (item.live && video.buffered.length && video.buffered.end(video.buffered.length - 1) - video.currentTime > 30) start(item, 0, audio);
      else video.play().catch(() => {});
    } else video.pause();
  }

  let pending = null, pendingTimer;
  function jump(ms) {
    if (!item || item.live) return;
    const target = Math.max(0, Math.min(dur - 5000, (pending ?? position()) + ms));
    // Innerhalb des schon Geladenen springen, sonst neu ab der Stelle – mehrfaches Tippen zusammengefasst
    const local = (target - offset) / 1000;
    if (pending == null && local >= 0 && video.buffered.length && local <= video.buffered.end(video.buffered.length - 1)) {
      video.currentTime = local;
    } else {
      pending = target;
      $pos.textContent = duration(target);
      $seek.value = dur ? Math.round((target / dur) * 1000) : 0;
      clearTimeout(pendingTimer);
      pendingTimer = setTimeout(() => { const t = pending; pending = null; start(item, t, audio); }, 700);
    }
    wake();
  }

  function fullscreen() {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else (el.requestFullscreen ? el.requestFullscreen() : Promise.reject()).catch(() => {});
  }

  function save() {
    if (!item || item.live || item.catchup || !dur) return;
    fetch('/api/progress', {
      method: 'POST', keepalive: true, headers: { 'Content-Type': 'application/json', 'X-Klar': '1' },
      body: JSON.stringify({ ref: item.ref, parent: item.parent || item.ref, title: item.title, subtitle: item.subtitle || '', image: item.image || '', position: Math.round(position()), duration: Math.round(dur) }),
    }).catch(() => {});
  }

  function destroy() {
    if (mp) { try { mp.pause(); mp.unload(); mp.detachMediaElement(); mp.destroy(); } catch (e) { /* schon weg */ } mp = null; }
  }

  function showError(text) {
    $spinner.classList.add('hidden');
    $error.replaceChildren(h('p', null, text), h('button', { class: 'btn primary', onclick: (e) => { e.stopPropagation(); start(item, position(), audio); } }, 'Erneut versuchen'));
    $error.classList.remove('hidden');
    wake();
  }

  async function start(it, startMs, audioIdx) {
    if (item && item !== it) save();
    item = it;
    offset = it.live ? 0 : Math.max(0, Math.round(startMs || 0));
    destroy();
    $error.classList.add('hidden');
    $spinner.classList.remove('hidden');
    $title.textContent = it.title || '';
    $sub.textContent = it.subtitle || '';
    $seekRow.classList.toggle('hidden', !!it.live);
    el._rew.classList.toggle('hidden', !!it.live);
    el._fwd.classList.toggle('hidden', !!it.live);
    $liveTools.classList.toggle('hidden', !it.live || !it.list || it.list.length < 2);
    $nowNext.replaceChildren();
    wake();
    const mine = it;
    try {
      probe = await api('probe?ref=' + encodeURIComponent(it.ref));
    } catch (e) {
      if (item === mine) showError(e.message);
      return;
    }
    if (item !== mine) return;
    dur = probe.duration || 0;
    $dur.textContent = duration(dur);
    audio = audioIdx ?? (probe.chosen >= 0 ? probe.chosen : null);
    if (!window.mpegts || !mpegts.isSupported()) { showError('Dieser Browser kann den Stream nicht abspielen (Media Source Extensions fehlen).'); return; }
    const q = new URLSearchParams({ ref: it.ref, hevc: S.hevc ? '1' : '0' });
    if (offset) q.set('t', String(offset));
    if (audio != null) q.set('a', String(audio));
    if (it.recent) q.set('recent', it.recent);
    // Volle Adresse: mpegts.js lädt in einem eigenen Thread
    mp = mpegts.createPlayer({ type: 'mpegts', isLive: !!it.live, url: new URL('/play?' + q, location.href).href }, {
      enableWorker: true,
      lazyLoad: false,
      autoCleanupSourceBuffer: true,
      liveBufferLatencyChasing: !!it.live,
      liveBufferLatencyMaxLatency: 8,
      liveBufferLatencyMinRemain: 2,
    });
    mp.on(mpegts.Events.ERROR, (type, detail) => {
      if (item !== mine) return;
      showError(type === 'NetworkError' ? 'Der Stream wurde unterbrochen.' : `Wiedergabe nicht möglich (${detail || type}).`);
    });
    mp.attachMediaElement(video);
    mp.load();
    video.play().catch(() => { $spinner.classList.add('hidden'); $playBtn.innerHTML = ICONS.play; wake(); });
    if (it.live && it.stream) showNow(it.stream);
    clearInterval(saveTimer);
    saveTimer = setInterval(save, 20000);
  }

  function showNow(s) {
    const fill = () => {
      const c = S.now.get(s.i);
      $nowNext.replaceChildren();
      if (!c) return;
      const cur = c.list.find((p) => p.s <= Date.now() && p.e > Date.now());
      const next = c.list.find((p) => p.s >= Date.now());
      if (cur) {
        const share = Math.min(100, ((Date.now() - cur.s) / (cur.e - cur.s)) * 100);
        $nowNext.append(h('div', null, h('b', null, `${clock(cur.s)} ${cur.t}`)), h('div', { class: 'bar' }, h('i', { style: `width:${share}%` })));
      }
      if (next) $nowNext.append(h('div', { class: 'dim', style: 'margin-top:6px' }, `Danach ${clock(next.s)} ${next.t}`));
    };
    fill();
    S.now.delete(s.i);
    wantNow(s.i);
    setTimeout(fill, 1500);
  }

  function zap(step) {
    if (!item?.list) return;
    const n = (item.index + step + item.list.length) % item.list.length;
    const s = item.list[n];
    start({ ref: s.k, title: s.n, image: s.l, live: true, stream: s, list: item.list, index: n }, 0);
  }

  function showDrawer() {
    $drawer.replaceChildren();
    lazyList($drawer, item.list, (s, i) => channelRow(s, () => { $drawer.classList.remove('open'); start({ ref: s.k, title: s.n, image: s.l, live: true, stream: s, list: item.list, index: i }, 0); }, i === item.index), 60);
    $drawer.classList.add('open');
    $drawer.querySelector('.channel.on')?.scrollIntoView({ block: 'center' });
  }

  function audioMenu() {
    $menu.replaceChildren(h('div', { class: 'h' }, 'Tonspur'));
    const tracks = probe?.audio || [];
    if (!tracks.length) $menu.append(h('div', { class: 'h' }, 'Keine Auswahl'));
    for (const t of tracks) {
      const label = [t.lang && t.lang.toUpperCase(), t.title, t.codec && t.codec.toUpperCase(), t.channels > 2 ? `${t.channels} Kanäle` : null].filter(Boolean).join(' · ') || `Spur ${t.index + 1}`;
      $menu.append(h('button', { class: t.index === audio ? 'on' : '', onclick: () => { $menu.classList.add('hidden'); start(item, position(), t.index); } }, label));
    }
    $menu.classList.toggle('hidden');
  }

  function keys(e) {
    if (!open) return;
    if (e.key === 'Escape' && !document.fullscreenElement) history.back();
    else if (e.key === ' ') { e.preventDefault(); toggle(); }
    else if (e.key === 'ArrowLeft') jump(-15000);
    else if (e.key === 'ArrowRight') jump(30000);
    else if (e.key === 'ArrowUp' || e.key === 'PageUp') zap(-1);
    else if (e.key === 'ArrowDown' || e.key === 'PageDown') zap(1);
    else if (e.key === 'f') fullscreen();
    else return;
    wake();
  }
  document.addEventListener('keydown', keys);

  window.addEventListener('popstate', () => { if (open && !(history.state && history.state.player)) close(true); });
  window.addEventListener('pagehide', save);

  function openPlayer(it) {
    if (!el) build();
    if (!open) {
      history.pushState({ player: true }, '');
      open = true;
    }
    el.classList.remove('hidden');
    start(it, it.start || 0);
  }

  function close(fromHistory) {
    if (!open) return;
    save();
    open = false;
    clearInterval(saveTimer);
    clearTimeout(hideTimer);
    destroy();
    item = null;
    el.classList.add('hidden');
    $drawer.classList.remove('open');
    $menu.classList.add('hidden');
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (!fromHistory) history.back();
    // Fortschritt in den Listen auffrischen
    setTimeout(() => { if (/^#?(start)?$/.test(location.hash) || location.hash.startsWith('#serie') || location.hash.startsWith('#movie')) route(); }, 400);
  }

  return { open: openPlayer, close };
})();

boot();
