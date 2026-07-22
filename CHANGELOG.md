# Changelog

## 0.4.0 — unreleased

### Added

- **Optional server-side conversation log** (`conversation-log` section, **off by
  default**). One JSONL line per message: sanitized text as rendered, the players who
  could see the bubble at send time, world and position, ISO-8601 + epoch timestamps.
  With `log-admits: true` (default), players who come into range of a live bubble are
  recorded as admit events. Daily files under `plugins/ProxChat/conversation/`,
  flushed per line; `retention-days: 0` (default) keeps files forever, a positive
  value prunes older daily files by their filename date. Writing runs on a dedicated
  background thread and can never block, delay, or drop a bubble — under backpressure
  the log drops its own lines (counted, rate-limited warning) rather than touch chat.
  All keys apply live via `/proxchat reload`.

### Fixed

- Stopping the server with players online no longer logs a spurious
  `Error occurred while disabling ProxChat` — cleanup no longer tries to schedule
  tasks from a disabled plugin (bubbles were always non-persistent; the error was
  noise, not a leak).
- Per-viewer visibility updates now pre-check that the display entity is valid and
  region-reachable before scheduling, closing a rare cross-region error during
  stalled world transfers (the failure was already fail-safe: worst case a hide was
  skipped and the bubble faded naturally).
- The legacy formatting lead-in `§` (U+00A7) is now stripped by message sanitization —
  some client render paths honor §-codes even in raw display text, so it is never
  legitimate message content.

## 0.3.0 — 2026-07-17

- Initial public release: proximity speech bubbles for Folia/Paper with
  server-authoritative radius admission, three modes (`ON`/`OFF`/`SUPPRESSED`),
  a `ProxChatService` API for host plugins, hardened input sanitization,
  per-player rate limiting, live config reload, and crash-safe mode persistence.
