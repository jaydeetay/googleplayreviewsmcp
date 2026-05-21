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

private fun JsonObject.requireString(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

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
            description = "Fetch reviews for a Google Play app. The server auto-paginates the Play Developer API " +
                "(which returns ~12 reviews per page) until `limit` filtered results are collected or " +
                "all pages are exhausted. Filters (language, unansweredOnly, searchText) are applied " +
                "client-side; only matching reviews count toward `limit`. " +
                "When the limit fires mid-page, reviews beyond the limit on that page are not returned. " +
                "The nextPageToken in the response points to the next unfetched API page — resuming " +
                "from it skips those unreturned reviews. Use limit=0 to fetch all reviews without truncation.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "App package name, e.g. com.example.app")
                    }
                    putJsonObject("pageToken") {
                        put("type", "string")
                        put("description", "Resume pagination from this API token (returned as nextPageToken in a previous response). Note: if the previous call hit its limit mid-page, some reviews from that page were not returned.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max filtered reviews to return (default 100); 0 means no limit")
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
            safeToolCall("list_reviews") {
                val packageName = args.requireString("packageName")
                    ?: return@safeToolCall errorResult("packageName is required")
                val page = reviewService.listReviews(
                    packageName = packageName,
                    pageToken = args.requireString("pageToken"),
                    limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 100,
                    language = args.requireString("language"),
                    unansweredOnly = args["unansweredOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    searchText = args.requireString("searchText"),
                    translationLanguage = args.requireString("translationLanguage")
                )
                textResult(json.encodeToString<ReviewsPage>(page))
            }
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
            safeToolCall("get_review") {
                val packageName = args.requireString("packageName")
                    ?: return@safeToolCall errorResult("packageName is required")
                val reviewId = args.requireString("reviewId")
                    ?: return@safeToolCall errorResult("reviewId is required")
                val review = reviewService.getReview(packageName, reviewId)
                textResult(json.encodeToString<Review>(review))
            }
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
            safeToolCall("reply_to_review") {
                val packageName = args.requireString("packageName")
                    ?: return@safeToolCall errorResult("packageName is required")
                val reviewId = args.requireString("reviewId")
                    ?: return@safeToolCall errorResult("reviewId is required")
                val replyText = args.requireString("replyText")
                    ?: return@safeToolCall errorResult("replyText is required")
                reviewService.replyToReview(packageName, reviewId, replyText)
                textResult("Reply posted successfully.")
            }
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
            safeToolCall("delete_reply") {
                val packageName = args.requireString("packageName")
                    ?: return@safeToolCall errorResult("packageName is required")
                val reviewId = args.requireString("reviewId")
                    ?: return@safeToolCall errorResult("reviewId is required")
                reviewService.deleteReply(packageName, reviewId)
                textResult("Reply deleted successfully.")
            }
        }
    }

    private fun errorResult(message: String) = CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true
    )

    private fun textResult(text: String) = CallToolResult(content = listOf(TextContent(text = text)))

    private suspend fun safeToolCall(
        toolName: String,
        block: suspend () -> CallToolResult
    ): CallToolResult = runCatching { block() }
        .getOrElse { e -> errorResult("$toolName failed: ${e.message}") }
}
