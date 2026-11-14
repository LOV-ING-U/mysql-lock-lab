#!/bin/bash
# mysqld-debug를 띄우고, 준비되면 Spring을 실행한다.
# Spring이 GDB를 자식 프로세스로 띄우므로 이 스크립트는 GDB를 다루지 않는다.
set -euo pipefail

DATA=/var/lib/mysql
SOCKET=/run/mysqld/mysql.sock
PASSWORD=${MYSQL_ROOT_PASSWORD:-local-lock-lab}
MYSQLD=(
  /usr/sbin/mysqld-debug
  --user=mysql
  --basedir=/usr
  --datadir=$DATA
  --socket=$SOCKET
  --pid-file=/run/mysqld/mysqld.pid
  --bind-address=127.0.0.1
  --mysqlx=OFF
  --performance-schema=ON
  --innodb-buffer-pool-size=128M
)

wait_for_mysql() {
  for _ in $(seq 120); do
    if MYSQL_PWD="$1" mysql -uroot --socket=$SOCKET -e 'SELECT 1' >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  echo "mysqld가 준비되지 않았습니다" >&2
  return 1
}

mkdir -p /run/mysqld
chown -R mysql:mysql $DATA /run/mysqld

# 최초 1회: 데이터 디렉터리 초기화 + root 비밀번호 설정 (네트워크 없이)
if [ ! -f $DATA/.locklab-initialized ]; then
  if [ ! -d $DATA/mysql ]; then
    /usr/sbin/mysqld-debug --initialize-insecure --user=mysql --basedir=/usr --datadir=$DATA
  fi
  "${MYSQLD[@]}" --skip-networking &
  wait_for_mysql ""
  MYSQL_PWD="" mysql -uroot --socket=$SOCKET -e "
    ALTER USER 'root'@'localhost' IDENTIFIED BY '$PASSWORD';
    CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '$PASSWORD';
    GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;"
  MYSQL_PWD="$PASSWORD" mysqladmin -uroot --socket=$SOCKET shutdown
  wait
  touch $DATA/.locklab-initialized
fi

"${MYSQLD[@]}" &
MYSQLD_PID=$!
wait_for_mysql "$PASSWORD"

java -XX:MaxRAMPercentage=50.0 -jar /app/app.jar &
JAVA_PID=$!

# 종료 순서: Spring(→ GDB detach) 먼저, 그다음 mysqld
stop() {
  kill -TERM $JAVA_PID 2>/dev/null || true
  wait $JAVA_PID 2>/dev/null || true
  kill -TERM $MYSQLD_PID 2>/dev/null || true
  wait $MYSQLD_PID 2>/dev/null || true
  exit 0
}
trap stop TERM INT

# Spring이 끝날 때까지 기다린다.
# (wait -n은 쓰지 않는다: 1번 프로세스는 남의 자식 프로세스도 거두므로 엉뚱하게 반환될 수 있다)
set +e
wait $JAVA_PID
echo "Spring 종료 (exit $?) → mysqld도 종료합니다" >&2
stop
