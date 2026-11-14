package dev.locklab

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class LockLabApplication

fun main(args: Array<String>) {
    runApplication<LockLabApplication>(*args)
}
