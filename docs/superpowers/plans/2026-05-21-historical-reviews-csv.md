# Historical Reviews — Local CSV Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `list_historical_reviews` MCP tool that reads locally-downloaded Google Play review CSV files, enabling access to review history beyond the 7-day live API window.

**Architecture:** A new `HistoricalReviewSource` interface is implemented by `CsvReviewRepository`, which discovers and parses UTF-16LE monthly CSV files from a `PLAY_REVIEWS_DIR` env var directory. `McpServer` receives a nullable `HistoricalReviewSource` and registers the tool only when it's non-null.

**Tech Stack:** Kotlin, JUnit 5 (`@TempDir`), existing `ReviewService.applyFilters`, `kotlinx.coroutines`

---

### Task 1: HistoricalReviewSource interface and CsvReviewRepository skeleton

**Files:**
- Create: `src/main/kotlin/com/googleplayreviews/HistoricalReviewSource.kt`
- Create: `src/main/kotlin/com/googleplayreviews/CsvReviewRepository.kt`
- Create: `src/test/kotlin/com/googleplayreviews/CsvReviewRepositoryTest.kt`

- [ ] **Step 1: Create the interface**

`src/main/kotlin/com/googleplayreviews/HistoricalReviewSource.kt`:
```kotlin
package com.googleplayreviews

import com.googleplayreviews.models.ReviewsPage

interface HistoricalReviewSource {
    suspend fun listReviews(
        packageName: String,
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 100,
        language: String? = null,
        unansweredOnly: Boolean = false,
        searchText: String? = null
    ): ReviewsPage
}
```

- [ ] **Step 2: Write a failing test for an empty reviews directory**

`src/test/kotlin/com/googleplayreviews/CsvReviewRepositoryTest.kt`:
```kotlin
package com.googleplayreviews

import com.googleplayreviews.models.ReviewsPage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsvReviewRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private data class FixtureRow(
        val reviewId: String,
        val language: String = "en",
        val device: String = "Pixel6",
        val submitDate: String = "2023-06-15T10:00:00Z",
        val rating: Int = 4,
        val text: String = "Great app",
        val replyText: String = "",
        val replyDate: String = ""
    )

    private fun writeCsv(packageName: String, yyyymm: String, rows: List<FixtureRow>) {
        val file = File(tempDir, "reviews_${packageName}_${yyyymm}.csv")
        val header = "Package Name,App Version Code,App Version Name,Reviewer Language,Device," +
            "Review Submit Date and Time,Review Submit Millis Since Epoch," +
            "Review Last Update Date and Time,Review Last Update Millis Since Epoch," +
            "Star Rating,Review Title,Review Text," +
            "Developer Reply Date and Time,Developer Reply Millis Since Epoch," +
            "Developer Reply Text,Review Link"
        val lines = mutableListOf(header)
        for (r in rows) {
            lines.add(
                "$packageName,,,${r.language},${r.device}," +
                "${r.submitDate},0,${r.submitDate},0," +
                "${r.rating},,${r.text}," +
                "${r.replyDate},0,${r.replyText}," +
                "http://play.google.com/console?reviewId=${r.reviewId}"
            )
        }
        file.writeText("﻿" + lines.joinToString("\n"), Charsets.UTF_16LE)
    }

    @Test
    fun `empty directory returns empty ReviewsPage`() = runBlocking {
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(0, result.reviews.size)
        assertNull(result.nextPageToken)
    }
}
```

- [ ] **Step 3: Create a CsvReviewRepository stub that compiles**

