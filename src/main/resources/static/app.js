'use strict';
const $ = id => document.getElementById(id);

async function api(path, body, timeout = 60000) {
  const response = await fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type': 'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(timeout),
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.message || `요청 실패 (${response.status})`);
  }
  return data;
}

// ---------------------------------------------------------------- 화면 전환

function show(screen) {
  for (const name of ['lab', 'problems']) {
    $('screen-' + name).hidden = name !== screen;
  }
  for (const button of document.querySelectorAll('[data-screen]')) {
    button.toggleAttribute('aria-current', button.dataset.screen === screen);
  }
  if (screen === 'problems' && !problems.length) {
    loadProblems();
  }
}

for (const button of document.querySelectorAll('[data-screen]')) {
  button.addEventListener('click', () => show(button.dataset.screen));
}

// ---------------------------------------------------------------- 실험실 상태

const events = []; // 받은 이벤트 (seq 순)
let lastSeq = 0;
let selectedSeq = null; // null이면 마지막 이벤트를 보여준다
const lockNames = new Map(); // GDB가 준 lock id -> 'L1', 'L2' ... (처음 등장한 순서)
let sessions = {};
let tracer = {};
let tables = [];
const pending = new Set(); // 응답을 기다리는 세션
let pollTimer;

const STATUS_TEXT = {
  idle: '트랜잭션 없음',
  running: '실행 중',
  waiting: '락 대기 중',
  done: '실행 완료 (커밋 전)',
  error: '오류 (커밋 필요)',
};

function lockName(id) {
  if (id === null || id === undefined) return '-';
  if (id === 'other') return 'other';
  if (!lockNames.has(id)) lockNames.set(id, 'L' + (lockNames.size + 1));
  return lockNames.get(id);
}

function addEvents(list) {
  for (const event of list || []) {
    if (event.seq <= lastSeq) continue;
    lastSeq = event.seq;
    for (const row of event.lock_t || []) lockName(row.id);
    if (event.lock !== undefined) lockName(event.lock);
    events.push(event);
  }
}

function applyTimeline(data) {
  tracer = data.tracer;
  tables = data.tables;
  sessions = data.sessions;
  addEvents(data.events);
}

function isBusy(session) {
  return session && (session.status === 'running' || session.status === 'waiting');
}

// 실행 중·대기 중인 세션이 있거나 GDB가 붙는 중이면 0.5초마다 새 이벤트를 가져온다
function schedulePoll() {
  clearTimeout(pollTimer);
  const needed = Object.values(sessions).some(isBusy) || tracer.status === 'starting';
  if (needed) pollTimer = setTimeout(poll, 500);
}

async function poll() {
  try {
    applyTimeline(await api('/api/timeline?after=' + lastSeq));
    render();
    schedulePoll();
  } catch (error) {
    $('tracer-status').textContent = '서버 연결 실패: ' + error.message;
    pollTimer = setTimeout(poll, 2000);
  }
}

// ---------------------------------------------------------------- 실험실 동작

$('trace-toggle').addEventListener('click', async () => {
  const on = !(tracer.status === 'ready' || tracer.status === 'starting');
  try {
    applyTimeline(await api('/api/trace', {on}));
  } catch (error) {
    $('tracer-status').textContent = error.message;
  }
  render();
  schedulePoll();
});

$('setup-run').addEventListener('click', async () => {
  $('setup-message').className = '';
  $('setup-message').textContent = '실행 중';
  try {
    const data = await api('/api/tables', {sql: $('setup-sql').value}, 300000);
    tables = data.tables;
    $('setup-message').textContent = '완료';
  } catch (error) {
    $('setup-message').className = 'error';
    $('setup-message').textContent = error.message;
  }
  render();
});

$('reset').addEventListener('click', async () => {
  $('setup-message').className = '';
  $('setup-message').textContent = '리셋 중';
  try {
    const data = await api('/api/reset', {});
    events.length = 0;
    lastSeq = 0;
    selectedSeq = null;
    lockNames.clear();
    applyTimeline(data);
    $('setup-message').textContent = '테이블과 타임라인을 지웠습니다.';
  } catch (error) {
    $('setup-message').className = 'error';
    $('setup-message').textContent = error.message;
  }
  render();
  schedulePoll();
});

for (const box of document.querySelectorAll('.console')) {
  const name = box.dataset.session;
  box.querySelector('[data-run]').addEventListener('click', () =>
    send(name, `/api/sessions/${name}/run`, {sql: box.querySelector('textarea').value}));
  box.querySelector('[data-commit]').addEventListener('click', () =>
    send(name, `/api/sessions/${name}/commit`, {}));
}

