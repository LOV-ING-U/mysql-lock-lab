package dev.locklab

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLException
import java.util.Locale

data class ProblemDto(
    val id: Int,
    val q: String,
    val tableSql: String,
    val testSql: String,
    val choices: Map<String, String>,
)

data class GradeResultDto(
    val result: String,
    val answer: List<String>,
    val explanation: String,
    @JsonProperty("user_answer") val userAnswer: List<String>,
)

data class AskInput(
    val question: String? = null,
    @JsonProperty("user_answer") val userAnswer: List<String>? = null,
)

data class ReferenceDto(
    @JsonProperty("chunk_id") val chunkId: String,
    @JsonProperty("v_mysql") val mysqlVersion: Double,
    @JsonProperty("source_url") val sourceUrl: String,
    val text: String,
)

data class AskResultDto(
    val reply: String,
    val sources: List<ReferenceDto>,
    @get:JsonIgnore val timingsMs: Map<String, Double> = emptyMap(),
)

@RestController
class LearningController(
    private val problems: ProblemService,
    private val rag: RagService,
) {
    @GetMapping("/problems")
    fun problems() = problems.all().map { it.view() }

    @PostMapping("/grade/{id}")
    fun grade(
        @PathVariable id: Int,
        @RequestBody answers: List<String>,
    ) = problems.find(id).grade(answers)

    @PostMapping("/ask/{id}")
    fun ask(
        @PathVariable id: Int,
        @RequestBody input: AskInput,
        response: HttpServletResponse,
    ): AskResultDto {
        val result = rag.ask(problems.find(id), input)
        response.setHeader(
            "Server-Timing",
            result.timingsMs.entries.joinToString(", ") {
                "${it.key};dur=${String.format(Locale.ROOT, "%.3f", it.value)}"
            },
        )
        return result
    }
}

// 학습 API(문제·채점·추가 질문) 전용 오류 처리. 실험실 API에는 적용하지 않는다.
class Failure(
    val status: Int,
    message: String,
) : RuntimeException(message)

data class ApiError(
    val message: String?,
)

@RestControllerAdvice(assignableTypes = [LearningController::class])
class ApiErrors {
    @ExceptionHandler(Exception::class)
    fun handle(error: Exception): ResponseEntity<ApiError> {
        val status =
            when (error) {
                is Failure -> error.status
                is HttpMessageNotReadableException, is SQLException -> 400
                else -> 500
            }
        val message = if (error is HttpMessageNotReadableException) "올바른 JSON 요청을 보내세요." else error.message
        return ResponseEntity.status(status).body(ApiError(message))
    }
}