`src/main/kotlin/com/googleplayreviews/CsvReviewRepository.kt`:
```kotlin
package com.googleplayreviews

import com.googleplayreviews.models.ReviewsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CsvReviewRepository(private val reviewsDir: File) : HistoricalReviewSource {

    override suspend fun listReviews(
        packageName: String,
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 100,
        language: String? = null,
        unansweredOnly: Boolean = false,
        searchText: String? = null
    ): ReviewsPage = withContext(Dispatchers.IO) {
        ReviewsPage(emptyList())
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

```bash
./gradlew test --tests "com.googleplayreviews.CsvReviewRepositoryTest.empty directory returns empty ReviewsPage"
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/HistoricalReviewSource.kt \
        src/main/kotlin/com/googleplayreviews/CsvReviewRepository.kt \
        src/test/kotlin/com/googleplayreviews/CsvReviewRepositoryTest.kt
git commit -m "feat: add HistoricalReviewSource interface and CsvReviewRepository stub"
```

---

### Task 2: CsvReviewRepository — CSV parsing and row mapping

**Files:**
- Modify: `src/main/kotlin/com/googleplayreviews/CsvReviewRepository.kt`
- Modify: `src/test/kotlin/com/googleplayreviews/CsvReviewRepositoryTest.kt`

- [ ] **Step 1: Write failing tests for CSV parsing**

Add these tests to `CsvReviewRepositoryTest` (inside the class, after the empty-directory test):
```kotlin
    @Test
    fun `returns mapped reviews from CSV file`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(reviewId = "abc-123", language = "en", rating = 5, text = "Love it"),
            FixtureRow(reviewId = "def-456", language = "fr", rating = 3, text = "Pas mal")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(2, result.reviews.size)
        val first = result.reviews[0]
        assertEquals("abc-123", first.reviewId)
        assertEquals("Unknown", first.authorName)
        assertEquals(5, first.rating)
        assertEquals("en", first.reviewerLanguage)
        assertEquals("Love it", first.originalText)
        assertEquals("Pixel6", first.deviceInfo?.model)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `rows without a Review Link are skipped`() = runBlocking {
        val file = File(tempDir, "reviews_com.example.app_202306.csv")
        val header = "Package Name,App Version Code,App Version Name,Reviewer Language,Device," +
            "Review Submit Date and Time,Review Submit Millis Since Epoch," +
            "Review Last Update Date and Time,Review Last Update Millis Since Epoch," +
            "Star Rating,Review Title,Review Text," +
            "Developer Reply Date and Time,Developer Reply Millis Since Epoch," +
            "Developer Reply Text,Review Link"
        val content = "$header\ncom.example.app,,,en,Pixel6,2023-06-15T10:00:00Z,0,2023-06-15T10:00:00Z,0,4,,No link,,,,"
        file.writeText("﻿$content", Charsets.UTF_16LE)
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(0, result.reviews.size)
    }

    @Test
    fun `developer reply is populated when present`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(
                reviewId = "abc-123",
                replyText = "Thanks for the feedback!",
                replyDate = "2023-06-20T09:00:00Z"
            )
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        val reply = result.reviews[0].reply
        assertEquals("Thanks for the feedback!", reply?.text)
        assertEquals("2023-06-20T09:00:00Z", reply?.lastModified)
    }

    @Test
    fun `reviews from multiple monthly files are combined`() = runBlocking {
        writeCsv("com.example.app", "202305", listOf(FixtureRow(reviewId = "may-1")))
        writeCsv("com.example.app", "202306", listOf(FixtureRow(reviewId = "jun-1")))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(2, result.reviews.size)
    }

    @Test
    fun `files for a different package are not included`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(FixtureRow(reviewId = "abc-123")))
        writeCsv("com.other.app", "202306", listOf(FixtureRow(reviewId = "other-1")))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app")
        assertEquals(1, result.reviews.size)
        assertEquals("abc-123", result.reviews[0].reviewId)
    }
```

- [ ] **Step 2: Run the tests to confirm they fail**

```bash
./gradlew test --tests "com.googleplayreviews.CsvReviewRepositoryTest"
```
Expected: multiple failures — `AssertionError: expected 2 but was 0` etc.

- [ ] **Step 3: Implement full CSV parsing in CsvReviewRepository**

Replace `CsvReviewRepository.kt` entirely:
```kotlin
package com.googleplayreviews

