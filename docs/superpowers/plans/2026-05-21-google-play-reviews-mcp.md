# Google Play Reviews MCP Server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Kotlin/JVM MCP server that exposes four MCP tools for reading and replying to Google Play reviews, using the official MCP Kotlin SDK and Google Play Developer API, with stdio transport so Claude manages the process lifecycle.

**Architecture:** `GoogleAuthProvider` loads service account credentials from the `GOOGLE_SERVICE_ACCOUNT_JSON` env var at startup and fails fast if missing. `ReviewService` wraps the `AndroidPublisher` Java client and applies client-side filters (language, unanswered-only, text search). `McpServer` registers the four MCP tools and delegates to `ReviewService`. `Main.kt` wires the stdio transport and starts the server.

**Tech Stack:** Kotlin 2.0, Gradle Kotlin DSL, `io.modelcontextprotocol:kotlin-sdk`, `com.google.apis:google-api-services-androidpublisher`, `com.google.auth:google-auth-library-oauth2-http`, `kotlinx.serialization`, JUnit 5, kotlin.test

---

## File Map

| File | Purpose |
|------|---------|
| `build.gradle.kts` | Gradle build: dependencies, fat-jar config, JVM toolchain |
| `settings.gradle.kts` | Project name |
| `.gitignore` | Ignore build output and IDE files |
| `src/main/kotlin/com/googleplayreviews/Main.kt` | Entry point — initializes auth, wires transport, starts server |
| `src/main/kotlin/com/googleplayreviews/McpServer.kt` | Registers all four MCP tools, delegates to ReviewService |
| `src/main/kotlin/com/googleplayreviews/ReviewService.kt` | AndroidPublisher API calls + client-side filtering |
| `src/main/kotlin/com/googleplayreviews/GoogleAuthProvider.kt` | Loads service account JSON from env var → GoogleCredentials |
| `src/main/kotlin/com/googleplayreviews/models/Review.kt` | Data classes: Review, DeveloperReply, DeviceInfo, ReviewsPage |
| `src/test/kotlin/com/googleplayreviews/ReviewServiceFilterTest.kt` | Unit tests for all client-side filtering logic |
| `src/test/kotlin/com/googleplayreviews/GoogleAuthProviderTest.kt` | Unit tests for auth error handling |
| `src/test/kotlin/com/googleplayreviews/GooglePlayApiIntegrationTest.kt` | @Disabled integration tests (opt-in, require real credentials) |

---

### Task 1: Scaffold the Gradle project

**Files:**
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `.gitignore`
- Create: `src/main/kotlin/com/googleplayreviews/Main.kt` (placeholder)

- [ ] **Step 1: Create `.gitignore`**

```
.gradle/
build/
.idea/
*.iml
.DS_Store
```

- [ ] **Step 2: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "googleplayreviewsmcp"
```

- [ ] **Step 3: Create `build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    application
}

