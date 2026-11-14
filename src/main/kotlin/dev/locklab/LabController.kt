package dev.locklab

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class LabController(
    private val lab: LabService,
) {
    data class SqlRequest(
        val sql: String = "",
    )

    // 사전 DB/데이터 준비 (admin 커넥션)
    @PostMapping("/tables")
    fun tables(
        @RequestBody request: SqlRequest,
    ) = lab.tables(request.sql)

    data class TraceRequest(
        val on: Boolean = false,
    )

    // GDB 추적 켜기/끄기
    @PostMapping("/trace")
    fun trace(
        @RequestBody request: TraceRequest,
    ) = lab.trace(request.on)

    // 세션 A/B에서 실행 (커밋하지 않음)
    @PostMapping("/sessions/{name}/run")
    fun run(
        @PathVariable name: String,
        @RequestBody request: SqlRequest,
    ) = lab.run(name, request.sql)

    @PostMapping("/sessions/{name}/commit")
    fun commit(
        @PathVariable name: String,
    ) = lab.commit(name)

    // seq가 after보다 큰 이벤트 + 세션 상태 (브라우저가 polling)
    @GetMapping("/timeline")
    fun timeline(
        @RequestParam(defaultValue = "0") after: Int,
    ) = lab.timeline(after)

    @PostMapping("/reset")
    fun reset() = lab.reset()
}