import com.googleplayreviews.models.DeviceInfo
import com.googleplayreviews.models.DeveloperReply
import com.googleplayreviews.models.Review
import com.googleplayreviews.models.ReviewsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.time.LocalDate

class CsvReviewRepository(private val reviewsDir: File) : HistoricalReviewSource {

    override suspend fun listReviews(
        packageName: String,
        startDate: String? = null,
        endDate: String? = null,
        limit: Int = 100,
        language: String? = null,
        unansweredOnly: Boolean = false,
        searchText: String? = null
    ): ReviewsPage = withContext(Dispatchers.IO) {
        val start = startDate?.let { LocalDate.parse(it) }
        val end = endDate?.let { LocalDate.parse(it) }

        val files = reviewsDir.listFiles { f ->
            f.name.matches(Regex("reviews_${Regex.escape(packageName)}_\\d{6}\\.csv"))
        }?.sortedBy { it.name } ?: emptyList()

        val accumulated = mutableListOf<Review>()

        for (file in files) {
            val yyyymm = file.nameWithoutExtension.substringAfterLast("_")
            if (!monthInRange(yyyymm, start, end)) continue

            val rows = parseCsv(file)
            val reviews = rows.mapNotNull { mapRow(it) }
                .filter { rowInDateRange(it.createTime, start, end) }

            accumulated.addAll(ReviewService.applyFilters(reviews, language, unansweredOnly, searchText))

            if (limit > 0 && accumulated.size >= limit) {
                return@withContext ReviewsPage(accumulated.take(limit), nextPageToken = null)
            }
        }

        ReviewsPage(accumulated, nextPageToken = null)
    }

    private fun monthInRange(yyyymm: String, start: LocalDate?, end: LocalDate?): Boolean {
        val year = yyyymm.substring(0, 4).toInt()
        val month = yyyymm.substring(4, 6).toInt()
        val monthStart = LocalDate.of(year, month, 1)
        val monthEnd = monthStart.plusMonths(1).minusDays(1)
        if (start != null && monthEnd.isBefore(start)) return false
        if (end != null && monthStart.isAfter(end)) return false
        return true
    }

    private fun rowInDateRange(createTime: String, start: LocalDate?, end: LocalDate?): Boolean {
        if (start == null && end == null) return true
        return try {
            val date = LocalDate.parse(createTime.substringBefore("T"))
            (start == null || !date.isBefore(start)) && (end == null || !date.isAfter(end))
        } catch (e: Exception) {
            false
        }
    }

    private fun parseCsv(file: File): List<Map<String, String>> {
        val content = file.readText(Charsets.UTF_16)
        val lines = content.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return emptyList()
        val headers = parseCsvRow(lines[0])
        return lines.drop(1).mapNotNull { line ->
            val values = parseCsvRow(line)
            if (values.size == headers.size) headers.zip(values).toMap() else null
        }
    }