async function send(name, path, body) {
  pending.add(name);
  render();
  try {
    const data = await api(path, body);
    sessions[name] = {status: data.status, open: data.open, rows: data.rows, error: data.error};
    addEvents(data.events);
    selectedSeq = null;
  } catch (error) {
    sessions[name] = {...sessions[name], error: error.message};
  }
  pending.delete(name);
  render();
  schedulePoll();
}

// 지금까지 받은 타임라인을 JSON 파일로 저장한다 (lock id -> 화면 라벨 표 포함)
$('export').addEventListener('click', () => {
  const data = {
    exported_at: new Date().toISOString(),
    lock_names: Object.fromEntries(lockNames),
    sessions,
    events,
  };
  const blob = new Blob([JSON.stringify(data, null, 2)], {type: 'application/json'});
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = `locklab-timeline-${data.exported_at.replace(/[:.]/g, '-')}.json`;
  link.click();
  URL.revokeObjectURL(link.href);
});

// ---------------------------------------------------------------- 렌더링

function render() {
  const tracing = tracer.status === 'ready' || tracer.status === 'starting';
  $('tracer-status').textContent =
    tracer.status === 'ready' ? 'GDB 연결됨 (추적 중)'
      : tracer.status === 'starting' ? 'GDB를 mysqld에 연결하는 중 (수십 초 걸릴 수 있습니다)'
        : tracer.status === 'failed' ? 'GDB 오류: ' + (tracer.message || '')
          : '추적 꺼짐';
  $('trace-toggle').textContent = tracing ? '추적 중지' : '추적 시작';
  $('trace-toggle').disabled = tracer.status === 'starting';
  $('setup-run').disabled = tracing;
  $('tracer-diagnostics').textContent = (tracer.diagnostics || []).join('\n');
  $('tables').textContent = tables.join(', ') || '없음';

  for (const box of document.querySelectorAll('.console')) {
    const name = box.dataset.session;
    const session = sessions[name] || {status: 'idle'};
    const busy = isBusy(session) || pending.has(name);
    box.querySelector('[data-status]').textContent = STATUS_TEXT[session.status] || session.status;
    box.querySelector('[data-status]').dataset.value = session.status;
    box.querySelector('[data-run]').disabled = busy || tracer.status !== 'ready';
    box.querySelector('[data-commit]').disabled = busy;
    box.querySelector('[data-error]').textContent = session.error || '';
    const rows = session.rows || [];
    box.querySelector('[data-rows]').hidden = !rows.length;
    box.querySelector('[data-rows]').textContent = JSON.stringify(rows, null, 1);
  }

  renderEvents();
  renderSnapshot();
}

function describeArgs(event) {
  const args = event.args || {};
  const parts = [];
  if (args.table) parts.push(args.index ? `${args.table}.${args.index}` : args.table);
  if (args.mode) parts.push(args.mode);
  if (args.heap_no !== undefined) parts.push('heap_no ' + args.heap_no);
  if (args.trx) parts.push('trx ' + args.trx);
  if (event.lock !== undefined) parts.push('대상 ' + lockName(event.lock));
  return parts.join(' · ');
}

function cells(row, values) {
  for (const value of values) {
    row.insertCell().textContent = value;
  }
}

function renderEvents() {
  const body = document.createDocumentFragment();
  const current = selectedSeq ?? events.at(-1)?.seq;
  for (const event of events) {
    const row = document.createElement('tr');
    cells(row, [event.seq, event.session, event.fn, event.phase, describeArgs(event), event.result || '']);
    if (event.seq === current) row.className = 'selected';
    if (event.result === 'DB_LOCK_WAIT') row.classList.add('wait');
    row.addEventListener('click', () => {
      selectedSeq = event.seq;
      renderEvents();
      renderSnapshot();
    });
    body.append(row);
  }
  $('events').replaceChildren(body);
  $('empty-events').hidden = events.length > 0;
  $('export').disabled = events.length === 0;
}

function renderSnapshot() {
  const current = selectedSeq ?? events.at(-1)?.seq;
  const event = events.find(item => item.seq === current);
  $('snapshot').hidden = !event;
  if (!event) return;
  $('snapshot-title').textContent = `#${event.seq} ${event.session} · ${event.fn} ${event.phase} 시점`;

  fill('snap-locks', event.lock_t, lock => [
    lockName(lock.id),
    lock.trx,
    lock.page_id ? `${lock.table}.${lock.index} page(${lock.page_id.join(', ')})` : `table ${lock.table}`,
    `${lock.type_mode} (${lock.mode})`,
    lock.bitmap ? `[${lock.bitmap.join(',')}]` : '-',
  ]);
  fill('snap-trx', event.trx_t, trx => [
    trx.id,
    trx.trx_locks.map(lockName).join(' ⇄ ') || '(비어 있음)',
    lockName(trx.wait_lock),
    trx.blocking_trx || '-',
  ]);
  fill('snap-hash', event.rec_hash, cell => [
    cell.cell,
    cell.chain.map(lockName).join(' → '),
  ]);
  fill('snap-tables', event.tables, table => [
    table.name,
    table.locks.map(lockName).join(' ⇄ ') || '(비어 있음)',
    Object.entries(table.count_by_mode).map(([mode, count]) => `${mode}=${count}`).join(', ') || '-',
  ]);
}

