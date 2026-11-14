"""mysqld-debug에 붙은 GDB 안에서 동작하는 InnoDB 락 구조체 트레이서.

- 8개 락 함수에 브레이크포인트를 건다.
- 실험 세션(A/B) 스레드에서 걸렸을 때만 함수 진입/반환 시점의 구조체 스냅샷을 찍는다.
- 결과는 stdout에 'LOCKLAB {json}' 한 줄씩 쓴다. Spring이 이 파이프를 읽는다.
- mysqld 메모리는 읽기만 하고, mysqld 함수는 호출하지 않는다.

환경변수
- LOCKLAB_SESSIONS: '12345:A,12346:B' (세션 스레드 LWP -> 라벨)
- LOCKLAB_DB: 실험 DB 이름 (이 DB의 테이블에 대한 요청만 추적)
"""
import json
import os

import gdb

from protocol import (
    LOCK_REC,
    LOCK_TABLE,
    count_by_mode,
    mode_name,
    parse_sessions,
    short_table_name,
    trim_bitmap,
)

PREFIX = 'LOCKLAB '
SESSIONS = parse_sessions(os.environ.get('LOCKLAB_SESSIONS', ''))
DATABASE = os.environ.get('LOCKLAB_DB', 'lab')
MAX_CELLS = 4_000_000
MAX_CHAIN = 10_000

trx_labels = {}  # trx_t 주소 -> 'A' / 'B'
lock_ids = {}  # lock_t 주소 -> 정수 id (해제되면 지워서, 주소가 재사용되면 새 id)
next_lock_id = 1
types = {}
last_stop = None


# ---------------------------------------------------------------- 출력 / 메모리 읽기

def emit(data):
    """stdout(fd 1)에 한 줄을 쓴다. gdb.execute의 출력 캡처를 거치지 않는다."""
    # 앞에도 줄바꿈: GDB가 같은 stdout에 쓴 메시지가 줄바꿈 없이 남아 있어도 우리 줄은 새 줄에서 시작
    line = '\n' + PREFIX + json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n'
    view = memoryview(line.encode('utf-8'))
    while view:
        written = os.write(1, view)
        view = view[written:]


def pointer_type(*names):
    for name in names:
        try:
            return gdb.lookup_type(name).pointer()
        except gdb.error:
            pass
    raise ValueError('디버그 심볼에 타입이 없습니다: ' + '/'.join(names))


def read_word(value):
    """8바이트짜리 값(unique_ptr, atomic<T*>, atomic<size_t>)을 정수로 읽는다."""
    if value.type.sizeof != 8:
        raise ValueError('8바이트 값이 아닙니다: ' + str(value.type))
    data = gdb.selected_inferior().read_memory(value.address, 8)
    return int.from_bytes(bytes(data), 'little')


def text(char_pointer):
    return char_pointer.string(encoding='utf-8', errors='replace')


def as_lock(address):
    return gdb.Value(address).cast(types['lock'])


def walk(first, next_of):
    """널 포인터가 나올 때까지 연결 리스트를 따라간다 (길이 상한 있음)."""
    node = first
    for _ in range(MAX_CHAIN):
        if not int(node):
            return
        yield node
        node = next_of(node)


def lock_id(address):
    global next_lock_id
    if address not in lock_ids:
        lock_ids[address] = next_lock_id
        next_lock_id += 1
    return lock_ids[address]


def current_session():
    return SESSIONS.get(gdb.selected_thread().ptid[1])


# ---------------------------------------------------------------- 스냅샷

def describe_lock(lock):
    type_mode = int(lock['type_mode'])
    row = {
        'id': lock_id(int(lock)),
        'trx': trx_labels.get(int(lock['trx']), 'other'),
        'type_mode': type_mode,
        'mode': mode_name(type_mode),
    }
    if type_mode & LOCK_TABLE:
        row['table'] = table_label(lock['tab_lock']['table'])
        return row
    record = lock['rec_lock']
    page = record['page_id']
    n_bits = int(record['n_bits'])
    # 비트맵은 lock_t 구조체 바로 뒤에 붙어 있다
    bitmap = gdb.selected_inferior().read_memory(
        int(lock) + lock.type.target().sizeof,
        n_bits // 8,
    )
    row['table'] = table_label(lock['index']['table'])
    row['index'] = text(lock['index']['name']['m_name'])
    row['page_id'] = [int(page['m_space']), int(page['m_page_no'])]
    row['bitmap'] = trim_bitmap(bytes(bitmap), n_bits)
    return row