group = "com.googleplayreviews"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0")
    implementation("com.google.apis:google-api-services-androidpublisher:v3-rev20241217-2.0.0")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.25.0")
    implementation("com.google.http-client:google-http-client-gson:1.44.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.googleplayreviews.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.googleplayreviews.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

- [ ] **Step 4: Initialize Gradle wrapper**

```bash
gradle wrapper --gradle-version 8.10
```

Expected: `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` created.

- [ ] **Step 5: Create source directories**

```bash
mkdir -p src/main/kotlin/com/googleplayreviews/models
mkdir -p src/test/kotlin/com/googleplayreviews
```

- [ ] **Step 6: Create placeholder `Main.kt` so the project has a main class**

`src/main/kotlin/com/googleplayreviews/Main.kt`:

```kotlin
package com.googleplayreviews

fun main() {
    println("Google Play Reviews MCP Server")
}
```

- [ ] **Step 7: Verify the build compiles and runs**

```bash
./gradlew run
```

Expected output includes:
```
Google Play Reviews MCP Server
```

- [ ] **Step 8: Commit**

```bash
git add build.gradle.kts settings.gradle.kts .gitignore gradle/ src/
git commit -m "chore: scaffold Gradle project"
```

---

### Task 2: Data models

**Files:**
- Create: `src/main/kotlin/com/googleplayreviews/models/Review.kt`

- [ ] **Step 1: Create `Review.kt` with all data classes**

`src/main/kotlin/com/googleplayreviews/models/Review.kt`:

```kotlin
package com.googleplayreviews.models

import kotlinx.serialization.Serializable

@Serializable
data class Review(
    val reviewId: String,
    val authorName: String,
    val rating: Int,
    val reviewerLanguage: String,
    val createTime: String,
    val updateTime: String,
    val originalText: String,
    val translatedText: String? = null,
    val deviceInfo: DeviceInfo? = null,
    val reply: DeveloperReply? = null
)

@Serializable
data class DeveloperReply(
    val text: String,
    val lastModified: String
)

@Serializable
data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val androidVersion: String? = null
)

@Serializable
data class ReviewsPage(
    val reviews: List<Review>,
    val nextPageToken: String? = null
)
```

- [ ] **Step 2: Verify the build compiles**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/models/Review.kt
git commit -m "feat: add Review data models"
```

---

### Task 3: GoogleAuthProvider

**Files:**
- Create: `src/main/kotlin/com/googleplayreviews/GoogleAuthProvider.kt`
- Create: `src/test/kotlin/com/googleplayreviews/GoogleAuthProviderTest.kt`

- [ ] **Step 1: Write failing tests**

`src/test/kotlin/com/googleplayreviews/GoogleAuthProviderTest.kt`:

```kotlin
package com.googleplayreviews

import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertTrue

class GoogleAuthProviderTest {

    @Test
    fun `throws when env var is missing`() {
        val exception = assertThrows<IllegalStateException> {
            GoogleAuthProvider.loadCredentials(envVarValue = null)
        }
        assertTrue(
            exception.message!!.contains("GOOGLE_SERVICE_ACCOUNT_JSON"),
            "Error message should mention the env var name"
        )
    }

    @Test
    fun `throws when env var contains malformed JSON`() {
        assertThrows<IllegalArgumentException> {
            GoogleAuthProvider.loadCredentials(envVarValue = "not-valid-json")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.googleplayreviews.GoogleAuthProviderTest"
```

Expected: FAIL — `GoogleAuthProvider` does not exist yet.

- [ ] **Step 3: Implement `GoogleAuthProvider`**

`src/main/kotlin/com/googleplayreviews/GoogleAuthProvider.kt`:

```kotlin
package com.googleplayreviews

import com.google.auth.oauth2.GoogleCredentials
import java.io.ByteArrayInputStream

object GoogleAuthProvider {

    private const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"

    fun fromEnv(): GoogleCredentials {
        val json = System.getenv("GOOGLE_SERVICE_ACCOUNT_JSON")
        return loadCredentials(json)
    }

    fun loadCredentials(envVarValue: String?): GoogleCredentials {
        checkNotNull(envVarValue) {
            "GOOGLE_SERVICE_ACCOUNT_JSON environment variable is not set"
        }
        return try {
            GoogleCredentials
                .fromStream(ByteArrayInputStream(envVarValue.toByteArray()))
                .createScoped(listOf(SCOPE))
        } catch (e: Exception) {
            throw IllegalArgumentException(
                "GOOGLE_SERVICE_ACCOUNT_JSON contains invalid content: ${e.message}", e
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.googleplayreviews.GoogleAuthProviderTest"
```

Expected: PASS — 2 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/GoogleAuthProvider.kt \
        src/test/kotlin/com/googleplayreviews/GoogleAuthProviderTest.kt
git commit -m "feat: add GoogleAuthProvider with env var loading"
```

---

### Task 4: ReviewService — client-side filtering

The filtering logic in `ReviewService.applyFilters` is the primary unit-testable piece. Write the tests first; they drive the implementation.

**Files:**
- Create: `src/main/kotlin/com/googleplayreviews/ReviewService.kt`
- Create: `src/test/kotlin/com/googleplayreviews/ReviewServiceFilterTest.kt`

- [ ] **Step 1: Write failing filter tests**

`src/test/kotlin/com/googleplayreviews/ReviewServiceFilterTest.kt`:

```kotlin
package com.googleplayreviews

import com.googleplayreviews.models.DeveloperReply
import com.googleplayreviews.models.Review
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewServiceFilterTest {

    private val sampleReply = DeveloperReply(
        text = "Thanks!",
        lastModified = "2024-01-02T00:00:00Z"
    )

    private fun fakeReview(
        reviewId: String = "r1",
        language: String = "en",
        originalText: String = "Great app",
        translatedText: String? = null,
        reply: DeveloperReply? = null
    ) = Review(
        reviewId = reviewId,
        authorName = "Test User",
        rating = 5,
        reviewerLanguage = language,
        createTime = "2024-01-01T00:00:00Z",
        updateTime = "2024-01-01T00:00:00Z",
        originalText = originalText,
        translatedText = translatedText,
        reply = reply
    )

    @Test
    fun `language filter includes matching reviews`() {
        val reviews = listOf(fakeReview(language = "en"), fakeReview(language = "fr"))
        val result = ReviewService.applyFilters(reviews, language = "en")
        assertEquals(1, result.size)
        assertEquals("en", result[0].reviewerLanguage)
    }

    @Test
    fun `language filter is case-insensitive`() {
        val reviews = listOf(fakeReview(language = "EN"))
        val result = ReviewService.applyFilters(reviews, language = "en")
        assertEquals(1, result.size)
    }

    @Test
    fun `language filter null passes all reviews through`() {
        val reviews = listOf(fakeReview(language = "en"), fakeReview(language = "fr"))
        val result = ReviewService.applyFilters(reviews, language = null)
        assertEquals(2, result.size)
    }

    @Test
    fun `unansweredOnly excludes reviews with replies`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", reply = null),
            fakeReview(reviewId = "r2", reply = sampleReply)
        )
        val result = ReviewService.applyFilters(reviews, unansweredOnly = true)
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `unansweredOnly false passes all reviews through`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", reply = null),
            fakeReview(reviewId = "r2", reply = sampleReply)
        )
        val result = ReviewService.applyFilters(reviews, unansweredOnly = false)
        assertEquals(2, result.size)
    }

    @Test
    fun `searchText matches against original text`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", originalText = "I love this app"),
            fakeReview(reviewId = "r2", originalText = "This is terrible")
        )
        val result = ReviewService.applyFilters(reviews, searchText = "love")
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `searchText matches against translated text when original does not match`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", originalText = "J'adore", translatedText = "I love this"),
            fakeReview(reviewId = "r2", originalText = "C'est nul", translatedText = "This is bad")
        )
        val result = ReviewService.applyFilters(reviews, searchText = "love")
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `searchText is case-insensitive`() {
        val reviews = listOf(fakeReview(originalText = "Great App"))
        val result = ReviewService.applyFilters(reviews, searchText = "great app")
        assertEquals(1, result.size)
    }

    @Test
    fun `searchText null passes all reviews through`() {
        val reviews = listOf(fakeReview(originalText = "anything"))
        val result = ReviewService.applyFilters(reviews, searchText = null)
        assertEquals(1, result.size)
    }

    @Test
    fun `multiple filters applied together`() {
        val reviews = listOf(
            fakeReview(reviewId = "r1", language = "en", originalText = "love it", reply = null),
            fakeReview(reviewId = "r2", language = "en", originalText = "love it", reply = sampleReply),
            fakeReview(reviewId = "r3", language = "fr", originalText = "love it", reply = null),
            fakeReview(reviewId = "r4", language = "en", originalText = "hate it", reply = null)
        )
        val result = ReviewService.applyFilters(
            reviews,
            language = "en",
            unansweredOnly = true,
            searchText = "love"
        )
        assertEquals(1, result.size)
        assertEquals("r1", result[0].reviewId)
    }

    @Test
    fun `returns empty list when nothing matches`() {
        val reviews = listOf(fakeReview(originalText = "great"))
        val result = ReviewService.applyFilters(reviews, searchText = "terrible")
        assertEquals(0, result.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.googleplayreviews.ReviewServiceFilterTest"
```

Expected: FAIL — `ReviewService` does not exist yet.

- [ ] **Step 3: Create `ReviewService.kt`**

`src/main/kotlin/com/googleplayreviews/ReviewService.kt`:

```kotlin
package com.googleplayreviews

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.ReviewsReplyRequest
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.googleplayreviews.models.DeveloperReply
import com.googleplayreviews.models.DeviceInfo
import com.googleplayreviews.models.Review
import com.googleplayreviews.models.ReviewsPage
import java.time.Instant

class ReviewService(credentials: GoogleCredentials) {

    private val publisher = AndroidPublisher.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        HttpCredentialsAdapter(credentials)
    ).setApplicationName("google-play-reviews-mcp").build()

    fun listReviews(
        packageName: String,
        pageToken: String? = null,
        maxResults: Int = 100,
        language: String? = null,
        unansweredOnly: Boolean = false,
        searchText: String? = null,
        translationLanguage: String? = null
    ): ReviewsPage {
        val request = publisher.reviews().list(packageName)
            .setMaxResults(maxResults.toLong())
        pageToken?.let { request.setPageToken(it) }
        translationLanguage?.let { request.setTranslationLanguage(it) }

        val response = request.execute()
        val allReviews = (response.reviews ?: emptyList()).map { mapReview(it) }
        val filtered = applyFilters(allReviews, language, unansweredOnly, searchText)

        return ReviewsPage(
            reviews = filtered,
            nextPageToken = response.tokenPagination?.nextPageToken
        )
    }

    fun getReview(packageName: String, reviewId: String): Review {
        val response = publisher.reviews().get(packageName, reviewId).execute()
        return mapReview(response)
    }

    fun replyToReview(packageName: String, reviewId: String, replyText: String) {
        val replyRequest = ReviewsReplyRequest().setReplyText(replyText)
        publisher.reviews().reply(packageName, reviewId, replyRequest).execute()
    }

    fun deleteReply(packageName: String, reviewId: String) {
        // The Play Developer API has no dedicated delete-reply endpoint.
        // Posting an empty string clears the reply.
        replyToReview(packageName, reviewId, "")
    }

    private fun mapReview(
        apiReview: com.google.api.services.androidpublisher.model.Review
    ): Review {
        val userComment = apiReview.comments
            ?.firstOrNull { it.userComment != null }
            ?.userComment

        val developerComment = apiReview.comments
            ?.firstOrNull { it.developerComment != null }
            ?.developerComment

        val metadata = userComment?.deviceMetadata

        // When translationLanguage is requested, the API puts the translated text in `text`
        // and the original in `originalText`. When no translation is requested, `originalText`
        // is null and `text` contains the original.
        val originalText = userComment?.originalText ?: userComment?.text ?: ""
        val translatedText = if (userComment?.originalText != null) userComment.text else null

        return Review(
            reviewId = apiReview.reviewId ?: "",
            authorName = apiReview.authorName ?: "Unknown",
            rating = userComment?.starRating ?: 0,
            reviewerLanguage = userComment?.reviewerLanguage ?: "",
            createTime = userComment?.lastModified?.seconds
                ?.let { Instant.ofEpochSecond(it).toString() } ?: "",
            updateTime = userComment?.lastModified?.seconds
                ?.let { Instant.ofEpochSecond(it).toString() } ?: "",
            originalText = originalText,
            translatedText = translatedText,
            deviceInfo = metadata?.let {
                DeviceInfo(
                    manufacturer = it.manufacturer,
                    model = it.productName,
                    androidVersion = it.androidSdkVersion?.toString()
                )
            },
            reply = developerComment?.let {
                DeveloperReply(
                    text = it.text ?: "",
                    lastModified = it.lastModified?.seconds
                        ?.let { s -> Instant.ofEpochSecond(s).toString() } ?: ""
                )
            }
        )
    }

    companion object {
        fun applyFilters(
            reviews: List<Review>,
            language: String? = null,
            unansweredOnly: Boolean = false,
            searchText: String? = null
        ): List<Review> = reviews.filter { review ->
            val languageMatch = language == null ||
                review.reviewerLanguage.equals(language, ignoreCase = true)

            val unansweredMatch = !unansweredOnly || review.reply == null

            val searchMatch = searchText == null || run {
                val term = searchText.lowercase()
                review.originalText.lowercase().contains(term) ||
                    review.translatedText?.lowercase()?.contains(term) == true
            }

            languageMatch && unansweredMatch && searchMatch
        }
    }
}
```

- [ ] **Step 4: Run filter tests to verify they pass**

```bash
./gradlew test --tests "com.googleplayreviews.ReviewServiceFilterTest"
```

Expected: PASS — 11 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/ReviewService.kt \
        src/test/kotlin/com/googleplayreviews/ReviewServiceFilterTest.kt
git commit -m "feat: add ReviewService with client-side filtering"
```

---

### Task 5: McpServer — tool registration

**Files:**
- Create: `src/main/kotlin/com/googleplayreviews/McpServer.kt`

- [ ] **Step 1: Create `McpServer.kt`**

`src/main/kotlin/com/googleplayreviews/McpServer.kt`:

```kotlin
package com.googleplayreviews

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object McpServer {

    private val json = Json { encodeDefaults = false }

    fun create(reviewService: ReviewService): Server {
        val server = Server(
            serverInfo = Implementation(name = "google-play-reviews", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())
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
            description = "Fetch reviews for a Google Play app with optional client-side filters. " +
                "Returns a page of reviews and a nextPageToken for pagination.",
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
                        put("description", "Max reviews to fetch from the API (default 100, max 4096)")
                    }
                    putJsonObject("language") {
                        put("type", "string")
                        put("description", "Filter to reviews with this BCP-47 reviewer language (client-side)")
                    }
                    putJsonObject("unansweredOnly") {
                        put("type", "boolean")
                        put("description", "If true, only return reviews with no developer reply (client-side)")
                    }
                    putJsonObject("searchText") {
                        put("type", "string")
                        put("description", "Case-insensitive substring match against original and translated text (client-side)")
                    }
                    putJsonObject("translationLanguage") {
                        put("type", "string")
                        put("description", "BCP-47 language code; the API translates review text to this language")
                    }
                },
                required = listOf("packageName")
            )
        ) { request ->
            val args = request.params.arguments ?: buildJsonObject {}
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
                CallToolResult(content = listOf(TextContent(text = json.encodeToString(page))))
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
            val args = request.params.arguments ?: buildJsonObject {}
            runCatching {
                val packageName = args["packageName"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("packageName is required")
                val reviewId = args["reviewId"]?.jsonPrimitive?.contentOrNull
                    ?: return@addTool errorResult("reviewId is required")
                val review = reviewService.getReview(packageName, reviewId)
                CallToolResult(content = listOf(TextContent(text = json.encodeToString(review))))
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
            val args = request.params.arguments ?: buildJsonObject {}
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
            val args = request.params.arguments ?: buildJsonObject {}
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
```

- [ ] **Step 2: Verify the build compiles**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/McpServer.kt
git commit -m "feat: register MCP tools in McpServer"
```

---

### Task 6: Wire `Main.kt`

**Files:**
- Modify: `src/main/kotlin/com/googleplayreviews/Main.kt`

- [ ] **Step 1: Replace the placeholder with the real entry point**

`src/main/kotlin/com/googleplayreviews/Main.kt`:

```kotlin
package com.googleplayreviews

import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
    val credentials = try {
        GoogleAuthProvider.fromEnv()
    } catch (e: IllegalStateException) {
        System.err.println("Startup failed: ${e.message}")
        return@runBlocking
    }

    val reviewService = ReviewService(credentials)
    val server = McpServer.create(reviewService)
    val transport = StdioServerTransport()

    server.connect(transport)
}
```

- [ ] **Step 2: Verify the full test suite still passes**

```bash
./gradlew test
```

Expected: PASS — all unit tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/Main.kt
git commit -m "feat: wire Main.kt entry point with stdio transport"
```

---

### Task 7: Integration test scaffold

**Files:**
- Create: `src/test/kotlin/com/googleplayreviews/GooglePlayApiIntegrationTest.kt`

- [ ] **Step 1: Create the `@Disabled` integration test file**

`src/test/kotlin/com/googleplayreviews/GooglePlayApiIntegrationTest.kt`:

```kotlin
package com.googleplayreviews

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Disabled("Requires GOOGLE_SERVICE_ACCOUNT_JSON and TEST_PACKAGE_NAME env vars. Run manually only.")
class GooglePlayApiIntegrationTest {

    private val packageName = System.getenv("TEST_PACKAGE_NAME")
        ?: error("TEST_PACKAGE_NAME env var not set")

    private val reviewService = ReviewService(GoogleAuthProvider.fromEnv())

    @Test
    fun `can list reviews`() {
        val page = reviewService.listReviews(packageName, maxResults = 5)
        assertNotNull(page)
        assertTrue(page.reviews.isNotEmpty(), "Expected at least one review")
    }

    @Test
    fun `can get a single review`() {
        val page = reviewService.listReviews(packageName, maxResults = 1)
        val reviewId = page.reviews.first().reviewId
        val review = reviewService.getReview(packageName, reviewId)
        assertNotNull(review)
        assertTrue(review.reviewId == reviewId)
    }

    @Test
    fun `language filter returns only matching reviews`() {
        val page = reviewService.listReviews(packageName, maxResults = 50, language = "en")
        assertTrue(
            page.reviews.all { it.reviewerLanguage.equals("en", ignoreCase = true) },
            "All reviews should have reviewerLanguage=en"
        )
    }
}
```

- [ ] **Step 2: Run the full test suite to confirm `@Disabled` tests are skipped**

```bash
./gradlew test
```

Expected: PASS — unit tests pass, integration tests skipped.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/googleplayreviews/GooglePlayApiIntegrationTest.kt
git commit -m "test: add @Disabled integration test scaffold"
```

---

### Task 8: Build fat JAR and configure Claude MCP

**Files:**
- No new source files — verify the `jar` task from `build.gradle.kts` works.

- [ ] **Step 1: Build the fat JAR**

```bash
./gradlew jar
```

Expected: `build/libs/googleplayreviewsmcp-1.0.0.jar` created.

- [ ] **Step 2: Verify the JAR exits cleanly when env var is missing**

```bash
java -jar build/libs/googleplayreviewsmcp-1.0.0.jar
```

Expected output on stderr:
```
Startup failed: GOOGLE_SERVICE_ACCOUNT_JSON environment variable is not set
```

- [ ] **Step 3: Add the server to Claude Code MCP config**

Run the following, replacing `/absolute/path/to` with your actual project path:

```bash
claude mcp add google-play-reviews \
  --command "java" \
  --args "-jar,/absolute/path/to/googleplayreviewsmcp/build/libs/googleplayreviewsmcp-1.0.0.jar" \
  --env "GOOGLE_SERVICE_ACCOUNT_JSON=$(cat /path/to/your-service-account-key.json)"
```

Or manually add to `~/.claude/mcp_servers.json`:

```json
{
  "google-play-reviews": {
    "command": "java",
    "args": [
      "-jar",
      "/absolute/path/to/googleplayreviewsmcp/build/libs/googleplayreviewsmcp-1.0.0.jar"
    ],
    "env": {
      "GOOGLE_SERVICE_ACCOUNT_JSON": "<paste your service account JSON here>"
    }
  }
}
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "chore: verify fat jar build"
```

---

## Notes for the Developer

**MCP SDK API:** The `io.modelcontextprotocol:kotlin-sdk` is evolving. If class names like `Tool.Input`, `ServerCapabilities.Tools`, or `StdioServerTransport` don't resolve, check the SDK's README and adjust imports accordingly. The logic won't change — only the wrapper classes.

**deleteReply:** The Play Developer API v3 has no dedicated delete-reply endpoint. Posting an empty string via `reviews.reply` is the documented workaround. Verify this works with your app in the Play Console before relying on it.

**SDK version:** Check [Maven Central](https://central.sonatype.com/artifact/io.modelcontextprotocol/kotlin-sdk) for the latest `kotlin-sdk` version before building.
