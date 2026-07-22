# An MCP server to read and reply to Google Play Reviews

A local MCP (Model Context Protocol) server that lets an LLM read and reply to Google Play Store
reviews via the Play Developer API (`androidpublisher`). Works with any app you have Play Console
access to — every tool takes `packageName` as a parameter, so one running server can serve reviews
across multiple apps.

NOTE: Google's APIs truly suck - this one only returns reviews from the last 7 days. They have some weak justification, but it's really just crap on their part. They make little effort to document it, but there is this:

https://support.google.com/googleplay/android-developer/thread/239806165/google-play-api-list-reviews-only-returns-last-7-days-of-reviews-and-does-not-return-pagination

That said, if you can obtain the review ID you can still use the API to read it and to post replies even to old reviews. The MCP server can deal with this by being pointed at a dump of the csv files you can download from the Play Store. But to be honest the LLM can do a reasonable job if you just tell it where the dump is - it'll do all the grepping itself without the need for a server.

## Requirements

- JVM 17+ (it's Kotlin)
- A Google Cloud service account with access to the Play Developer API for your app(s)

## Building

```bash
./gradlew jar
```

This produces a self-contained fat jar at `build/libs/googleplayreviewsmcp-1.0.0.jar` (the `jar`
task bundles the runtime classpath — no shadow/shadowJar plugin needed).

## Google Cloud / Play Console setup

1. In Google Cloud Console, create (or reuse) a project and a service account with the
   `androidpublisher` API enabled.
2. Download a JSON key for that service account.
3. In Google Play Console → **Users and permissions**, invite the service account's `client_email`
   as a user, and grant it at least the **Reply to reviews** permission for the relevant app(s).
4. Save the downloaded JSON key somewhere private, e.g. `~/.credentials/<app>-service-account.json`.
   Keep it out of version control and don't paste its contents into a shell history file.

## Configuration

The server reads two environment variables:

| Variable | Required | Purpose |
|---|---|---|
| `GOOGLE_SERVICE_ACCOUNT_JSON` | Yes | The **full contents** of the service account JSON key (not a file path). |
| `PLAY_REVIEWS_DIR` | No | Path to a directory of Play Console CSV review exports. When set, enables the `list_historical_reviews` tool for reviews older than the API's 7-day window. |

`GOOGLE_SERVICE_ACCOUNT_JSON` must be the raw JSON text, so if you're wiring this into an MCP
client config (a JSON file itself), the key's JSON ends up embedded as a JSON string value —
most MCP clients handle escaping this automatically when you paste/generate the config
programmatically.

### `PLAY_REVIEWS_DIR` CSV format

Files must be named `reviews_<packageName>_<YYYYMM>.csv` (e.g.
`reviews_com.example.app_202501.csv`) and use the standard column layout from Play Console's
**Download reviews as CSV** feature (UTF-16LE encoded, as Google exports them). Historical review
support only reads files matching the requested `packageName`.

## Installing as an MCP server (Claude Code)

Register it once, at user scope, so it's available across all your projects:

```bash
claude mcp add-json google-play-reviews --scope user "$(python3 -c '
import json
with open("/path/to/service-account.json") as f:
    creds = f.read()
print(json.dumps({
    "type": "stdio",
    "command": "java",
    "args": ["-jar", "/path/to/googleplayreviewsmcp/build/libs/googleplayreviewsmcp-1.0.0.jar"],
    "env": {"GOOGLE_SERVICE_ACCOUNT_JSON": creds}
}))
')"
```

Substitute the real paths to your service account JSON and the built jar. Verify it connected:

```bash
claude mcp list
```

You should see `google-play-reviews` reported as `✔ Connected`. If the jar is rebuilt, no
reconfiguration is needed — the same path is re-executed on each server start.

## Tools exposed

- **`list_reviews`** — Fetch reviews for an app from the live Play Developer API (last ~7 days
  only). Auto-paginates server-side; supports `language`, `unansweredOnly`, and `searchText`
  filters (applied client-side) plus optional `translationLanguage`.
- **`get_review`** — Fetch a single review by ID.
- **`reply_to_review`** — Post or update the developer reply on a review.
- **`delete_reply`** — Delete the developer reply on a review.
- **`list_historical_reviews`** — Only available when `PLAY_REVIEWS_DIR` is set. Reads
  locally-downloaded CSV exports, supporting the same filters as `list_reviews` plus
  `startDate`/`endDate` range filtering. Useful for reviews older than the live API's window —
  the review IDs it returns work with `get_review`/`reply_to_review` too.

All tools take `packageName` (e.g. `com.example.app`) as their first argument, so a single running
server instance can be used to manage reviews for multiple apps.

## TODO

- Get reviews from Cloud storage instead of/in addition to manual CSV export
- Consider other distribution options besides a raw jar (e.g. a native binary)