def table_label(table):
    name = text(table['name']['m_name'])
    return short_table_name(name, DATABASE) or name


def describe_trx(address, label, locks):
    state = gdb.Value(address).cast(types['trx'])['lock']
    members = list(
        walk(
            state['trx_locks']['first_element'],
            lambda lock: lock['trx_locks']['next'],
        )
    )
    for lock in members:
        locks[int(lock)] = lock
    wait = read_word(state['wait_lock'])
    blocking = read_word(state['blocking_trx'])
    return {
        'id': label,
        'trx_locks': [lock_id(int(lock)) for lock in members],
        'wait_lock': lock_id(wait) if wait else None,
        'blocking_trx': trx_labels.get(blocking, 'other') if blocking else None,
    }


def describe_tables(locks):
    tables = {}
    for lock in locks.values():
        if int(lock['type_mode']) & LOCK_TABLE:
            table = lock['tab_lock']['table']
            tables[int(table)] = table
    rows = []
    for table in tables.values():
        members = walk(
            table['locks']['first_element'],
            lambda lock: lock['tab_lock']['locks']['next'],
        )
        rows.append(
            {
                'name': table_label(table),
                'locks': [
                    lock_id(int(lock)) if int(lock) in locks else 'other'
                    for lock in members
                ],
                'count_by_mode': count_by_mode(
                    [int(table['count_by_mode'][i]) for i in range(5)]
                ),
            }
        )
    return rows


def describe_rec_hash(locks):
    """비어 있지 않은 셀 중 추적 중인 레코드 락이 들어 있는 셀만 보여준다."""
    tracked = {
        address
        for address, lock in locks.items()
        if int(lock['type_mode']) & LOCK_REC
    }
    if not tracked:
        return []
    lock_sys = gdb.parse_and_eval('lock_sys')
    table = gdb.Value(read_word(lock_sys['rec_hash']['ht'])).cast(types['hash_table'])
    n_cells = read_word(table['n_cells'])
    cells = read_word(table['cells'])
    if not cells or n_cells > MAX_CELLS:
        return []
    # 셀 배열(hash_cell_t = void* 하나)을 한 번에 읽는다
    raw = bytes(gdb.selected_inferior().read_memory(cells, n_cells * 8))
    rows = []
    for index, head in enumerate(memoryview(raw).cast('Q')):
        if not head:
            continue
        chain = [int(lock) for lock in walk(as_lock(head), lambda lock: lock['hash'])]
        if tracked.intersection(chain):
            rows.append(
                {
                    'cell': index,
                    'chain': [
                        lock_id(address) if address in tracked else 'other'
                        for address in chain
                    ],
                }
            )
    return rows


def snapshot():
    locks = {}  # 추적 중인 trx가 가진 lock_t: 주소 -> Value
    trx_rows = [
        describe_trx(address, label, locks)
        for address, label in sorted(trx_labels.items(), key=lambda item: item[1])
    ]
    lock_rows = sorted(
        (describe_lock(lock) for lock in locks.values()),
        key=lambda row: row['id'],
    )
    state = {
        'lock_t': lock_rows,
        'trx_t': trx_rows,
        'rec_hash': describe_rec_hash(locks),
        'tables': describe_tables(locks),
    }
    # 더 이상 어느 trx 목록에도 없는 lock_t는 id를 지운다
    for address in list(lock_ids):
        if address not in locks:
            del lock_ids[address]
    return state


# ---------------------------------------------------------------- 진입 / 반환 기록

