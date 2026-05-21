package com.googleplayreviews

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered

fun main(): Unit = runBlocking {
    val credentials = try {
        GoogleAuthProvider.fromEnv()
    } catch (e: IllegalStateException) {
        System.err.println("Startup failed: ${e.message}")
        return@runBlocking
    }

    val reviewService = ReviewService(credentials)
    val server = McpServer.create(reviewService)
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    val done = CompletableDeferred<Unit>()
    server.onClose { done.complete(Unit) }
    server.connect(transport)
    done.await()
}
