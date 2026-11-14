package dev.locklab

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * GDB를 자식 프로세스로 띄우고 stdout의 'LOCKLAB {json}' 줄을 이벤트 목록에 쌓는다.
 * 이벤트 내용은 GDB가 만든 JSON을 그대로 쓴다.
 */
@Component
class Tracer(
    private val json: ObjectMapper,
    @Value("\${lab.gdb-script}") private val script: String,
    @Value("\${lab.mysqld-pid-file}") private val pidFile: String,
) {
    // stopped -> starting -> ready, 실패하면 failed
    @Volatile
    var status = "stopped"

    // stop()으로 끈 것인지 (GDB가 스스로 죽은 것과 구분)
    @Volatile
    private var stopping = false

    @Volatile
    var message: String? = null

    // 프로브 처리 중 난 오류 (최근 10개)
    val diagnostics = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val events = mutableListOf<JsonNode>()

    private var gdb: Process? = null

    // sessions: mysqld 스레드 LWP -> 세션 이름
    fun start(
        sessions: Map<Long, String>,
        database: String,
    ) {
        if (status == "starting" || status == "ready") {
            return
        }
        stopping = false
        message = null
        diagnostics.clear()
        val pid = Files.readString(Path.of(pidFile)).trim()
        val builder =
            ProcessBuilder(
                "gdb",
                "--nx",
                "--quiet",
                "--batch",
                "-p",
                pid,
                "-x",
                script,
            )
        builder.environment()["LOCKLAB_SESSIONS"] =
            sessions.entries.joinToString(",") { "${it.key}:${it.value}" }
        builder.environment()["LOCKLAB_DB"] = database
        builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        status = "starting"
        val process = builder.start()
        gdb = process
        Thread.ofPlatform().daemon().start { read(process) }
    }

    // stdout을 계속 읽어야 한다. 멈추면 파이프가 차서 GDB와 mysqld가 같이 멈춘다.
    private fun read(process: Process) {
        process.inputStream.bufferedReader().forEachLine { line ->
            // GDB 자체 출력이 앞에 붙어도 찾을 수 있게 줄 중간까지 본다
            val start = line.indexOf("LOCKLAB ")
            if (start < 0) {
                if (line.isNotBlank()) {
                    System.err.println("[gdb] $line")
                }
                return@forEachLine
            }
            val node =
                try {
                    json.readTree(line.substring(start + "LOCKLAB ".length)) as ObjectNode
                } catch (error: Exception) {
                    diagnose("이벤트 JSON을 읽지 못했습니다: ${error.message}")
                    return@forEachLine
                }
            when (node.path("kind").asText()) {
                "ready" -> status = "ready"
                "fatal" -> fail(node.path("message").asText())
                "diagnostic" -> diagnose(node.path("message").asText())
                else -> add(node)
            }
        }
        gdb = null
        if (stopping) {
            status = "stopped"
        } else if (status != "failed") {
            fail("GDB가 종료되었습니다.")
        }
    }

    private fun diagnose(text: String) {
        System.err.println("[gdb diagnostic] $text")
        diagnostics.addLast(text)
        while (diagnostics.size > 10) {
            diagnostics.pollFirst()
        }
    }

    private fun fail(reason: String) {
        status = "failed"
        message = reason
    }

    @Synchronized
    private fun add(event: ObjectNode) {
        event.put("seq", events.size + 1)
        events.add(event)
    }

    @Synchronized
    fun size(): Int = events.size

    // seq가 after보다 큰 이벤트
    @Synchronized
    fun since(after: Int): List<JsonNode> = events.drop(after).toList()

    @Synchronized
    fun clear() = events.clear()

    // from 이후, 이 세션의 락 요청이 DB_LOCK_WAIT으로 반환됐는가
    @Synchronized
    fun lockWaitSince(
        session: String,
        from: Int,
    ): Boolean =
        events.drop(from).any {
            it.path("session").asText() == session &&
                it.path("result").asText() == "DB_LOCK_WAIT"
        }

    // SIGINT를 보내면 GDB가 detach하고 끝난다. 강제 종료하면 mysqld가 멈춘 채 남을 수 있다.
    @PreDestroy
    fun stop() {
        val process = gdb ?: return
        stopping = true
        ProcessBuilder("kill", "-INT", process.pid().toString()).start().waitFor()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        status = "stopped"
    }
}
