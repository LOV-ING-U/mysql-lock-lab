package dev.locklab

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LearningTest {
    private val json = jacksonObjectMapper()
    private val problem =
        Problem(
            1,
            "문제",
            "CREATE TABLE t(id INT PRIMARY KEY)",
            "SELECT * FROM t FOR UPDATE",
            linkedMapOf("A" to "가", "B" to "나", "C" to "다"),
            listOf("A", "B"),
            "정석 해설",
        )

    @Test
    fun `multiple choices compare as sets and preserve the submitted order`() {
        val result = problem.grade(listOf("B", "A"))
        assertEquals("맞았습니다", result.result)
        assertEquals(listOf("B", "A"), result.userAnswer)
        assertEquals("틀렸습니다", problem.grade(listOf("A", "C")).result)
        assertEquals("틀렸습니다", problem.grade(listOf("A")).result)
    }

    @Test
    fun `empty duplicate and unknown choices are invalid requests`() {
        for (answers in listOf(emptyList(), listOf("A", "A"), listOf("D"))) {
            val error = assertThrows(Failure::class.java) { problem.grade(answers) }
            assertEquals(400, error.status)
        }
    }

    @Test
    fun `JSON follows the PDF keys and does not reveal answers before grading`() {
        val submitted: List<String> = json.readValue("""["A","C"]""")
        val result = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(problem.grade(submitted))
        assertEquals(setOf("result", "answer", "explanation", "user_answer"), result.fieldNames().asSequence().toSet())
        assertEquals("A", result.path("user_answer")[0].asText())
        val publicProblem = json.valueToTree<com.fasterxml.jackson.databind.JsonNode>(problem.view())
        assertEquals(setOf("id", "q", "tableSql", "testSql", "choices"), publicProblem.fieldNames().asSequence().toSet())
        assertTrue(publicProblem.path("choices").isObject)
    }
}
