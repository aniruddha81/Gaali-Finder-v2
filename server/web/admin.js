// Gaali Console: polls /v1/admin/metrics and draws it. No libraries; every server-supplied string
// goes through esc() before it reaches innerHTML, because names and log lines are user-controlled.
(() => {
  'use strict';
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const n0 = v => Number(v || 0).toLocaleString();
  const bytes = v => {
    v = Number(v || 0);
    const u = ['B', 'KB', 'MB', 'GB', 'TB'];
    let i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i ? v.toFixed(v >= 100 ? 0 : 1) : v) + ' ' + u[i];
  };
  const ms = v => (v < 0 ? 'fail' : v < 10 ? v.toFixed(2) : v < 100 ? v.toFixed(1) : Math.round(v)) + (v < 0 ? '' : ' ms');
  const pct = v => (v >= 10 ? Math.round(v) : v.toFixed(1)) + '%';
  const dur = s => {
    const d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
    return d ? `${d}d ${h}h` : h ? `${h}h ${m}m` : m ? `${m}m ${s % 60}s` : `${s}s`;
  };
  const clock = t => new Date(t).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  const ago = t => { const s = Math.max(0, Math.round((Date.now() - t) / 1000)); return s < 60 ? s + 's ago' : s < 3600 ? Math.floor(s / 60) + 'm ago' : s < 86400 ? Math.floor(s / 3600) + 'h ago' : Math.floor(s / 86400) + 'd ago'; };
  const sum = (a, f) => a.reduce((t, x) => t + f(x), 0);

  // ---- session ----
  let tok = null;
  try { tok = JSON.parse(sessionStorage.getItem('gc') || 'null'); } catch { /* private mode: stay in memory */ }
  const save = s => {
    tok = s ? { access: s.accessToken, refresh: s.refreshToken } : null;
    try { tok ? sessionStorage.setItem('gc', JSON.stringify(tok)) : sessionStorage.removeItem('gc'); } catch { /* ignore */ }
  };
  const post = (path, body) => fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

  let refreshing = null; // refresh tokens rotate, so two refreshes at once would revoke the session
  const refresh = () => refreshing ||= (async () => {
    try {
      const r = await post('/v1/auth/refresh', { refreshToken: tok.refresh });
      if (!r.ok) return false;
      save(await r.json());
      return true;
    } catch { return false; } finally { refreshing = null; }
  })();

  async function api(path, opts = {}, retry = true) {
    const headers = { Authorization: 'Bearer ' + (tok?.access || ''), ...(opts.body ? { 'Content-Type': 'application/json' } : {}) };
    const r = await fetch(path, { ...opts, headers, cache: 'no-store' });
    if (r.status === 401 && retry && tok?.refresh && await refresh()) return api(path, opts, false);
    return r;
  }
  // call returns the decoded JSON body, or throws the server's message so the UI can show it.
  async function call(path, body) {
    const r = await api(path, body === undefined ? {} : { method: 'POST', body: JSON.stringify(body) });
    const j = await r.json().catch(() => ({}));
    if (r.status === 403 && j.error === 'forbidden') { save(null); showLogin('This account is not an admin.'); }
    if (!r.ok) throw new Error(j.message || 'Request failed (' + r.status + ')');
    return j;
  }

  function showLogin(msg = '') {
    stop();
    $('#app').hidden = true;
    $('#login').hidden = false;
    $('#loginErr').textContent = msg;
    $('#email').focus();
  }

  $('#loginForm').addEventListener('submit', async e => {
    e.preventDefault();
    $('#loginErr').textContent = '';
    try {
      const r = await post('/v1/auth/login', { email: $('#email').value, password: $('#password').value });
      const j = await r.json().catch(() => ({}));
      if (!r.ok) { $('#loginErr').textContent = j.message || 'Sign-in failed.'; return; }
      $('#password').value = '';
      save(j);
      start();
    } catch { $('#loginErr').textContent = 'Cannot reach the server.'; }
  });

  $('#signOut').addEventListener('click', async () => {
    if (tok?.refresh) post('/v1/auth/logout', { refreshToken: tok.refresh }).catch(() => { });
    save(null);
    showLogin();
  });

  // ---- polling ----
  let hist = [], lastT = 0, data = null, timer = null, busy = false;

  function start() {
    $('#login').hidden = true;
    $('#app').hidden = false;
    hist = []; lastT = 0; data = null;
    dt.tables = [];
    route();
    tick();
    schedule();
  }
  function stop() { clearInterval(timer); timer = null; }
  function schedule() {
    stop();
    const every = +$('#interval').value;
    if (every) timer = setInterval(tick, every);
  }
  $('#interval').addEventListener('change', schedule);

  async function tick() {
    if (busy || document.hidden) return;
    busy = true;
    try {
      const r = await api('/v1/admin/metrics?since=' + lastT);
      if (r.status === 401) return showLogin('Session expired. Sign in again.');
      if (r.status === 403) { save(null); return showLogin('This account is not an admin.'); }
      if (!r.ok) throw new Error(r.status);
      data = await r.json();
      if (data.history.length) {
        hist.push(...data.history);
        if (hist.length > 720) hist = hist.slice(-720);
        lastT = hist[hist.length - 1].t;
      }
      render();
    } catch {
      setHealth('bad', 'Unreachable', 'The server did not answer the last request.');
    } finally { busy = false; }
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden && tok) tick(); });

  // ---- navigation ----
  const titles = { overview: 'Overview', database: 'Database', requests: 'Requests', content: 'Users & content', data: 'Data', sql: 'SQL console', logs: 'Logs' };
  let page = 'overview';
  function route() {
    const p = location.hash.slice(1);
    page = titles[p] ? p : 'overview';
    $$('section[data-page]').forEach(s => s.hidden = s.dataset.page !== page);
    $$('aside a').forEach(a => a.classList.toggle('on', a.dataset.page === page));
    $('#title').textContent = titles[page];
    if (page === 'data' && !dt.tables.length) loadTables();
    if (data) render();
  }
  addEventListener('hashchange', route);
  addEventListener('resize', () => data && drawCharts());
  $('#reqFilter').addEventListener('input', () => data && renderRequests());
  $('#logFilter').addEventListener('input', () => data && renderLogs());

  // ---- rendering ----
  const card = (k, v, s = '', tone = '') => `<div class="card"><div class="k">${esc(k)}</div><div class="v ${tone}">${esc(v)}</div><div class="s">${esc(s)}</div></div>`;
  const bar = (p, tone = '') => `<div class="bar ${p > 90 ? 'bad' : p > 75 ? 'warn' : tone}"><div style="width:${Math.min(100, Math.max(0, p))}%"></div></div>`;
  const kv = rows => rows.map(([k, v]) => `<span>${esc(k)}</span><span>${v}</span>`).join('');
  const table = (head, rows, empty = 'Nothing yet.') => rows.length
    ? `<table><thead><tr>${head.map(h => `<th class="${h[1] || ''}">${esc(h[0])}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table>`
    : `<div class="mute">${esc(empty)}</div>`;
  const set = (id, html) => { const el = $('#' + id); if (el.innerHTML !== html) el.innerHTML = html; };

  function setHealth(tone, text, why) {
    $('#healthDot').className = 'dot ' + tone;
    $('#healthText').textContent = text;
    $('#health').title = why || '';
  }

  function render() {
    const d = data, last = hist[hist.length - 1];
    const recent = hist.slice(-12); // about the last minute

    const problems = [];
    if (last && last.dbMs < 0) problems.push('Database ping failed');
    if (last && last.dbMs > 250) problems.push('Database ping is slow (' + ms(last.dbMs) + ')');
    const r12 = sum(recent, s => s.req), e12 = sum(recent, s => s.err);
    if (r12 >= 5 && e12 / r12 > 0.05) problems.push('More than 5% of requests failed in the last minute');
    const h = d.host;
    if (h.available && h.memUsed / h.memTotal > 0.9) problems.push('Host memory above 90%');
    if (h.diskTotal && h.diskFree / h.diskTotal < 0.1) problems.push('Less than 10% disk free');
    const au = d.data.audio;
    if (au.files !== au.expectedFiles) problems.push(`Audio folder has ${au.files} files but the database lists ${au.expectedFiles}`);
    setHealth(problems.length ? 'warn' : 'ok', problems.length ? 'Needs attention' : 'Healthy', problems.join('\n'));
    $('#meta').textContent = `up ${dur(d.uptime)} · ${clock(d.now)} · ${d.build.go} ${d.build.os}/${d.build.arch}`;

    renderOverview(last, recent, r12, e12);
    renderDatabase(last);
    renderRequests();
    renderContent();
    renderLogs();
    drawCharts();
  }

  function renderOverview(last, recent, r12, e12) {
    const d = data, h = d.host, p = d.process;
    const lat = r12 ? sum(recent, s => s.avgMs * s.req) / r12 : 0;
    const memPct = h.available ? 100 * h.memUsed / h.memTotal : 0;
    const diskPct = h.diskTotal ? 100 * (h.diskTotal - h.diskFree) / h.diskTotal : 0;
    const tone = (v, warn, bad) => v >= bad ? 'bad' : v >= warn ? 'warn' : '';
    set('cards', [
      card('Requests / min', n0(r12), `${n0(d.http.total)} since start`),
      card('Error rate', r12 ? pct(100 * e12 / r12) : '0%', `${n0(d.http.c4)} client errors total`, tone(r12 ? 100 * e12 / r12 : 0, 1, 5)),
      card('Avg latency', ms(lat), 'last minute'),
      card('DB ping', last ? ms(last.dbMs) : '–', 'SELECT 1 round trip', last && last.dbMs > 100 ? 'warn' : ''),
      card('CPU', h.available && last ? pct(last.cpu) : 'n/a', h.available ? `load ${h.load.map(x => x.toFixed(2)).join(' ')}` : 'host stats need Linux', last ? tone(last.cpu, 70, 90) : ''),
      card('Memory', h.available ? pct(memPct) : 'n/a', h.available ? `${bytes(h.memUsed)} of ${bytes(h.memTotal)}` : '', tone(memPct, 75, 90)),
      card('Disk', h.diskTotal ? pct(diskPct) : 'n/a', h.diskTotal ? `${bytes(h.diskFree)} free` : '', tone(diskPct, 75, 90)),
      card('Goroutines', n0(p.goroutines), `${bytes(p.heapAlloc)} heap`),
      card('Users', n0(d.data.users.total), `+${n0(d.data.users.new24h)} in 24h`),
      card('Clips', n0(d.data.clips.total), `${bytes(d.data.clips.bytes)} stored`),
    ].join(''));

    const row = (label, used, total, extra = '') => `<div style="margin-bottom:12px"><div style="display:flex;justify-content:space-between"><span>${esc(label)}</span><span class="mute">${esc(extra || `${bytes(used)} / ${bytes(total)}`)}</span></div>${bar(total ? 100 * used / total : 0)}</div>`;
    set('resources',
      `<div class="panel"><h3>Resources</h3>
      ${h.available ? row('Host memory', h.memUsed, h.memTotal) : ''}
      ${h.diskTotal ? row('Data disk', h.diskTotal - h.diskFree, h.diskTotal) : ''}
      ${row('Go heap in use', p.heapAlloc, p.heapSys, `${bytes(p.heapAlloc)} / ${bytes(p.heapSys)} reserved`)}
      ${!h.available && !h.diskTotal ? '<div class="mute">Host memory and disk are reported when the server runs on Linux.</div>' : ''}
    </div>
    <div class="panel"><h3>Process</h3><div class="kv">${kv([
        ['Uptime', dur(d.uptime)],
        ['Resident memory', p.rss ? bytes(p.rss) : 'n/a'],
        ['Open file descriptors', p.fds ? n0(p.fds) : 'n/a'],
        ['Goroutines', n0(p.goroutines)],
        ['Stack in use', bytes(p.stack)],
        ['GC runs', n0(p.gcCount)],
        ['Last GC pause', ms(p.gcPauseMs)],
        ['CPU cores', d.build.cpus],
        ['Rate-limiter keys', n0(d.limiter)],
      ])}</div></div>`);
  }

  function renderDatabase(last) {
    const { db, audio, pool } = data.data;
    const rows = sum(db.tables, t => t.rows);
    set('dbCards', [
      card('Database file', bytes(db.fileBytes), `${bytes(db.walBytes)} WAL`),
      card('Rows', n0(rows), `${db.tables.length} tables`),
      card('Free pages', n0(db.freePages), `${bytes(db.freePages * db.pageSize)} reclaimable of ${n0(db.pages)} pages`),
      card('DB ping', last ? ms(last.dbMs) : '–', 'SELECT 1 round trip'),
      card('Pool in use', `${pool.inUse} / ${pool.open}`, `${n0(pool.waitCount)} waits so far`),
    ].join(''));
    const total = sum(db.tables, t => t.bytes) || 1;
    set('tables', table([['Table'], ['Rows', 'r'], ['Size', 'r'], ['']],
      db.tables.map(t => `<tr><td>${esc(t.name)}</td><td class="r">${n0(t.rows)}</td><td class="r">${t.bytes ? bytes(t.bytes) : '–'}</td><td style="width:90px">${bar(100 * t.bytes / total)}</td></tr>`)));
    const mismatch = audio.files !== audio.expectedFiles || audio.bytes !== audio.expectedBytes;
    set('dbKv', kv([
      ['Journal mode', esc(db.journal)],
      ['Page size', bytes(db.pageSize)],
      ['Pages', n0(db.pages)],
      ['Main file', bytes(db.fileBytes)],
      ['WAL file', bytes(db.walBytes)],
      ['Shared-memory file', bytes(db.shmBytes)],
      ['Audio files on disk', `<span class="${mismatch ? 's4' : ''}">${n0(audio.files)} (${bytes(audio.bytes)})</span>`],
      ['Clips in database', `${n0(audio.expectedFiles)} (${bytes(audio.expectedBytes)})`],
      ['Pool open / in use / idle', `${pool.open} / ${pool.inUse} / ${pool.idle}`],
      ['Pool total wait', pool.waitMs + ' ms'],
    ]));
  }

  function renderRequests() {
    const h = data.http;
    set('reqCards', [
      card('Total', n0(h.total), 'since start'),
      card('2xx', n0(h.c2), '', 's2'), card('3xx', n0(h.c3), '', 's3'),
      card('4xx', n0(h.c4), 'client errors', h.c4 ? 's4' : ''), card('5xx', n0(h.c5), 'server errors', h.c5 ? 's5' : ''),
    ].join(''));
    const routes = [...h.routes].sort((a, b) => b.count - a.count);
    set('routes', table([['Route'], ['Hits', 'r'], ['4xx', 'r'], ['5xx', 'r'], ['Avg', 'r'], ['Max', 'r']],
      routes.map(r => `<tr><td>${esc(r.route)}</td><td class="r">${n0(r.count)}</td><td class="r ${r.e4 ? 's4' : ''}">${n0(r.e4)}</td><td class="r ${r.e5 ? 's5' : ''}">${n0(r.e5)}</td><td class="r">${ms(r.avgMs)}</td><td class="r">${ms(r.maxMs)}</td></tr>`)));
    const q = $('#reqFilter').value.trim().toLowerCase();
    const list = h.recent.slice().reverse().filter(r => !q || `${r.method} ${r.path} ${r.status} ${r.ip}`.toLowerCase().includes(q));
    set('recent', table([['Time'], ['Method'], ['Path'], ['Status', 'r'], ['Took', 'r'], ['IP']],
      list.map(r => `<tr><td>${clock(r.t)}</td><td>${esc(r.method)}</td><td>${esc(r.path)}</td><td class="r s${Math.floor(r.status / 100)}">${r.status}</td><td class="r">${ms(r.ms)}</td><td>${esc(r.ip)}</td></tr>`),
      'No requests match.'));
  }

  function renderContent() {
    const { users: u, clips: c } = data.data, m = data.mail;
    set('userCards', [
      card('Users', n0(u.total), `${n0(u.verified)} verified`),
      card('New users', n0(u.new24h), `${n0(u.new7d)} in 7 days`),
      card('Google / password', `${n0(u.google)} / ${n0(u.password)}`, 'accounts may have both'),
      card('Premium', n0(u.premium)),
      card('Active sessions', n0(u.sessions), 'unrevoked refresh tokens'),
      card('Clips', n0(c.total), `${n0(c.new24h)} in 24h`),
      card('Clip storage', bytes(c.bytes)),
      card('Likes / dislikes', `${n0(c.likes)} / ${n0(c.dislikes)}`),
    ].join(''));
    set('signups', table([['Name'], ['Email'], ['Joined'], ['']],
      u.recent.map(s => `<tr><td>${esc(s.name)}</td><td>${esc(s.email)}</td><td>${ago(s.at)}</td><td>${s.verified ? '<span class="tag">verified</span> ' : ''}${s.google ? '<span class="tag">google</span>' : ''}</td></tr>`)));
    set('clips', table([['File'], ['By'], ['Size', 'r'], ['👍', 'r'], ['👎', 'r'], ['Added']],
      c.recent.map(x => `<tr><td>${esc(x.file)}</td><td>${esc(x.by)}</td><td class="r">${bytes(x.bytes)}</td><td class="r">${n0(x.likes)}</td><td class="r">${n0(x.dislikes)}</td><td>${ago(x.at)}</td></tr>`)));
    set('top', table([['Uploader'], ['Clips', 'r'], ['Size', 'r']],
      c.top.map(x => `<tr><td>${esc(x.name)}</td><td class="r">${n0(x.clips)}</td><td class="r">${bytes(x.bytes)}</td></tr>`)));
    set('mailKv', kv([
      ['Provider', esc(m.provider)],
      ['Sent today', `${n0(m.today)} / ${n0(m.cap)}`],
      ['Pending verify/reset links', n0(u.pendingMail)],
    ]));
  }

  function renderLogs() {
    const q = $('#logFilter').value.trim().toLowerCase();
    const pre = $('#logs');
    const atEnd = pre.parentElement.scrollTop + pre.parentElement.clientHeight >= pre.parentElement.scrollHeight - 40 || !pre.innerHTML;
    const lines = data.logs.filter(l => !q || l.toLowerCase().includes(q));
    const html = lines.map(l => /fail|error|panic|fatal/i.test(l) ? `<span class="s5">${esc(l)}</span>` : esc(l)).join('\n') || 'No log lines yet.';
    if (pre.innerHTML !== html) pre.innerHTML = html;
    pre.scrollTop = atEnd ? pre.scrollHeight : pre.scrollTop;
  }

  // ---- charts ----
  const hover = {};
  const draw = {};

  function niceMax(v) {
    const p = Math.pow(10, Math.floor(Math.log10(v)));
    const f = v / p;
    return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * p;
  }

  function chart(id, series, fmt, fixedMax, minTop = 0) {
    draw[id] = () => paint(id, series, fmt, fixedMax, minTop);
    paint(id, series, fmt, fixedMax, minTop);
  }

  function paint(id, series, fmt, fixedMax, minTop) {
    const c = $('#' + id);
    const w = c.clientWidth, h = c.clientHeight;
    if (!w) return; // section is hidden
    const dpr = devicePixelRatio || 1;
    if (c.width !== Math.round(w * dpr)) { c.width = Math.round(w * dpr); c.height = Math.round(h * dpr); }
    const g = c.getContext('2d');
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    const css = getComputedStyle(document.documentElement);
    const col = n => css.getPropertyValue(n).trim();
    g.font = '11px system-ui, sans-serif';
    const L = 46, R = 8, T = 8, B = 20, n = hist.length;
    if (n < 2) { g.fillStyle = col('--mute'); g.fillText('Collecting samples…', L, h / 2); return; }
    const top = fixedMax || Math.max(minTop, niceMax(Math.max(1e-9, ...series.flatMap(s => s.data)) * 1.1));
    const X = i => L + i * (w - L - R) / (n - 1);
    const Y = v => T + (h - T - B) * (1 - Math.min(v, top) / top);

    g.strokeStyle = col('--line'); g.fillStyle = col('--mute'); g.lineWidth = 1; g.textAlign = 'right';
    for (let i = 0; i <= 4; i++) {
      const y = Math.round(Y(top * i / 4)) + .5;
      g.beginPath(); g.moveTo(L, y); g.lineTo(w - R, y); g.stroke();
      g.fillText(fmt(top * i / 4), L - 6, y + 4);
    }
    g.textAlign = 'left'; g.fillText(clock(hist[0].t), L, h - 5);
    g.textAlign = 'right'; g.fillText(clock(hist[n - 1].t), w - R, h - 5);

    series.forEach((s, k) => {
      const color = col(s.color);
      g.beginPath();
      s.data.forEach((v, i) => i ? g.lineTo(X(i), Y(v)) : g.moveTo(X(i), Y(v)));
      g.strokeStyle = color; g.lineWidth = 1.8; g.lineJoin = 'round'; g.stroke();
      if (k === 0) {
        g.lineTo(X(n - 1), Y(0)); g.lineTo(X(0), Y(0)); g.closePath();
        g.globalAlpha = .12; g.fillStyle = color; g.fill(); g.globalAlpha = 1;
      }
    });

    const i = hover[id];
    if (i != null && i < n) {
      const x = X(i);
      g.strokeStyle = col('--mute'); g.beginPath(); g.moveTo(x, T); g.lineTo(x, h - B); g.stroke();
      const lines = [clock(hist[i].t), ...series.map(s => `${s.label}: ${fmt(s.data[i])}`)];
      const bw = Math.max(...lines.map(l => g.measureText(l).width)) + 14, bh = lines.length * 15 + 8;
      const bx = x + bw + 10 > w ? x - bw - 8 : x + 8;
      g.fillStyle = col('--panel2'); g.strokeStyle = col('--line');
      g.fillRect(bx, T + 2, bw, bh); g.strokeRect(bx + .5, T + 2.5, bw - 1, bh - 1);
      g.fillStyle = col('--text'); g.textAlign = 'left';
      lines.forEach((l, j) => g.fillText(l, bx + 7, T + 17 + j * 15));
      series.forEach(s => { g.beginPath(); g.arc(x, Y(s.data[i]), 3, 0, 7); g.fillStyle = col(s.color); g.fill(); });
    }
  }

  for (const id of ['chReq', 'chLat', 'chHost', 'chGo']) {
    const c = $('#' + id);
    c.addEventListener('mousemove', e => {
      const r = c.getBoundingClientRect(), n = hist.length;
      if (n < 2) return;
      hover[id] = Math.max(0, Math.min(n - 1, Math.round((e.clientX - r.left - 46) / (r.width - 54) * (n - 1))));
      draw[id]?.();
    });
    c.addEventListener('mouseleave', () => { hover[id] = null; draw[id]?.(); });
  }

  function drawCharts() {
    if (page !== 'overview') return;
    const col = f => hist.map(f);
    chart('chReq', [{ data: col(s => s.req), color: '--c1', label: 'requests' }, { data: col(s => s.err), color: '--c4', label: '5xx' }], v => n0(Math.round(v * 10) / 10), 0, 4);
    chart('chLat', [{ data: col(s => s.avgMs), color: '--c3', label: 'avg request' }, { data: col(s => Math.max(0, s.dbMs)), color: '--c2', label: 'DB ping' }], v => ms(v));
    chart('chHost', [{ data: col(s => s.cpu), color: '--c1', label: 'CPU' }, { data: col(s => s.mem), color: '--c2', label: 'memory' }], v => Math.round(v) + '%', 100);
    chart('chGo', [{ data: col(s => s.heap / 1048576), color: '--c1', label: 'heap' }, { data: col(s => s.gor), color: '--c3', label: 'goroutines' }], v => (v >= 10 ? Math.round(v) : v.toFixed(1)));
  }

  // ---- data browser: every table, with edit / add / delete ----
  const dt = { tables: [], name: '', meta: null, rows: [], total: 0, offset: 0, limit: 50, sort: '', dir: 'desc' };
  const TIME_COL = /(^at|_at)$/;
  const cellHtml = (col, v, table) => {
    if (v === null) return '<span class="mute">NULL</span>';
    if (TIME_COL.test(col.name) && typeof v === 'number' && v > 0) return `<span title="${esc(v)}">${esc(new Date(v).toLocaleString())}</span>`;
    if (table === 'clips' && col.name === 'storage_key') return `<a href="/files/${encodeURIComponent(v)}" target="_blank" rel="noopener">▶ ${esc(v)}</a>`;
    const s = String(v);
    return `<span class="cell" title="${esc(s)}">${esc(s.length > 80 ? s.slice(0, 80) + '…' : s)}</span>`;
  };
  const rowKey = (meta, row) => Object.fromEntries(meta.columns.map((c, i) => [c, i]).filter(([c]) => c.pk > 0).map(([c, i]) => [c.name, row[i]]));
  const dtError = msg => { $('#dtErr').textContent = msg || ''; };

  async function loadTables() {
    try {
      dt.tables = (await call('/v1/admin/tables')).tables;
      const sel = $('#dtTable');
      sel.innerHTML = dt.tables.map(t => `<option value="${esc(t.name)}">${esc(t.name)} (${n0(t.rows)})</option>`).join('');
      if (!dt.tables.some(t => t.name === dt.name)) dt.name = dt.tables.find(t => t.name === 'users')?.name || dt.tables[0]?.name || '';
      sel.value = dt.name;
      await loadRows();
    } catch (e) { dtError(e.message); }
  }

  async function loadRows() {
    if (!dt.name) return;
    const p = new URLSearchParams({ limit: dt.limit, offset: dt.offset, q: $('#dtSearch').value.trim() });
    if (dt.sort) { p.set('sort', dt.sort); p.set('dir', dt.dir); }
    try {
      const j = await call(`/v1/admin/tables/${encodeURIComponent(dt.name)}?${p}`);
      Object.assign(dt, { meta: j.table, rows: j.rows, total: j.total });
      dtError('');
      renderGrid();
    } catch (e) { dtError(e.message); }
  }

  function renderGrid() {
    const { meta, rows } = dt;
    const t = dt.tables.find(x => x.name === dt.name);
    $('#dtAdd').hidden = !meta.canInsert;
    $('#dtInfo').textContent = rows.length ? `${dt.offset + 1}–${dt.offset + rows.length} of ${n0(dt.total)}${meta.readOnly ? ' · read-only' : ''}` : (meta.readOnly ? 'read-only' : '');
    $('#dtPrev').disabled = dt.offset === 0;
    $('#dtNext').disabled = dt.offset + rows.length >= dt.total;
    const arrow = c => dt.sort === c.name ? (dt.dir === 'asc' ? ' ▲' : ' ▼') : '';
    const head = `<th></th>${meta.columns.map(c => `<th class="sortable" data-sort="${esc(c.name)}" title="${esc(c.type)}${c.pk ? ' · primary key' : ''}">${esc(c.name)}${arrow(c)}</th>`).join('')}`;
    const body = rows.map((r, i) => {
      const acts = meta.readOnly ? '' : [
        `<button class="ghost sm" data-act="edit" data-i="${i}">Edit</button>`,
        meta.name === 'users' ? `<button class="ghost sm" data-act="password" data-i="${i}">Password</button><button class="ghost sm" data-act="revoke" data-i="${i}">Sign out</button>` : '',
        `<button class="danger sm" data-act="delete" data-i="${i}">Delete</button>`,
      ].join('');
      return `<tr><td class="act"><div>${acts}</div></td>${meta.columns.map((c, j) => `<td>${cellHtml(c, r[j], meta.name)}</td>`).join('')}</tr>`;
    }).join('');
    $('#dtGrid').innerHTML = rows.length ? `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table>` : `<div class="mute">${dt.total || (t && t.rows) ? 'No rows match.' : 'This table is empty.'}</div>`;
  }

  $('#dtTable').addEventListener('change', e => { dt.name = e.target.value; dt.offset = 0; dt.sort = ''; loadRows(); });
  $('#dtSearch').addEventListener('input', () => { clearTimeout(dt.timer); dt.timer = setTimeout(() => { dt.offset = 0; loadRows(); }, 250); });
  $('#dtRefresh').addEventListener('click', loadTables);
  $('#dtPrev').addEventListener('click', () => { dt.offset = Math.max(0, dt.offset - dt.limit); loadRows(); });
  $('#dtNext').addEventListener('click', () => { dt.offset += dt.limit; loadRows(); });
  $('#dtAdd').addEventListener('click', () => openForm('insert'));
  $('#dtGrid').addEventListener('click', e => {
    const th = e.target.closest('th[data-sort]');
    if (th) {
      dt.dir = dt.sort === th.dataset.sort && dt.dir === 'desc' ? 'asc' : 'desc';
      dt.sort = th.dataset.sort; dt.offset = 0;
      return loadRows();
    }
    const b = e.target.closest('button[data-act]');
    if (b) rowAction(b.dataset.act, dt.rows[+b.dataset.i]);
  });

  async function changed(fn) {
    try { await fn(); dtError(''); await loadTables(); } catch (e) { dtError(e.message); }
  }

  function rowAction(act, row) {
    const { meta } = dt, key = rowKey(meta, row), label = `${meta.name} ${Object.values(key).join(' / ')}`;
    const base = `/v1/admin/tables/${encodeURIComponent(meta.name)}`;
    if (act === 'edit') return openForm('update', row);
    if (act === 'delete') {
      const extra = meta.name === 'users' ? '\n\nTheir clips, reactions, sessions and audio files are deleted too.' : meta.name === 'clips' ? '\n\nThe audio file is deleted too.' : '';
      if (confirm(`Delete ${label}? This cannot be undone.${extra}`)) changed(() => call(base + '/delete', { key }));
    } else if (act === 'revoke') {
      if (confirm(`Sign ${row[meta.columns.findIndex(c => c.name === 'email')]} out of every device?`)) changed(() => call(`/v1/admin/users/${encodeURIComponent(key.id)}/revoke`, {}));
    } else if (act === 'password') {
      const pw = prompt('New password for this user (10-128 characters). Their sessions are signed out.');
      if (pw) changed(() => call(`/v1/admin/users/${encodeURIComponent(key.id)}/password`, { password: pw }));
    }
  }

  // The edit / add dialog: one field per column; locked columns are shown but cannot change.
  function openForm(mode, row) {
    const { meta } = dt, dlg = $('#dlg');
    $('#dlgTitle').textContent = (mode === 'insert' ? 'Add row to ' : 'Edit row in ') + meta.name;
    $('#dlgErr').textContent = '';
    $('#dlgFields').innerHTML = meta.columns.map((c, i) => {
      const v = row ? row[i] : null;
      const locked = mode === 'insert' ? (c.type === 'BLOB' || (c.locked && !c.pk)) : c.locked;
      if (mode === 'insert' && locked) return '';
      const hint = TIME_COL.test(c.name) && typeof v === 'number' ? ` · ${new Date(v).toLocaleString()}` : (c.name === 'id' && mode === 'insert' ? ' · leave empty to generate' : '');
      const nullBox = !c.notNull && !locked ? `<label class="line"><input type="checkbox" data-null="${i}" ${v === null ? 'checked' : ''}> NULL</label>` : '';
      return `<label><span>${esc(c.name)} · ${esc(c.type.toLowerCase())}${c.pk ? ' · key' : ''}${c.notNull ? '' : ' · optional'}${esc(hint)}</span>
        <span class="line"><input data-i="${i}" value="${esc(v ?? '')}" ${locked ? 'disabled' : ''} autocomplete="off">${nullBox}</span></label>`;
    }).join('');
    dlg.dataset.mode = mode;
    dlg._row = row;
    dlg.showModal();
  }

  $('#dlgCancel').addEventListener('click', () => $('#dlg').close());
  $('#dlgForm').addEventListener('submit', async e => {
    e.preventDefault();
    const { meta } = dt, dlg = $('#dlg'), mode = dlg.dataset.mode, row = dlg._row;
    const values = {};
    $$('#dlgFields input[data-i]').forEach(inp => {
      if (inp.disabled) return;
      const i = +inp.dataset.i, nul = $(`input[data-null="${i}"]`)?.checked;
      const old = row ? row[i] : null;
      if (mode === 'update') {
        if (nul ? old !== null : (old === null || String(old) !== inp.value)) values[meta.columns[i].name] = nul ? null : inp.value;
      } else if (nul || inp.value !== '') values[meta.columns[i].name] = nul ? null : inp.value;
    });
    if (mode === 'update' && !Object.keys(values).length) { dlg.close(); return; }
    try {
      const base = `/v1/admin/tables/${encodeURIComponent(meta.name)}`;
      await call(base + '/' + mode, mode === 'insert' ? { values } : { key: rowKey(meta, row), values });
      dlg.close();
      await loadTables();
    } catch (err) { $('#dlgErr').textContent = err.message; }
  });

  // ---- SQL console ----
  async function runSQL(write) {
    const sql = $('#sqlText').value.trim();
    if (!sql) return;
    if (write && !confirm('Run this statement and keep its changes on the live database?')) return;
    $('#sqlMsg').textContent = 'Running…';
    try {
      const j = await call('/v1/admin/sql', { sql, write });
      const note = j.changes ? `${n0(j.changes)} row change${j.changes === 1 ? '' : 's'} ${j.committed ? 'saved' : 'rolled back (nothing was kept)'}` : `${n0(j.rows.length)} row${j.rows.length === 1 ? '' : 's'}${j.truncated ? ' (first 500 shown)' : ''}`;
      $('#sqlMsg').textContent = note;
      $('#sqlOut').innerHTML = j.columns.length
        ? table(j.columns.map(c => [c]), j.rows.map(r => `<tr>${r.map(v => `<td>${v === null ? '<span class="mute">NULL</span>' : `<span class="cell" title="${esc(v)}">${esc(String(v).slice(0, 200))}</span>`}</td>`).join('')}</tr>`), 'No rows.')
        : `<span class="mute">${esc(note)}</span>`;
      if (j.committed) dt.tables = [];
    } catch (e) {
      $('#sqlMsg').textContent = '';
      $('#sqlOut').innerHTML = `<span class="s5">${esc(e.message)}</span>`;
    }
  }
  $('#sqlDry').addEventListener('click', () => runSQL(false));
  $('#sqlRun').addEventListener('click', () => runSQL(true));
  $('#sqlText').addEventListener('keydown', e => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') runSQL(false); });

  // ---- orphaned audio files ----
  async function scanOrphans(remove) {
    try {
      const j = await call('/v1/admin/orphans', remove ? {} : undefined);
      const list = (title, a) => a.length ? `<div><b>${esc(title)} (${a.length})</b><br>${a.slice(0, 20).map(esc).join('<br>')}${a.length > 20 ? '<br>…' : ''}</div>` : '';
      $('#orphOut').innerHTML = remove ? `Removed ${j.orphanFiles.length} file${j.orphanFiles.length === 1 ? '' : 's'}.`
        : (list('Files with no clip in the database', j.orphanFiles) + list('Clips whose file is missing', j.missingFiles)) || 'All audio files match the clips table.';
      $('#orphClean').hidden = remove || !j.orphanFiles.length;
    } catch (e) { $('#orphOut').textContent = e.message; }
  }
  $('#orphScan').addEventListener('click', () => scanOrphans(false));
  $('#orphClean').addEventListener('click', () => { if (confirm('Delete every audio file that no clip points at?')) scanOrphans(true); });

  tok ? start() : showLogin();
})();