    private fun parseCsvRow(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun mapRow(row: Map<String, String>): Review? {
        val reviewId = extractReviewId(row["Review Link"].orEmpty()) ?: return null
        val replyText = row["Developer Reply Text"].orEmpty()
        val replyDate = row["Developer Reply Date and Time"].orEmpty()
        return Review(
            reviewId = reviewId,
            authorName = "Unknown",
            rating = row["Star Rating"]?.toIntOrNull() ?: 0,
            reviewerLanguage = row["Reviewer Language"].orEmpty(),
            createTime = row["Review Submit Date and Time"].orEmpty(),
            updateTime = row["Review Last Update Date and Time"].orEmpty(),
            originalText = row["Review Text"].orEmpty(),
            deviceInfo = row["Device"]?.takeIf { it.isNotBlank() }?.let { DeviceInfo(model = it) },
            reply = if (replyText.isNotBlank()) DeveloperReply(text = replyText, lastModified = replyDate) else null
        )
    }

    private fun extractReviewId(reviewLink: String): String? {
        if (reviewLink.isBlank()) return null
        return try {
            URI(reviewLink).query
                ?.split("&")
                ?.firstOrNull { it.startsWith("reviewId=") }
                ?.substringAfter("reviewId=")
        } catch (e: Exception) {
            null
        }
    }
}
```

- [ ] **Step 4: Run the tests to confirm they pass**

```bash
./gradlew test --tests "com.googleplayreviews.CsvReviewRepositoryTest"
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/CsvReviewRepository.kt \
        src/test/kotlin/com/googleplayreviews/CsvReviewRepositoryTest.kt
git commit -m "feat: implement CsvReviewRepository CSV parsing and row mapping"
```

---

### Task 3: CsvReviewRepository — date range filtering

**Files:**
- Modify: `src/test/kotlin/com/googleplayreviews/CsvReviewRepositoryTest.kt`

(No changes to CsvReviewRepository — the implementation from Task 2 already includes `monthInRange` and `rowInDateRange`. These tests verify that logic.)

- [ ] **Step 1: Write failing tests for date range filtering**

Add these tests to `CsvReviewRepositoryTest`:
```kotlin
    @Test
    fun `startDate excludes reviews before that date`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(reviewId = "old", submitDate = "2023-06-01T00:00:00Z"),
            FixtureRow(reviewId = "new", submitDate = "2023-06-20T00:00:00Z")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", startDate = "2023-06-15")
        assertEquals(1, result.reviews.size)
        assertEquals("new", result.reviews[0].reviewId)
    }

    @Test
    fun `endDate excludes reviews after that date`() = runBlocking {
        writeCsv("com.example.app", "202306", listOf(
            FixtureRow(reviewId = "early", submitDate = "2023-06-05T00:00:00Z"),
            FixtureRow(reviewId = "late", submitDate = "2023-06-25T00:00:00Z")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", endDate = "2023-06-10")
        assertEquals(1, result.reviews.size)
        assertEquals("early", result.reviews[0].reviewId)
    }

    @Test
    fun `file outside date range is skipped entirely`() = runBlocking {
        writeCsv("com.example.app", "202301", listOf(FixtureRow(reviewId = "jan")))
        writeCsv("com.example.app", "202306", listOf(FixtureRow(reviewId = "jun")))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", startDate = "2023-06-01", endDate = "2023-06-30")
        assertEquals(1, result.reviews.size)
        assertEquals("jun", result.reviews[0].reviewId)
    }

    @Test
    fun `limit stops reading after enough reviews are collected`() = runBlocking {
        writeCsv("com.example.app", "202305", listOf(
            FixtureRow(reviewId = "r1"), FixtureRow(reviewId = "r2"), FixtureRow(reviewId = "r3")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", limit = 2)
        assertEquals(2, result.reviews.size)
        assertNull(result.nextPageToken)
    }

    @Test
    fun `limit zero returns all reviews`() = runBlocking {
        writeCsv("com.example.app", "202305", listOf(
            FixtureRow(reviewId = "r1"), FixtureRow(reviewId = "r2"), FixtureRow(reviewId = "r3")
        ))
        val repo = CsvReviewRepository(tempDir)
        val result = repo.listReviews("com.example.app", limit = 0)
        assertEquals(3, result.reviews.size)
    }
```

- [ ] **Step 2: Run the tests to confirm they pass (logic already implemented)**

```bash
./gradlew test --tests "com.googleplayreviews.CsvReviewRepositoryTest"
```
Expected: `BUILD SUCCESSFUL`. If any fail, fix `CsvReviewRepository.monthInRange` or `rowInDateRange` before continuing.

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/com/googleplayreviews/CsvReviewRepositoryTest.kt
git commit -m "test: add date range and limit tests for CsvReviewRepository"
```

---

### Task 4: McpServer — register list_historical_reviews tool

**Files:**
- Modify: `src/main/kotlin/com/googleplayreviews/McpServer.kt`

- [ ] **Step 1: Update `McpServer.create` signature to accept nullable HistoricalReviewSource**

In `McpServer.kt`, change the `create` function signature and body:
```kotlin
    fun create(reviewService: ReviewService, historicalSource: HistoricalReviewSource? = null): Server {
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
        if (historicalSource != null) registerListHistoricalReviews(server, historicalSource)
        return server
    }
```

- [ ] **Step 2: Add `registerListHistoricalReviews`**

Add this private function to `McpServer.kt` (after `registerDeleteReply`):
```kotlin
    private fun registerListHistoricalReviews(server: Server, source: HistoricalReviewSource) {
        server.addTool(
            name = "list_historical_reviews",
            description = "Fetch reviews from locally-downloaded Google Play CSV reports. " +
                "Reads monthly CSV files from the PLAY_REVIEWS_DIR directory. " +
                "Supports date range filtering (startDate/endDate) in addition to the same " +
                "filters available on list_reviews. Returns a ReviewsPage with nextPageToken always null.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") {
                        put("type", "string")
                        put("description", "App package name, e.g. com.example.app")
                    }
                    putJsonObject("startDate") {
                        put("type", "string")
                        put("description", "Inclusive start date in ISO format, e.g. 2023-01-01")
                    }
                    putJsonObject("endDate") {
                        put("type", "string")
                        put("description", "Inclusive end date in ISO format, e.g. 2023-12-31")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Max reviews to return (default 100); 0 means no limit")
                    }
                    putJsonObject("language") {
                        put("type", "string")
                        put("description", "Filter by BCP-47 reviewer language")
                    }
                    putJsonObject("unansweredOnly") {
                        put("type", "boolean")
                        put("description", "If true, only return reviews with no developer reply")
                    }
                    putJsonObject("searchText") {
                        put("type", "string")
                        put("description", "Case-insensitive substring match against review text")
                    }
                },
                required = listOf("packageName")
            )
        ) { request ->
            val args = request.arguments
            safeToolCall("list_historical_reviews") {
                val packageName = args.requireString("packageName")
                    ?: return@safeToolCall errorResult("packageName is required")
                val page = source.listReviews(
                    packageName = packageName,
                    startDate = args.requireString("startDate"),
                    endDate = args.requireString("endDate"),
                    limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 100,
                    language = args.requireString("language"),
                    unansweredOnly = args["unansweredOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    searchText = args.requireString("searchText")
                )
                textResult(json.encodeToString<ReviewsPage>(page))
            }
        }
    }
```

- [ ] **Step 3: Build to verify it compiles**

```bash
./gradlew build
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Run the full test suite**

```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/McpServer.kt
git commit -m "feat: register list_historical_reviews tool in McpServer"
```

---

### Task 5: Main.kt — wire PLAY_REVIEWS_DIR env var

**Files:**
- Modify: `src/main/kotlin/com/googleplayreviews/Main.kt`

- [ ] **Step 1: Update Main.kt to read PLAY_REVIEWS_DIR and instantiate CsvReviewRepository**

Replace `Main.kt` entirely:
```kotlin
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
```

- [ ] **Step 2: Build the fat jar**

```bash
./gradlew jar
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Run the full test suite one final time**

```bash
./gradlew test
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/googleplayreviews/Main.kt
git commit -m "feat: wire PLAY_REVIEWS_DIR env var to enable list_historical_reviews"
```

---

## Usage

Set `PLAY_REVIEWS_DIR` in the MCP client config to the directory containing your downloaded CSV files:

```json
{
  "env": {
    "PLAY_REVIEWS_DIR": "/path/to/downloaded/reviews"
  }
}
```

CSV files must follow the Google Play naming convention: `reviews_<packageName>_<YYYYMM>.csv` (UTF-16LE with BOM, as produced by `gsutil cp gs://pubsite_prod_rev_<bucket>/reviews/ .`).
