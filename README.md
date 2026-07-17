# Proximity Chat Bubble

Proximity text chat for **Folia** and Paper servers — chat messages appear as floating
speech bubbles above the speaker's head, readable only by players nearby. No sender
names, no global broadcast, no chat logs.

## What it does

While the plugin is **ON**, regular chat no longer broadcasts to the whole server.
Each message becomes a text bubble mounted above the speaker, visible to anyone within
a configurable radius (default 24 blocks, same world only). Bubbles stack per player
(newest at the bottom), fade after a few seconds, and hide while the speaker sneaks.

## Features

- **True proximity** — admission is server-authoritative: clients beyond the radius
  never *receive* the bubble entity, so a modified client cannot read distant chat.
  The client-side view range is only a render cull on top of that guarantee.
- **Anonymous by design** — no sender name appears on any surface, and message content
  is never logged or written to disk. Bubbles are non-persistent display entities.
- **Fail-closed** — every failure path ends in "no bubble", never in leaked, frozen,
  or truncated text.
- **Three modes** — `ON` (bubbles replace chat), `OFF` (plugin inert, vanilla chat
  untouched), `SUPPRESSED` (chat swallowed, all bubbles cleared — for pauses or
  cutscenes). Switch live in-game, from console, or from another plugin via the API.
- **Folia-native** — fully region-thread-safe and declared `folia-supported`; built
  against the Folia scheduler APIs, which Paper also ships.
- **Hardened input** — Unicode NFC normalization; control, invisible, and bidi
  characters stripped; whitespace collapsed; a code-point length cap that rejects
  overlong messages instead of truncating them. Bubble text is rendered as a literal
  component, never parsed as markup.
- **Rate-limited** — a per-player minimum interval between accepted messages keeps
  entity spawns and packet fan-out bounded.
- **Live config** — `/proxchat reload` applies every value immediately. Out-of-range
  values are clamped with a console warning; a config typo never blocks enabling.
  The deployed `config.yml` is never written back by the plugin.

## Install

1. Drop `prox-chat-<version>.jar` into your server's `plugins/` folder.
2. Restart. The plugin starts **OFF** on first boot — enable it with `/proxchat on`.
   The mode persists across restarts.

Requires Folia or Paper **26.1.2 or newer** (Java 25 runtime).

## Commands

`/proxchat <status|on|off|suppress|clear|reload>` — permission `proxchat.admin`
(default: op). Replies are console-friendly one-liners.

| Subcommand | Effect |
|---|---|
| `status` | Current mode, live bubble counts, and a config summary |
| `on` / `off` / `suppress` | Switch mode (leaving `ON` clears every live bubble) |
| `clear` | Remove all live bubbles without changing the mode |
| `reload` | Re-read `config.yml` in place; lists any clamped values |

## Configuration

| Key (under `bubbles:`) | Default | What it does |
|---|---|---|
| `radius-blocks` | `24.0` | Server-side visibility radius in blocks (same world only) |
| `lifetime-seconds` | `8` | Seconds before a bubble fades |
| `max-per-player` | `3` | Stack depth per player; the oldest falls off the top |
| `height-above-head` | `1.2` | Height of the bubble stack above the player |
| `stack-spacing` | `0.30` | Vertical gap between stacked bubbles (blocks) |
| `hide-on-sneak` | `true` | Sneaking hides your bubbles; they return when you rise |
| `max-message-length` | `96` | Max length in code points, counted after sanitization |
| `line-width` | `200` | Client text-wrap width in pixels |
| `view-range` | `0.5` | Client render cull, as a fraction of the 64-block base |
| `min-message-interval-ms` | `750` | Per-player minimum interval between accepted messages |

Keys missing from a deployed config fall back to their defaults, so upgrading never
requires regenerating the file.

## API for other plugins

ProxChat registers a `ProxChatService` with Bukkit's `ServicesManager` on enable.
Declare `softdepend: [ProxChat]` and look it up lazily:

```java
ProxChatService prox = getServer().getServicesManager().load(ProxChatService.class);
if (prox != null) {
    prox.setMode(Mode.SUPPRESSED); // or enable() / suppress() / disable()
    prox.clearAll();               // remove every live bubble, mode untouched
}
```

Every method is callable from any thread; mode flips are atomic, and add the
prox-chat jar as `compileOnly` — the `…proxchat.api` package is the only intended
import surface.

## Known quirks (0.3.0)

- You can't see your **own** bubble in first person when looking straight up — the
  client hangs your own passenger off the head pivot. F5 and other players are
  unaffected.
- The speaker's vanilla nametag isn't drawn while a bubble is mounted (client
  behavior); it returns when the last bubble fades.
- The "message too long" reply shown to the sender is currently French; localizable
  messages are planned.
- Stopping the server while players are online can log one harmless
  `Error occurred while disabling ProxChat` (cleanup scheduling is refused during
  disable; bubbles are non-persistent, so nothing leaks). Fix planned for 0.4.0.

## Building

```
./gradlew build
```

Gradle wrapper included; the Java 25 toolchain is auto-provisioned. The jar lands in
`build/libs/`.

## License

[MIT](LICENSE)