def record(function, frame, session, args, target=None, result=False):
    event = {'session': session, 'fn': function, 'phase': 'enter'}
    if args:
        event['args'] = args
    if target is not None:
        event['lock'] = lock_id(int(target))
    head = dict(event)
    event.update(snapshot())
    emit(event)
    # 인라인된 호출은 반환 지점을 잡을 수 없으므로 진입 스냅샷만 남긴다
    if frame.type() != gdb.INLINE_FRAME:
        Returned(frame, head, result)


class Returned(gdb.Breakpoint):
    """호출자의 복귀 주소에 거는 일회용 브레이크포인트.

    같은 함수가 재귀/다른 스레드로 다시 불릴 수 있으므로, 스택 포인터가
    호출 직전 값과 같을 때만 이 호출의 반환으로 인정한다.
    """

    def __init__(self, frame, head, with_result):
        caller = frame.older()
        while caller is not None and caller.type() in (
            gdb.INLINE_FRAME,
            gdb.TAILCALL_FRAME,
        ):
            caller = caller.older()
        if caller is None:
            raise ValueError('호출자 프레임이 없습니다')
        self.return_sp = int(caller.read_register('rsp'))
        super().__init__('*0x%x' % caller.pc(), internal=True)
        self.thread = gdb.selected_thread().global_num
        self.head = head
        self.with_result = with_result

    def stop(self):
        return int(gdb.newest_frame().read_register('rsp')) == self.return_sp

    def handle(self):
        try:
            event = dict(self.head, phase='return')
            if self.with_result:
                value = gdb.parse_and_eval('$eax').cast(gdb.lookup_type('dberr_t'))
                event['result'] = str(value)
            event.update(snapshot())
            emit(event)
        finally:
            self.delete()


# ---------------------------------------------------------------- 프로브

class Probe(gdb.Breakpoint):
    """함수 진입 브레이크포인트. 거를 조건은 condition(GDB 조건식)으로 붙인다."""

    def __init__(self, function, handler, variable=None):
        super().__init__(function, internal=True)
        if self.pending:
            raise ValueError('심볼이 없습니다: ' + function)
        self.function = function
        self.handler = handler
        self.variable = variable  # 조건식에 쓸 인자 이름 (해제·승인 프로브)

    def handle(self):
        self.handler(self.function, gdb.newest_frame())


change_probes = []


def label_trx(address, session):
    """trx를 A/B로 등록하고, 해제·승인 프로브의 조건식을 갱신한다."""
    if trx_labels.get(address) == session:
        return
    trx_labels[address] = session
    for probe in change_probes:
        if probe.variable == 'trx':
            terms = ['trx == (trx_t *) %#x' % a for a in trx_labels]
        else:
            terms = ['%s->trx == (trx_t *) %#x' % (probe.variable, a) for a in trx_labels]
        probe.condition = ' || '.join(terms)


def on_lock_table(function, frame):
    session = current_session()
    table = short_table_name(text(frame.read_var('table')['name']['m_name']), DATABASE)
    if session is None or table is None:
        return
    # thr_get_trx(thr) == thr->graph->trx
    label_trx(int(frame.read_var('thr')['graph']['trx']), session)
    args = {'table': table, 'mode': mode_name(int(frame.read_var('mode')))}
    record(function, frame, session, args, result=True)


def on_lock_rec_lock(function, frame):
    session = current_session()
    index = frame.read_var('index')
    table = short_table_name(text(index['table']['name']['m_name']), DATABASE)
    if session is None or table is None:
        return
    label_trx(int(frame.read_var('thr')['graph']['trx']), session)
    page = frame.read_var('block')['page']['id']
    args = {
        'table': table,
        'index': text(index['name']['m_name']),
        'mode': mode_name(int(frame.read_var('mode')) | LOCK_REC),
        'page_id': [int(page['m_space']), int(page['m_page_no'])],
        'heap_no': int(frame.read_var('heap_no')),
    }
    record(function, frame, session, args, result=True)


def on_insert_check(function, frame):
    session = current_session()
    index = frame.read_var('index')
    table = short_table_name(text(index['table']['name']['m_name']), DATABASE)
    if session is None or table is None:
        return
    label_trx(int(frame.read_var('thr')['graph']['trx']), session)
    args = {'table': table, 'index': text(index['name']['m_name'])}
    record(function, frame, session, args, result=True)


