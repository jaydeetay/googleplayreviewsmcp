package com.googleplayreviews

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSource
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.File

fun main(): Unit = runBlocking {
    val credentials = try {
        GoogleAuthProvider.fromEnv()
    } catch (e: IllegalStateException) {
        System.err.println("Startup failed: ${e.message}")
        return@runBlocking
    }

    val historicalSource = System.getenv("PLAY_REVIEWS_DIR")?.let { dirPath ->
        val dir = File(dirPath)
        if (dir.isDirectory) {
            CsvReviewRepository(dir)
        } else {
            System.err.println("Warning: PLAY_REVIEWS_DIR=$dirPath is not a valid directory — list_historical_reviews tool will not be available")
            null
        }
    }

    val reviewService = ReviewService(credentials)
    val server = McpServer.create(reviewService, historicalSource)
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered()
    )

    val done = CompletableDeferred<Unit>()
    server.onClose { done.complete(Unit) }
    server.connect(transport)
    done.await()
}