function fill(id, items, toValues) {
  const body = document.createDocumentFragment();
  for (const item of items || []) {
    const row = document.createElement('tr');
    cells(row, toValues(item));
    body.append(row);
  }
  if (!(items || []).length) {
    const row = document.createElement('tr');
    const cell = row.insertCell();
    cell.colSpan = 5;
    cell.textContent = '(없음)';
    body.append(row);
  }
  $(id).replaceChildren(body);
}

// ---------------------------------------------------------------- 문제 풀기 (기존 학습 기능)

let problems = [];
let currentProblemId;
const grades = new Map();
const conversations = new Map();

async function loadProblems() {
  $('learning-message').textContent = '문제 불러오는 중';
  try {
    problems = await api('/problems');
    currentProblemId = problems[0]?.id;
    $('learning-message').textContent = problems.length ? '' : '등록된 문제가 없습니다.';
    renderLearning();
  } catch (error) {
    $('learning-message').textContent = error.message;
  }
}

function renderLearning() {
  const problem = problems.find(item => item.id === currentProblemId);
  const navigation = document.createDocumentFragment();
  for (const item of problems) {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = item.id + '번';
    button.toggleAttribute('aria-current', item.id === currentProblemId);
    button.addEventListener('click', () => {
      currentProblemId = item.id;
      renderLearning();
    });
    navigation.append(button);
  }
  $('problem-navigation').replaceChildren(navigation);
  if (!problem) return;

  $('problem-question').textContent = problem.id + '번. ' + problem.q;
  $('problem-table-sql').textContent = problem.tableSql;
  $('problem-test-sql').textContent = problem.testSql;
  const choices = document.createDocumentFragment();
  for (const [key, text] of Object.entries(problem.choices)) {
    const label = document.createElement('label');
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.value = key;
    label.append(input, document.createTextNode(` ${key}. ${text}`));
    choices.append(label);
  }
  $('problem-choices').replaceChildren(choices);
  $('grade-submit').disabled = false;

  const grade = grades.get(problem.id);
  $('grade-view').hidden = !grade;
  if (grade) {
    $('grade-result').textContent = grade.result;
    $('grade-answer').textContent = grade.answer.join(', ');
    $('grade-user-answer').textContent = grade.user_answer.join(', ');
    $('grade-explanation').textContent = grade.explanation;
  }
  renderChat();
}

function renderChat() {
  const fragment = document.createDocumentFragment();
  for (const item of conversations.get(currentProblemId) || []) {
    const article = document.createElement('article');
    const question = document.createElement('p');
    question.textContent = '질문: ' + item.question;
    const reply = document.createElement('p');
    reply.textContent = item.reply;
    article.append(question, reply);
    for (const source of item.sources || []) {
      const details = document.createElement('details');
      const summary = document.createElement('summary');
      summary.textContent = source.chunk_id;
      const text = document.createElement('p');
      text.textContent = source.text;
      details.append(summary, text);
      article.append(details);
    }
    fragment.append(article);
  }
  $('chat').replaceChildren(fragment);
}

$('grade-form').addEventListener('submit', async event => {
  event.preventDefault();
  const id = currentProblemId;
  const selected = [...document.querySelectorAll('#problem-choices input:checked')].map(input => input.value);
  if (!selected.length) {
    $('learning-message').textContent = '선택지를 한 개 이상 고르세요.';
    return;
  }
  try {
    grades.set(id, await api('/grade/' + id, selected));
    $('learning-message').textContent = '';
    renderLearning();
  } catch (error) {
    $('learning-message').textContent = error.message;
  }
});

$('ask-form').addEventListener('submit', async event => {
  event.preventDefault();
  const id = currentProblemId;
  const grade = grades.get(id);
  const question = $('ask-question').value.trim();
  if (!grade || !question) return;
  $('ask-submit').disabled = true;
  $('ask-status').textContent = '문서 검색·답변 생성 중 (최대 약 2분)';
  try {
    const response = await api('/ask/' + id, {question, user_answer: grade.user_answer}, 125000);
    const chat = conversations.get(id) || [];
    chat.push({question, ...response});
    conversations.set(id, chat);
    $('ask-question').value = '';
    $('ask-status').textContent = '';
    renderChat();
  } catch (error) {
    $('ask-status').textContent = error.message;
  }
  $('ask-submit').disabled = false;
});

// ---------------------------------------------------------------- 시작

show('lab');
poll();
