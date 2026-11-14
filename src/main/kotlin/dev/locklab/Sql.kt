package dev.locklab

import java.math.BigInteger
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement
import java.time.temporal.TemporalAccessor

internal object Sql {
    fun input(sql: String?): String {
        if (sql.isNullOrBlank() || sql.toByteArray(Charsets.UTF_8).size > 32768) {
            throw Failure(400, "SQL을 입력하세요 (최대 32KB).")
        }
        return sql
    }

    fun rows(
        connection: Connection,
        query: String,
    ): List<Map<String, Any?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { result -> buildList { while (result.next()) add(row(result)) } }
        }

    private fun row(result: ResultSet): Map<String, Any?> =
        buildMap {
            for (index in 1..result.metaData.columnCount) {
                val raw = result.getObject(index)
                val value =
                    when (raw) {
                        is Long -> if (raw > 9007199254740991L || raw < -9007199254740991L) raw.toString() else raw
                        is BigInteger, is java.util.Date, is TemporalAccessor -> raw.toString()
                        is ByteArray -> raw.joinToString("") { "%02x".format(it) }
                        else -> raw
                    }
                put(result.metaData.getColumnLabel(index), value)
            }
        }

    fun execute(
        connection: Connection,
        sql: String,
    ): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        connection.createStatement().use { statement ->
            var isRows = statement.execute(sql)
            while (true) {
                if (isRows) {
                    statement.resultSet.use { result ->
                        val columns = (1..result.metaData.columnCount).map(result.metaData::getColumnLabel)
                        val rows = mutableListOf<Map<String, Any?>>()
                        var total = 0L
                        while (result.next()) if (total++ < 200) rows.add(row(result))
                        results.add(mapOf("columns" to columns, "rows" to rows, "totalRows" to total, "truncated" to (total > 200)))
                    }
                } else {
                    val count = statement.largeUpdateCount
                    if (count == -1L) break
                    results.add(mapOf("affectedRows" to count))
                }
                isRows = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT)
            }
        }
        return results
    }
}
