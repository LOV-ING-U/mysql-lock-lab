package dev.locklab

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

@Component
class Database(
    @Value("\${lab.database.host}") private val host: String,
    @Value("\${lab.database.port}") private val port: Int,
    @Value("\${lab.database.user}") private val user: String,
    @Value("\${lab.database.password}") private val password: String,
) {
    fun connect(
        name: String? = null,
        multi: Boolean = false,
    ): Connection {
        val properties =
            Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("allowMultiQueries", multi.toString())
                setProperty("allowPublicKeyRetrieval", "true")
                setProperty("sslMode", "DISABLED")
                setProperty("connectTimeout", "5000")
                setProperty("socketTimeout", "120000")
                setProperty("characterEncoding", "UTF-8")
            }
        return DriverManager.getConnection("jdbc:mysql://$host:$port/${name.orEmpty()}", properties)
    }
}
