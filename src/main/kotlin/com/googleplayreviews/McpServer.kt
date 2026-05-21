package com.googleplayreviews

import com.googleplayreviews.models.Review
import com.googleplayreviews.models.ReviewsPage
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object McpServer {

    private val json = Json { encodeDefaults = false }

    fun create(reviewService: ReviewService): Server {
        val server = Server(
            serverInfo = Implementation(name = "google-play-reviews", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))
            )
        )
        registerListReviews(server, reviewService)
        registerGetReview(server, reviewService)
        registerReplyToReview(server, reviewService)
        registerDeleteReply(server, reviewService)
        return server
    }

    private fun registerListReviews(server: Server, reviewService: ReviewService) {
        server.addTool(
            name = "list_reviews",
            description = "Fetch reviews for a Google Play app with optional client-side filters.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "App package name, e.g. com.example.app")
                    }
                    putJsonObject("pageToken") {
                        put("type", "string")
                        put("description", "Pagination token from a previous list_reviews response")
                    }
                    putJsonObject("maxResults") {
                        put("type", "integer")
                        put("description", "Max reviews to fetch (default 100, max 4096)")
                    }
                    putJsonObject("language") {
                        put("type", "string")
                        put("description", "Filter by BCP-47 reviewer language (client-side)")
                    }
                    putJsonObject("unansweredOnly") {
                        put("type", "boolean")
                        put("description", "If true, only return reviews with no developer reply")
                    }
                    putJsonObject("searchText") {
                        put("type", "string")
                        put("description", "Case-insensitive substring match against original and translated text")
                    }
                    putJsonObject("translationLanguage") {
                        put("type", "string")
                        put("description", "BCP-47 language code; API translates review text to this language")
                    }
                },
                required = listOf("packageName")
            )
        ) { request ->
            val args = request.arguments
            runCatching {
                val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("packageName is required")
                val page = reviewService.listReviews(
                    packageName = packageName,
                    pageToken = args["pageToken"]?.jsonPrimitive?.contentOrNull,
                    maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull ?: 100,
                    language = args["language"]?.jsonPrimitive?.contentOrNull,
                    unansweredOnly = args["unansweredOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    searchText = args["searchText"]?.jsonPrimitive?.contentOrNull,
                    translationLanguage = args["translationLanguage"]?.jsonPrimitive?.contentOrNull
                )
                CallToolResult(content = listOf(TextContent(text = json.encodeToString<ReviewsPage>(page))))
            }.getOrElse { e -> errorResult("list_reviews failed: ${e.message}") }
        }
    }

    private fun registerGetReview(server: Server, reviewService: ReviewService) {
        server.addTool(
            name = "get_review",
            description = "Fetch a single review by ID.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "App package name")
                    }
                    putJsonObject("reviewId") {
                        put("type", "string")
                        put("description", "The review ID to fetch")
                    }
                },
                required = listOf("packageName", "reviewId")
            )
        ) { request ->
            val args = request.arguments
            runCatching {
                val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("packageName is required")
                val reviewId = args["reviewId"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("reviewId is required")
                val review = reviewService.getReview(packageName, reviewId)
                CallToolResult(content = listOf(TextContent(text = json.encodeToString<Review>(review))))
            }.getOrElse { e -> errorResult("get_review failed: ${e.message}") }
        }
    }

    private fun registerReplyToReview(server: Server, reviewService: ReviewService) {
        server.addTool(
            name = "reply_to_review",
            description = "Post or update a developer reply on a review.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "App package name")
                    }
                    putJsonObject("reviewId") {
                        put("type", "string")
                        put("description", "The review ID to reply to")
                    }
                    putJsonObject("replyText") {
                        put("type", "string")
                        put("description", "Text of the developer reply")
                    }
                },
                required = listOf("packageName", "reviewId", "replyText")
            )
        ) { request ->
            val args = request.arguments
            runCatching {
                val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("packageName is required")
                val reviewId = args["reviewId"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("reviewId is required")
                val replyText = args["replyText"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("replyText is required")
                reviewService.replyToReview(packageName, reviewId, replyText)
                CallToolResult(content = listOf(TextContent(text = "Reply posted successfully.")))
            }.getOrElse { e -> errorResult("reply_to_review failed: ${e.message}") }
        }
    }

    private fun registerDeleteReply(server: Server, reviewService: ReviewService) {
        server.addTool(
            name = "delete_reply",
            description = "Delete the developer reply on a review.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "App package name")
                    }
                    putJsonObject("reviewId") {
                        put("type", "string")
                        put("description", "The review ID whose reply should be deleted")
                    }
                },
                required = listOf("packageName", "reviewId")
            )
        ) { request ->
            val args = request.arguments
            runCatching {
                val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("packageName is required")
                val reviewId = args["reviewId"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("reviewId is required")
                reviewService.deleteReply(packageName, reviewId)
                CallToolResult(content = listOf(TextContent(text = "Reply deleted successfully.")))
            }.getOrElse { e -> errorResult("delete_reply failed: ${e.message}") }
        }
    }

    private fun errorResult(message: String) = CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true
    )
}
