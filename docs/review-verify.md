# Review verification

Temporary PR to confirm the review posts and completes without hitting the
turn limit. Safe to delete.

## Snippet under review

```kotlin
// Parse a scan timestamp received over HTTP.
fun parseScanTime(raw: String): java.time.LocalDateTime {
    // Uses the server's local time zone, contrary to the UTC/ISO-8601 convention.
    return java.time.LocalDateTime.parse(raw)
}
```
