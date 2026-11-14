"""GDB 없이도 테스트할 수 있는 순수 함수 모음 (MySQL 8.4.6 상수 기준)."""

# lock_t.type_mode 하위 4비트 = 모드
MODE_NAMES = ['IS', 'IX', 'S', 'X', 'AUTO_INC']

LOCK_TABLE = 16
LOCK_REC = 32
LOCK_WAIT = 256

# 레코드 락 범위 플래그
RANGE_FLAGS = [
    (512, 'GAP'),
    (1024, 'REC_NOT_GAP'),
    (2048, 'INSERT_INTENTION'),
    (8192, 'PREDICATE'),
    (16384, 'PRDT_PAGE'),
]
RANGE_MASK = 512 | 1024 | 2048 | 8192 | 16384


def mode_name(type_mode):
    """type_mode 정수를 'X,REC_NOT_GAP,WAIT' 같은 문자열로 바꾼다."""
    mode = type_mode & 15
    parts = [MODE_NAMES[mode] if mode < len(MODE_NAMES) else str(mode)]
    for flag, label in RANGE_FLAGS:
        if type_mode & flag:
            parts.append(label)
    # 레코드 락인데 범위 플래그가 하나도 없으면 next-key 락
    if type_mode & LOCK_REC and not type_mode & RANGE_MASK:
        parts.append('NEXT_KEY')
    if type_mode & LOCK_WAIT:
        parts.append('WAIT')
    return ','.join(parts)


def trim_bitmap(data, n_bits):
    """비트맵 바이트를 0/1 목록으로 바꾸고, 마지막으로 켜진 비트까지만 남긴다."""
    bits = [
        1 if data[i // 8] & (1 << (i % 8)) else 0
        for i in range(min(n_bits, len(data) * 8))
    ]
    while bits and bits[-1] == 0:
        bits.pop()
    return bits


def parse_sessions(text):
    """'12345:A,12346:B' 형식을 {12345: 'A', 12346: 'B'}로 바꾼다."""
    sessions = {}
    for item in text.split(','):
        if not item.strip():
            continue
        lwp, label = item.split(':', 1)
        sessions[int(lwp)] = label.strip()
    return sessions


def short_table_name(full_name, database):
    """InnoDB 내부 이름 'lab/t'에서 DB 접두어를 뗀다. 다른 DB면 None."""
    prefix = database + '/'
    if not full_name.startswith(prefix):
        return None
    return full_name[len(prefix):]


def count_by_mode(counts):
    """dict_table_t.count_by_mode 배열에서 0이 아닌 것만 이름으로 남긴다."""
    return {
        MODE_NAMES[i]: count
        for i, count in enumerate(counts)
        if i < len(MODE_NAMES) and count
    }
