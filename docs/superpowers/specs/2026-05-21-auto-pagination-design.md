# Auto-Pagination for list_reviews — Design Spec

**Date:** 2026-05-21  
**Status:** Approved

---

## Overview

The Google Play Developer API returns ~12 reviews per page regardless of any per-page size parameter. The existing `list_reviews` tool returns a single page and exposes a `pageToken` for manual pagination, requiring the caller to loop. This spec adds automatic pagination inside the server so callers get up to `limit` filtered reviews in a single tool call.

---

## Change: `list_reviews` parameter update

### Remove

`maxResults` — this parameter was passed to the API as a per-page size hint, but the API ignores it and always returns ~12 results per page. Keeping it would cause confusion alongside the new `limit` parameter.

### Add

`limit` (int, optional, default 100) — the maximum number of **filtered** reviews to return across all API pages. The server fetches pages in a loop until it has accumulated `limit` filtered results or exhausts all available pages.

- `limit = 0` means no limit: fetch all available pages.
- Counts **after** client-side filters are applied (`language`, `unansweredOnly`, `searchText`). For example, `limit=50, unansweredOnly=true` returns up to 50 unanswered reviews, fetching as many API pages as needed to find them.
- If the loop stops early because `limit` was reached and more API pages exist, the response includes the last `nextPageToken` so the caller can resume manually.
- If all pages are exhausted before reaching `limit`, `nextPageToken` is `null`.

### Updated parameter table

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `packageName` | string | Yes | App package name |
| `pageToken` | string | No | Resume from a prior position in the API's page sequence |
| `limit` | int | No | Max filtered reviews to return across all pages (default 100, 0 = no limit) |
| `language` | string | No | Filter by BCP-47 reviewer language (client-side) |
| `unansweredOnly` | boolean | No | Only return reviews with no developer reply (client-side) |
| `searchText` | string | No | Case-insensitive substring match on original and translated text (client-side) |
| `translationLanguage` | string | No | Translate review text to this language |

---

## Implementation: `ReviewService.listReviews`

Replace the single-page fetch with a loop:

```
accumulated = []
currentToken = pageToken  // from caller; null means start from beginning

loop:
    fetch page from API using currentToken
    map API reviews to domain reviews
    apply filters
    append filtered results to accumulated
    if limit > 0 and accumulated.size >= limit:
        trim accumulated to limit
        set nextPageToken = page's nextPageToken (may be non-null)
        break
    if page has no nextPageToken:
        set nextPageToken = null
        break
    currentToken = page's nextPageToken

return ReviewsPage(reviews = accumulated, nextPageToken = nextPageToken)
```

---

## Impact on MCP tool schema

`McpServer.kt` must update the `list_reviews` tool `inputSchema`:
- Remove `maxResults` property and its description
- Add `limit` property: `type: integer`, description: `"Max filtered reviews to return across all API pages (default 100, 0 = no limit)"`

---

## Error handling

No new error cases. If an API call fails mid-loop, the existing `runCatching` in the tool handler catches it and returns an error result. Partial results accumulated before the failure are discarded.

---

## Testing

Add unit tests to `ReviewServiceFilterTest` (or a new `ReviewServicePaginationTest`) covering the pagination loop logic. Since the actual API loop can't be unit-tested without a real connection, test the accumulation and limit-trimming logic by extracting it into a pure function.

Specifically, a `paginateAndFilter` (or equivalent) pure function that takes:
- A list of pre-fetched pages (each a `List<Review>` with an optional next token)
- `limit`
- Filter parameters

Returns the accumulated result. This function is fully unit-testable without any API calls.

---

## Files changed

| File | Change |
|------|--------|
| `src/main/kotlin/com/googleplayreviews/ReviewService.kt` | Replace single-page fetch with pagination loop; remove `maxResults`, add `limit` |
| `src/main/kotlin/com/googleplayreviews/McpServer.kt` | Update `list_reviews` inputSchema: remove `maxResults`, add `limit` |
| `src/test/kotlin/com/googleplayreviews/ReviewServicePaginationTest.kt` | New unit tests for pagination accumulation and limit logic |
