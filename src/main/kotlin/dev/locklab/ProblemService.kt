package dev.locklab

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service

data class Problem(
    val id: Int,
    val q: String,
    val tableSql: String,
    val testSql: String,
    val choices: Map<String, String>,
    val answer: List<String>,
    val explanation: String,
) {
    fun view() = ProblemDto(id, q, tableSql, testSql, choices)

    fun grade(selected: List<String>): GradeResultDto {
        if (selected.isEmpty() || selected.distinct().size != selected.size || selected.any { it !in choices }) {
            throw Failure(400, "제시된 선택지를 중복 없이 한 개 이상 선택하세요.")
        }
        return GradeResultDto(
            if (selected.toSet() == answer.toSet()) "맞았습니다" else "틀렸습니다",
            answer,
            explanation,
            selected,
        )
    }
}

@Service
class ProblemService(
    private val db: Database,
    private val json: ObjectMapper,
) {
    // The lab reset drops only lab_v08. Learning material lives in a separate database.
    private val database = "locklab_learning_v08"

    init {
        db.connect().use { Sql.execute(it, "CREATE DATABASE IF NOT EXISTS $database CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci") }
        val seeds = ClassPathResource("data/problems.json").inputStream.use { json.readValue<List<Problem>>(it) }
        require(seeds.isNotEmpty() && seeds.map { it.id }.distinct().size == seeds.size)
        db.connect(database).use { connection ->
            Sql.execute(
                connection,
                """CREATE TABLE IF NOT EXISTS problem (
                id INT PRIMARY KEY, q TEXT NOT NULL, tableSql TEXT NOT NULL, testSql TEXT NOT NULL,
                choices JSON NOT NULL, answer JSON NOT NULL, explanation TEXT NOT NULL
            ) ENGINE=InnoDB""",
            )
            connection
                .prepareStatement(
                    """INSERT INTO problem (id,q,tableSql,testSql,choices,answer,explanation)
                VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE q=VALUES(q),tableSql=VALUES(tableSql),
                testSql=VALUES(testSql),choices=VALUES(choices),answer=VALUES(answer),explanation=VALUES(explanation)""",
                ).use { statement ->
                    for (problem in seeds) {
                        require(
                            problem.answer.isNotEmpty() && problem.answer.distinct().size == problem.answer.size &&
                                problem.answer.all { it in problem.choices },
                        )
                        statement.setInt(1, problem.id)
                        statement.setString(2, problem.q)
                        statement.setString(3, problem.tableSql)
                        statement.setString(4, problem.testSql)
                        statement.setString(5, json.writeValueAsString(problem.choices))
                        statement.setString(6, json.writeValueAsString(problem.answer))
                        statement.setString(7, problem.explanation)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
        }
    }

    fun all(): List<Problem> =
        db.connect(database).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM problem ORDER BY id").use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                Problem(
                                    rows.getInt("id"),
                                    rows.getString("q"),
                                    rows.getString("tableSql"),
                                    rows.getString("testSql"),
                                    json.readValue(rows.getString("choices")),
                                    json.readValue(rows.getString("answer")),
                                    rows.getString("explanation"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    fun find(id: Int) = all().firstOrNull { it.id == id } ?: throw Failure(404, "문제를 찾을 수 없습니다.")
}