def on_lock_change(variable):
    """lock_t 하나를 다루는 함수(승인/해제). 추적 중인 trx의 락일 때만 기록."""

    def handler(function, frame):
        lock = frame.read_var(variable)
        if int(lock['trx']) not in trx_labels:
            return
        args = None
        if function == 'lock_rec_release':
            args = {'heap_no': int(frame.read_var('heap_no'))}
        record(function, frame, current_session() or '-', args, target=lock)

    return handler


def on_trx_release(function, frame):
    label = trx_labels.get(int(frame.read_var('trx')))
    if label is None:
        return
    record(function, frame, current_session() or '-', {'trx': label})


# 세션 스레드에서만 보면 되는 요청 함수
REQUEST_PROBES = [
    ('lock_table', on_lock_table),
    ('lock_rec_lock', on_lock_rec_lock),
    ('lock_rec_insert_check_and_lock', on_insert_check),
]

# 다른 스레드(데드락 검출 등)에서도 불릴 수 있는 해제·승인 함수: (함수, 인자 이름)
CHANGE_PROBES = [
    ('lock_grant', 'lock'),
    ('lock_rec_discard', 'in_lock'),
    ('lock_table_remove_low', 'lock'),
    ('lock_rec_release', 'lock'),
    ('lock_trx_release_locks', 'trx'),
]


def install_probes():
    # 세션 LWP -> GDB 스레드 번호
    threads = {
        thread.ptid[1]: thread.global_num
        for thread in gdb.selected_inferior().threads()
    }
    for lwp in SESSIONS:
        if lwp not in threads:
            raise ValueError('세션 스레드가 mysqld에 없습니다: LWP %d' % lwp)
    # 함수마다 브레이크포인트는 하나. A·B 스레드가 아니면 GDB 조건식에서 바로 통과
    only_sessions = ' || '.join('$_gthread == %d' % threads[lwp] for lwp in SESSIONS)
    for function, handler in REQUEST_PROBES:
        probe = Probe(function, handler)
        probe.condition = only_sessions
    for function, variable in CHANGE_PROBES:
        handler = on_trx_release if variable == 'trx' else on_lock_change(variable)
        probe = Probe(function, handler, variable=variable)
        probe.condition = '0'  # 추적 중인 trx가 생기기 전에는 멈추지 않음
        change_probes.append(probe)


# ---------------------------------------------------------------- 메인 루프

def remember_stop(event):
    global last_stop
    last_stop = event


def safe(action, function):
    try:
        action()
    except Exception as error:
        emit(
            {
                'kind': 'diagnostic',
                'message': '%s: %s: %s' % (function, type(error).__name__, error),
            }
        )


def loop():
    global last_stop
    while gdb.selected_inferior().pid:
        last_stop = None
        gdb.execute('continue', to_string=True)
        event = last_stop
        if isinstance(event, gdb.BreakpointEvent):
            # 같은 브레이크포인트가 두 번 넘어와도 한 번만 처리
            for point in dict.fromkeys(event.breakpoints):
                if isinstance(point, (Probe, Returned)) and point.is_valid():
                    name = point.function if isinstance(point, Probe) else point.head['fn']
                    safe(point.handle, name)
        elif isinstance(event, gdb.SignalEvent) and event.stop_signal == 'SIGINT':
            return  # Spring이 종료를 요청함
        elif gdb.selected_inferior().pid:
            emit({'kind': 'diagnostic', 'message': '예상하지 못한 정지: ' + str(event)})
    emit({'kind': 'fatal', 'message': 'mysqld가 종료되었습니다.'})


def main():
    try:
        types['lock'] = pointer_type('lock_t', 'ib_lock_t')
        types['trx'] = pointer_type('trx_t')
        types['hash_table'] = pointer_type('hash_table_t')
        gdb.events.stop.connect(remember_stop)
        install_probes()
        emit({'kind': 'ready', 'sessions': sorted(SESSIONS.values())})
        loop()
    except Exception as error:
        emit({'kind': 'fatal', 'message': str(error)})
    finally:
        try:
            gdb.execute('detach')
        except gdb.error:
            pass
