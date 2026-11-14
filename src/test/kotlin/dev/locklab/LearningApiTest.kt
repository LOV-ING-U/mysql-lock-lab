package dev.locklab

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class LearningApiTest {
    private val json = jacksonObjectMapper()
    private val problem =
        Problem(
            1,
            "문제",
            "CREATE TABLE t(id INT PRIMARY KEY)",
            "SELECT * FROM t FOR UPDATE",
            mapOf("A" to "IX", "B" to "X", "C" to "S"),
            listOf("A", "B"),
            "저장된 해설",
        )
    private val problems =
        mock(ProblemService::class.java, withSettings().mockMaker("mock-maker-subclass")).apply {
            `when`(all()).thenReturn(listOf(problem))
            `when`(find(1)).thenReturn(problem)
            `when`(find(999)).thenThrow(Failure(404, "문제를 찾을 수 없습니다."))
        }
    private val rag = mock(RagService::class.java, withSettings().mockMaker("mock-maker-subclass"))
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(LearningController(problems, rag))
            .setControllerAdvice(ApiErrors())
            .setMessageConverters(MappingJackson2HttpMessageConverter(json))
            .build()

    @Test
    fun `problem GET exposes choices but hides canonical answers`() {
        val response = mvc.perform(get("/problems")).andReturn().response
        assertEquals(200, response.status)
        val item = json.readTree(response.contentAsByteArray)[0]
        assertEquals("문제", item.path("q").asText())
        assertFalse(item.has("answer"))
        assertFalse(item.has("explanation"))
        verifyNoInteractions(rag)
    }

    @Test
    fun `grade accepts a bare array and returns precisely the requested fields without AI`() {
        val response =
            mvc
                .perform(post("/grade/1").contentType(MediaType.APPLICATION_JSON).content("""["A","C"]"""))
                .andReturn()
                .response
        assertEquals(200, response.status)
        val result = json.readTree(response.contentAsByteArray)
        assertEquals(setOf("result", "answer", "explanation", "user_answer"), result.fieldNames().asSequence().toSet())
        assertEquals("틀렸습니다", result.path("result").asText())
        assertEquals(listOf("A", "C"), result.path("user_answer").map { it.asText() })
        verifyNoInteractions(rag)
    }

    @Test
    fun `ask passes the selected problem and snake case user answer to RAG`() {
        val input = AskInput("왜 IX인가요?", listOf("A", "C"))
        `when`(rag.ask(problem, input)).thenReturn(
            AskResultDto(
                "참고 자료 기반 답변",
                emptyList(),
                linkedMapOf(
                    "embedding" to 25.0,
                    "retrieval" to 4.5,
                    "generation" to 3000.0,
                    "other" to 2.0,
                    "rag" to 3031.5,
                ),
            ),
        )
        val response =
            mvc
                .perform(
                    post("/ask/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(mapOf("question" to input.question, "user_answer" to input.userAnswer))),
                ).andReturn()
                .response
        assertEquals(200, response.status)
        assertEquals(
            setOf("reply", "sources"),
            json
                .readTree(response.contentAsByteArray)
                .fieldNames()
                .asSequence()
                .toSet(),
        )
        assertEquals(
            "embedding;dur=25.000, retrieval;dur=4.500, generation;dur=3000.000, other;dur=2.000, rag;dur=3031.500",
            response.getHeader("Server-Timing"),
        )
        verify(rag).ask(problem, input)
    }

    @Test
    fun `malformed submissions and nonexistent problems return client errors`() {
        for (body in listOf("""{"selected":["A"]}""", """["D"]""", "[]", """["A","A"]""")) {
            assertEquals(
                400,
                mvc
                    .perform(post("/grade/1").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn()
                    .response.status,
            )
        }
        assertEquals(
            404,
            mvc
                .perform(post("/grade/999").contentType(MediaType.APPLICATION_JSON).content("""["A"]"""))
                .andReturn()
                .response.status,
        )
        verifyNoInteractions(rag)
    }
}
