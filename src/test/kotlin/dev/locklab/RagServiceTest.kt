package dev.locklab

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class RagServiceTest {
    private val json = jacksonObjectMapper()
    private val problem =
        Problem(
            3,
            "id=1의 잠금은?",
            "CREATE TABLE accounts(id INT PRIMARY KEY)",
            "SELECT * FROM accounts WHERE id=1 FOR UPDATE",
            mapOf("A" to "IX", "B" to "X", "C" to "S"),
            listOf("A", "B"),
            "IX와 레코드 X",
        )

    private inner class Fixture(
        val dimension: Int = 384,
        val hits: Boolean = true,
        val key: String = "test-only-key",
        val apiStatus: Int = 200,
        val responseStatus: String = "completed",
        val refusal: Boolean = false,
        val emptyOutput: Boolean = false,
        val delays: Map<String, Long> = emptyMap(),
    ) : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, JsonNode>>()
        val authorization = CopyOnWriteArrayList<Pair<String, String>>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    delays[path]?.let { Thread.sleep(it) }
                    val body = json.readTree(exchange.requestBody)
                    requests.add(path to body)
                    authorization.add(path to exchange.requestHeaders.getFirst("Authorization").orEmpty())
                    val response: Any =
                        when (path) {
                            "/embed" -> mapOf("vector" to List(dimension) { 0.1 }, "collection" to "locklab_v08_0123456789abcdef")
                            "/collections/locklab_v08_0123456789abcdef/points/query" -> {
                                val points =
                                    if (!hits) {
                                        emptyList<Any>()
                                    } else {
                                        (1..3).map { id ->
                                            mapOf(
                                                "payload" to
                                                    mapOf(
                                                        "chunk_id" to "chunk-$id",
                                                        "v_mysql" to 8.4,
                                                        "text" to "참고 문서 $id",
                                                        "source_url" to "https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html",
                                                    ),
                                                "vector" to listOf(9999.0),
                                            )
                                        }
                                    }
                                mapOf("result" to mapOf("points" to points))
                            }
                            "/v1/responses" ->
                                if (apiStatus != 200) {
                                    mapOf("error" to mapOf("message" to "secret: $key"))
                                } else {
                                    mapOf(
                                        "status" to responseStatus,
                                        "output" to
                                            if (emptyOutput) {
                                                emptyList<Any>()
                                            } else {
                                                listOf(
                                                    mapOf("type" to "reasoning", "summary" to emptyList<Any>()),
                                                    mapOf(
                                                        "type" to "message",
                                                        "role" to "assistant",
                                                        "content" to
                                                            if (refusal) {
                                                                listOf(mapOf("type" to "refusal", "refusal" to "refused"))
                                                            } else {
                                                                listOf(
                                                                    mapOf("type" to "output_text", "text" to "IX는 의도 락입니다."),
                                                                    mapOf("type" to "output_text", "text" to "[chunk-1]"),
                                                                )
                                                            },
                                                    ),
                                                )
                                            },
                                    )
                                }
                            else -> error("Unexpected endpoint: $path")
                        }
                    val bytes = json.writeValueAsBytes(response)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(if (path == "/v1/responses") apiStatus else 200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }
        private val url = "http://127.0.0.1:${server.address.port}"
        val service = RagService(json, url, url, key, "test-openai-model", "$url/v1/responses")

        override fun close() = server.stop(0)
    }

    @Test
    fun `retrieval focuses on the question while the LLM receives the full problem and evidence`() {
        Fixture().use { fixture ->
            val result = fixture.service.ask(problem, AskInput("RC라면 달라지나요?", listOf("A", "C")))
            assertEquals(3, result.sources.size)
            assertTrue(result.reply.contains("[chunk-1]"))
            val input =
                fixture.requests[0]
                    .second
                    .path("text")
                    .asText()
            assertEquals("query: RC라면 달라지나요?\nMySQL 8.4 InnoDB", input)
            assertFalse(input.contains(problem.testSql))
            assertFalse(input.contains(problem.tableSql))
            assertFalse(input.contains(problem.q))
            val search = fixture.requests[1].second
            assertEquals(384, search.path("query").size())
            assertEquals(3, search.path("limit").asInt())
            assertEquals(
                8.4,
                search
                    .path("filter")
                    .path("must")[0]
                    .path("range")
                    .path("gte")
                    .asDouble(),
            )
            assertEquals(
                listOf("/embed", "/collections/locklab_v08_0123456789abcdef/points/query", "/v1/responses"),
                fixture.requests.map { it.first },
            )
            assertEquals(listOf("", "", "Bearer test-only-key"), fixture.authorization.map { it.second })
            val llm = fixture.requests.last().second
            val prompt = llm.path("input").asText()
            assertTrue(prompt.contains(problem.tableSql))
            assertTrue(prompt.contains(problem.testSql))
            assertTrue(prompt.contains(problem.q))
            assertTrue(prompt.indexOf("RC라면 달라지나요?") < prompt.indexOf(problem.q))
            assertTrue(prompt.contains("틀렸습니다"))
            assertTrue(prompt.contains("user_answer"))
            assertTrue(prompt.contains("chunk-1"))
            assertFalse(prompt.contains("9999"))
            assertFalse(prompt.contains("\"vector\""))
            assertEquals("test-openai-model", llm.path("model").asText())
            assertFalse(llm.path("store").asBoolean(true))
            assertEquals("low", llm.path("reasoning").path("effort").asText())
            assertEquals(4096, llm.path("max_output_tokens").asInt())
            assertFalse(llm.toString().contains("test-only-key"))
            assertEquals("IX는 의도 락입니다.\n[chunk-1]", result.reply)
        }
    }

    @Test
    fun `timings isolate upstream calls and do not change the JSON answer body`() {
        Fixture(delays = mapOf("/embed" to 25L, "/v1/responses" to 50L)).use { fixture ->
            val result = fixture.service.ask(problem, AskInput("왜?"))
            val timings = result.timingsMs
            assertEquals(setOf("embedding", "retrieval", "generation", "other", "rag"), timings.keys)
            assertTrue(timings.values.all { it.isFinite() && it >= 0 })
            assertTrue(timings.getValue("embedding") >= 25)
            assertTrue(timings.getValue("generation") >= 50)
            assertEquals(timings.getValue("rag"), timings.filterKeys { it != "rag" }.values.sum(), 0.001)
            val body = json.valueToTree<JsonNode>(result)
            assertEquals(setOf("reply", "sources"), body.fieldNames().asSequence().toSet())
        }
    }

    @Test
    fun `gap versus record follow-up is not buried under the earlier primary key exercise`() {
        Fixture().use { fixture ->
            val question = "gap lock과 record lock의 구별 기준은 뭐에요?"
            fixture.service.ask(problem, AskInput(question, listOf("A", "C")))
            val search =
                fixture.requests[0]
                    .second
                    .path("text")
                    .asText()
            assertEquals("query: $question\nMySQL 8.4 InnoDB", search)
            val llm = fixture.requests.last().second
            assertTrue(llm.path("input").asText().startsWith("[지금 답해야 할 사용자 질문]\n$question"))
            assertTrue(llm.path("instructions").asText().contains("개념 질문에는 SQL이나 실험 조건을 추가로 요구하지 마세요"))
        }
    }

    @Test
    fun `wrong embedding dimensions stop before retrieval and generation`() {
        Fixture(dimension = 3).use { fixture ->
            assertEquals(503, assertThrows(Failure::class.java) { fixture.service.ask(problem, AskInput("왜?")) }.status)
            assertEquals(listOf("/embed"), fixture.requests.map { it.first })
        }
    }

    @Test
    fun `missing evidence stops before generation`() {
        Fixture(hits = false).use { fixture ->
            assertEquals(503, assertThrows(Failure::class.java) { fixture.service.ask(problem, AskInput("왜?")) }.status)
            assertEquals(2, fixture.requests.size)
        }
    }

    @Test
    fun `invalid questions or answers do not start model work`() {
        Fixture().use { fixture ->
            for (input in listOf(AskInput(" "), AskInput("가".repeat(1001)), AskInput("왜?", listOf("D")))) {
                assertEquals(400, assertThrows(Failure::class.java) { fixture.service.ask(problem, input) }.status)
            }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `missing API key fails before embedding or any API request`() {
        Fixture(key = " ").use { fixture ->
            val error = assertThrows(Failure::class.java) { fixture.service.ask(problem, AskInput("왜?")) }
            assertEquals(503, error.status)
            assertTrue(error.message.orEmpty().contains("OPENAI_API_KEY"))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun `API authentication limits and errors are readable and do not leak upstream bodies`() {
        for ((status, hint) in mapOf(401 to "인증", 403 to "인증", 429 to "한도", 400 to "OPENAI_MODEL", 500 to "HTTP 500")) {
            Fixture(apiStatus = status).use { fixture ->
                repeat(2) {
                    val error = assertThrows(Failure::class.java) { fixture.service.ask(problem, AskInput("왜?")) }
                    assertEquals(503, error.status)
                    assertTrue(error.message.orEmpty().contains(hint))
                    assertFalse(error.message.orEmpty().contains("test-only-key"))
                    assertFalse(error.message.orEmpty().contains("secret"))
                }
            }
        }
    }

    @Test
    fun `incomplete refused and empty responses never become successful answers`() {
        for (fixture in listOf(Fixture(responseStatus = "incomplete"), Fixture(refusal = true), Fixture(emptyOutput = true))) {
            fixture.use {
                assertEquals(503, assertThrows(Failure::class.java) { it.service.ask(problem, AskInput("왜?")) }.status)
            }
        }
    }
}
