package dev.locklab

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 실험실: admin 커넥션(테이블 준비·리셋) + 세션 A, B 커넥션.
 * 세션 커넥션은 닫지 않는다. 닫으면 mysqld 스레드(LWP)가 바뀌어 GDB 필터가 어긋난다.
 */
@Service
class LabService(
    private val tracer: Tracer,
    @Value("\${lab.database.host}") private val host: String,
    @Value("\${lab.database.port}") private val port: Int,
    @Value("\${lab.database.user}") private val user: String,
    @Value("\${lab.database.password}") private val password: String,
) {
    class Session(
        val name: String,
        val connection: Connection,
        val connectionId: Long,
        val lwp: Long,
    ) {
        val executor = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }

        // idle | running | waiting | done | error
        @Volatile
        var status = "idle"

        @Volatile
        var rows: List<Map<String, String?>> = emptyList()

        @Volatile
        var error: String? = null

        // 커밋하지 않은 트랜잭션이 있을 수 있음
        @Volatile
        var open = false

        // 이번 실행이 시작될 때의 이벤트 개수
        @Volatile
        var runFrom = 0

        fun busy() = status == "running" || status == "waiting"
    }

    private lateinit var admin: Connection
    private val sessions = linkedMapOf<String, Session>()

    // 웹 서버가 뜬 뒤 커넥션을 연다. GDB는 사용자가 "추적 시작"을 누를 때 붙인다.
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        connect("").use { it.createStatement().execute("CREATE DATABASE IF NOT EXISTS $DATABASE") }
        admin = connect(DATABASE)
        for (name in listOf("A", "B")) {
            sessions[name] = openSession(name)
        }
    }

    // on=true: GDB를 세션 A·B 스레드에 맞춰 붙인다 / on=false: GDB를 뗀다
    fun trace(on: Boolean): Map<String, Any?> {
        if (on) {
            tracer.start(sessions.values.associate { it.lwp to it.name }, DATABASE)
        } else {
            tracer.stop()
        }
        return timeline(tracer.size())
    }

    // 테이블 준비는 GDB가 붙기 전에만 한다 (DDL이 내부 테이블에 거는 락까지 멈추면 매우 느림)
    fun tables(sql: String): Map<String, Any?> {
        checkSql(sql)
        if (tracer.status != "stopped" && tracer.status != "failed") {
            fail(HttpStatus.CONFLICT, "추적을 끈 상태에서 테이블을 준비하세요.")
        }
        if (sessions.values.any { it.busy() || it.open }) {
            fail(HttpStatus.CONFLICT, "세션 A, B를 먼저 커밋하세요. 락이 남아 있으면 DDL이 막힙니다.")
        }
        val result = synchronized(admin) { execute(admin, sql) }
        return mapOf("result" to result, "tables" to tableNames())
    }

    // 커밋하지 않고 끝까지 실행한다. 락 대기에 걸리면 waiting으로 먼저 응답한다.
    fun run(
        name: String,
        sql: String,
    ): Map<String, Any?> {
        checkSql(sql)
        if (tracer.status != "ready") {
            fail(HttpStatus.SERVICE_UNAVAILABLE, "먼저 추적 시작을 누르세요. (GDB 상태: ${tracer.status})")
        }
        val session = session(name)
        val from = tracer.size()
        synchronized(session) {
            if (session.busy()) {
                fail(HttpStatus.CONFLICT, "세션 $name 이(가) 실행 중이거나 락을 기다리는 중입니다.")
            }
            session.status = "running"
            session.rows = emptyList()
            session.error = null
            session.open = true
            session.runFrom = from
        }
        session.executor.execute {
            try {
                val rows = execute(session.connection, sql)
                synchronized(session) {
                    session.rows = rows
                    session.status = "done"
                }
            } catch (error: Exception) {
                synchronized(session) {
                    session.error = error.message
                    session.status = "error"
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            updateWaiting(session)
            if (session.status != "running") {
                break
            }
            Thread.sleep(50)
        }
        return result(session, from)
    }

    fun commit(name: String): Map<String, Any?> {
        val session = session(name)
        if (session.busy()) {
            fail(HttpStatus.CONFLICT, "세션 $name 이(가) 실행 중이거나 락을 기다리는 중입니다.")
        }
        val from = tracer.size()
        session.executor.submit { session.connection.commit() }.get(30, TimeUnit.SECONDS)
        synchronized(session) {
            session.status = "idle"
            session.rows = emptyList()
            session.error = null
            session.open = false
        }
        return result(session, from)
    }

    fun timeline(after: Int): Map<String, Any?> =
        mapOf(
            "tracer" to
                mapOf(
                    "status" to tracer.status,
                    "message" to tracer.message,
                    "diagnostics" to tracer.diagnostics.toList(),
                ),
            "tables" to if (::admin.isInitialized) tableNames() else emptyList(),
            "sessions" to
                sessions.mapValues { (_, session) ->
                    updateWaiting(session)
                    view(session)
                },
            "events" to tracer.since(after),
        )

    // GDB 떼기 → 두 세션 롤백 → 실험 DB 삭제·재생성 → 이벤트 비우기. 커넥션은 유지한다.
    fun reset(): Map<String, Any?> {
        tracer.stop()
        for (session in sessions.values) {
            if (session.busy()) {
                synchronized(admin) {
                    admin.createStatement().use { it.execute("KILL QUERY ${session.connectionId}") }
                }
            }
            // 단일 스레드 실행기라서, 중단된 실행이 끝난 뒤에 롤백이 실행된다
            session.executor.submit { session.connection.rollback() }.get(30, TimeUnit.SECONDS)
            synchronized(session) {
                session.status = "idle"
                session.rows = emptyList()
                session.error = null
                session.open = false
            }
        }
        synchronized(admin) {
            admin.createStatement().use {
                it.execute("DROP DATABASE IF EXISTS $DATABASE")
                it.execute("CREATE DATABASE $DATABASE")
                it.execute("USE $DATABASE")
            }
        }
        for (session in sessions.values) {
            session.executor
                .submit { session.connection.createStatement().use { it.execute("USE $DATABASE") } }
                .get(30, TimeUnit.SECONDS)
        }
        tracer.clear()
        return timeline(0)
    }

    private fun openSession(name: String): Session {
        val connection = connect(DATABASE)
        connection.autoCommit = false
        connection.createStatement().use {
            it.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            it.execute("SET SESSION innodb_lock_wait_timeout = 300")
            it.execute("SET SESSION lock_wait_timeout = 300")
        }
        val (connectionId, lwp) =
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT CONNECTION_ID(), THREAD_OS_ID
                        FROM performance_schema.threads
                        WHERE PROCESSLIST_ID = CONNECTION_ID()
                        """.trimIndent(),
                    ).use { result ->
                        result.next()
                        result.getLong(1) to result.getLong(2)
                    }
            }
        connection.commit()
        return Session(name, connection, connectionId, lwp)
    }

    // 실행 중인데 이 세션의 DB_LOCK_WAIT 이벤트가 보이면 대기 상태로 바꾼다
    private fun updateWaiting(session: Session) {
        synchronized(session) {
            if (session.status == "running" && tracer.lockWaitSince(session.name, session.runFrom)) {
                session.status = "waiting"
            }
        }
    }

    private fun result(
        session: Session,
        from: Int,
    ): Map<String, Any?> = view(session) + ("session" to session.name) + ("events" to tracer.since(from))

    private fun view(session: Session): Map<String, Any?> =
        mapOf(
            "status" to session.status,
            "open" to session.open,
            "rows" to session.rows,
            "error" to session.error,
        )

    private fun session(name: String): Session = sessions[name] ?: fail(HttpStatus.NOT_FOUND, "세션은 A 또는 B입니다.")

    private fun tableNames(): List<String> =
        synchronized(admin) {
            admin.createStatement().use { statement ->
                statement.executeQuery("SHOW TABLES").use { result ->
                    buildList {
                        while (result.next()) {
                            add(result.getString(1))
                        }
                    }
                }
            }
        }

    // 여러 문장 실행 가능. SELECT 결과는 최대 100행, 그 외에는 affected_rows를 남긴다.
    private fun execute(
        connection: Connection,
        sql: String,
    ): List<Map<String, String?>> =
        connection.createStatement().use { statement ->
            val rows = mutableListOf<Map<String, String?>>()
            var hasRows = statement.execute(sql)
            while (true) {
                if (hasRows) {
                    statement.resultSet.use { result ->
                        val meta = result.metaData
                        while (result.next() && rows.size < MAX_ROWS) {
                            rows.add((1..meta.columnCount).associate { meta.getColumnLabel(it) to result.getString(it) })
                        }
                    }
                } else {
                    val count = statement.updateCount
                    if (count < 0) {
                        break
                    }
                    rows.add(mapOf("affected_rows" to count.toString()))
                }
                hasRows = statement.moreResults
            }
            rows
        }

    private fun connect(database: String): Connection =
        DriverManager.getConnection(
            "jdbc:mysql://$host:$port/$database?allowMultiQueries=true&sslMode=DISABLED&allowPublicKeyRetrieval=true",
            user,
            password,
        )

    private fun checkSql(sql: String) {
        if (sql.isBlank()) {
            fail(HttpStatus.BAD_REQUEST, "SQL을 입력하세요.")
        }
    }

    private fun fail(
        status: HttpStatus,
        message: String,
    ): Nothing = throw ResponseStatusException(status, message)

    companion object {
        const val DATABASE = "lab"
        private const val MAX_ROWS = 100
    }
}
