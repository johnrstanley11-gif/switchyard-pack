# Community Bridge for NeoForge 26.3

This is a server-only mod for the current Classic Engineering server, Minecraft **26.3**, NeoForge **26.3.0.65-beta**, Java **25**. It registers no blocks, items, client screens or network payloads. Players keep their existing client pack. `displayTest="IGNORE_SERVER_VERSION"` declares the server-only compatibility intent. It is not a Fabric mod or Paper plugin: those loaders cannot be dropped into the current NeoForge server. A Fabric/Paper port would reuse the HTTP contract while replacing loader events.

The delivered JAR is `build/libs/CommunityBridge-26.3-1.0.0.jar`. Building this project does not install it or alter the existing server.

## Build

Install JDK 25. The included Gradle wrapper downloads Gradle 9.6.1 and verifies its official distribution SHA-256 (`9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14`). No global Gradle installation is needed. With the existing workspace JDK/cache, run from the repository root:

```powershell
$env:JAVA_HOME = (Resolve-Path 'ports/minecraft-26.3/runtime/jdk-25.0.4.1+1').Path
$env:GRADLE_USER_HOME = (Resolve-Path 'ports/minecraft-26.3/gradle-home-pipes').Path
& 'server-mod/gradlew.bat' -p server-mod build
```

For a standalone checkout, with `JAVA_HOME` pointing to JDK 25:

```sh
cd server-mod
./gradlew build
```

On Windows run `gradlew.bat build` instead. The build pins ModDevGradle 2.0.148 and the NeoForge version in `gradle.properties`. Its first build downloads Mojang and NeoForge dependencies. The workspace contains the reusable dependency cache; subsequent builds can use `--offline` once resolved. The wrapper checksum comes from [Gradle's official distribution checksum](https://services.gradle.org/distributions/gradle-9.6.1-bin.zip.sha256). See the [official NeoForge mod setup documentation](https://docs.neoforged.net/docs/gettingstarted/) and [mod metadata reference](https://docs.neoforged.net/docs/gettingstarted/modfiles/).

## Install and configure

1. Back up the Minecraft world and configuration. Stop the target server during installation.
2. Copy the JAR to that server's `mods/` directory. **Do not install it on clients.**
3. Copy `config/communitybridge.properties.example` to the server's `config/communitybridge.properties`.
4. Use `online-mode=true` in the Minecraft server configuration. The bridge trusts the authenticated server's player UUID; offline-mode servers allow identity impersonation and do not prove ownership of a premium Minecraft account. If a proxy terminates authentication, configure and protect its authenticated forwarding separately.
5. Set `websiteUrl` to the public HTTPS site origin, `serverId=main` to match `SERVER_IDS`, and `apiToken` to the same long random token as `SERVER_API_TOKEN` in the website environment. Set `enabled=true` and restart.

Generate a token with `php -r "echo bin2hex(random_bytes(32)), PHP_EOL;"`. Never put real tokens in source control, chat, public URLs or command templates. No RCON credentials or direct MySQL access are needed from the mod. `websiteUrl` may include the site's base path but must not contain user information, query strings or fragments. HTTPS is required except loopback; `allowInsecureHttp=true` is available for a private development network only.

`allowedCommandRoots=crates,give,lp` allows only those first command words. Add an installed rank mod's command root explicitly before using it. Commands are already validated and rendered by the website; only site Admins edit templates. This allowlist is a second boundary, not a replacement for administrator trust. Reward command bodies may not contain control characters or exceed 512 characters.

## Linking and playtime

A player signs in with Discord on the website, requests a link code on **My account**, then types `/link CODE` while logged into the Minecraft server. The command sends the player object's UUID and current name; a user cannot supply a UUID argument. The code expires after ten minutes, is stored hashed, and is atomically consumed by the website. The command is rate limited locally to one attempt per five seconds, and the API is independently rate limited. HTTP runs on the bridge worker; results are sent to the player on the server thread.

Each Minecraft login creates a fresh random `session_id`. Join, one-minute heartbeat and leave events contain:

```json
{
  "server_id": "main",
  "session_id": "8af8490e-09d1-48c1-903e-e13e737c57df",
  "uuid": "380df991-f603-344c-a090-369bad2a924a",
  "username": "ExamplePlayer",
  "event": "heartbeat",
  "sequence": 2,
  "occurred_at": "2026-10-11T12:00:00Z"
}
```

Session IDs need not survive a restart: queued events preserve their original IDs, and subsequent logins get new IDs. Sequence values strictly increase within each session. A join establishes the baseline and credits no time. The website rejects future timestamps and identity changes, ignores replayed or out-of-order sequences, and caps each newly confirmed interval at 90 seconds. A missing join establishes a new baseline without retroactive credit. Sessions silent for 150 seconds are shown offline; the cron job closes them at their last confirmed heartbeat. A crash therefore loses an unconfirmed tail instead of crediting the entire downtime. AFK time counts as connected time.

Daily rollups split intervals at **UTC midnight**. Last 30 days means an exact rolling window; this week begins Monday at 00:00 UTC. Total time is the sum of confirmed intervals. Keep both hosts' clocks synchronized. Run the website cron every minute; see [website setup](../web/README.md).

The worker persists events in `<world>/communitybridge/outbox.json`, retries with backoff up to 60 seconds, and limits both the disk outbox and its coalesced in-memory pending queue to `maxOutboxEvents` (default 5,000 each). If a long outage or overload fills either queue, the oldest event is dropped and a warning is logged; the website can undercount that outage but cannot manufacture continuous playtime from it. Interactive link requests are limited to 32 queued/active jobs. Do not delete this directory when restarting. Filesystem failure is logged and can lose telemetry; command delivery is held before execution if its journal cannot be written.

## Rewards and crash recovery

The mod polls only for currently connected UUIDs, obtains command leases, then executes each command as the console **on the Minecraft server thread**. The website snapshots reward commands at purchase time and substitutes validated `{username}` and `{uuid}` values. The existing ClassicCrates mod accepts, for example:

```text
crates givekey {username} 1
```

Use the command supported by your installed permission mod for a Donator rank; `lp` is included in the default allowlist only as an option. The bridge does not install a permission mod. Prefer setting an existing rank or cosmetic entitlement rather than incrementing a consumable balance when an idempotent operation is available.

`<world>/communitybridge/deliveries.json` is an atomically replaced, flushed delivery journal. The worker writes **IN_PROGRESS before executing**; after a successful result it writes **DONE before sending the acknowledgment**. A repeated claim of a DONE ID only resends the acknowledgment. A known command failure is saved as FAILED and may retry under the website's maximum-attempt policy. A changed UUID or command for an existing ID is refused. Journal entries are not silently pruned; at `maxJournalEntries` new delivery is held until an administrator increases the limit.

If the server crashes between its journal write and the command's completion, the outcome is uncertain. Such an IN_PROGRESS entry is held automatically, even when its website lease expires. A database transaction cannot atomically commit an arbitrary Minecraft command and the world save: **exactly-once delivery across both systems is not guaranteed**. Even a flushed DONE entry cannot prove that a later world crash preserved an inventory change. Review server logs and the player's saved inventory/rank before resolving it.

From the **server console**:

```text
communitybridge status
communitybridge resolve 123 done
communitybridge resolve 123 retry
```

Use `done` after verifying the reward was granted; the next claim acknowledges without execution. Use `retry` only after verifying it was not granted; it removes the held journal entry so the website can lease it again. Recoveries are available to console sources only and only affect IN_PROGRESS entries. The website must still consider that command eligible for delivery; manually reconcile a queue row that has exhausted its attempts before expecting another claim. Keep world and journal backups together. Restoring only one side requires a reward audit.

## HTTP protocol

All endpoints are POST JSON and require `Authorization: Bearer SERVER_API_TOKEN`. Redirects are refused. Connect timeout is four seconds, total request/body timeout eight seconds, and responses are limited to 1 MiB. Work runs on one IO worker, with no HTTP on the tick thread.

| Endpoint | Request | Response |
| --- | --- | --- |
| `/api/server/link` | `{code,uuid,username}` | `{linked:true,user_id,uuid}` |
| `/api/server/heartbeat` | Event shown above | `{accepted,seconds}` |
| `/api/server/commands/claim` | `{server_id,online_uuids:[...]}` | `{commands:[{id,uuid,command,lease_token}]}` |
| `/api/server/commands/ack` | `{id,lease_token,success,error}` | `{acknowledged:true}` |

The API bearer token confers trusted server authority. Restrict the source IP at your reverse proxy when feasible, retain HTTPS, and rotate the token in both applications together. Do not expose a public endpoint that accepts players' self-reported sessions or votes.

## Optional integrations

The existing ClassicCrates Votifier listener continues to award its own vote keys. Community Bridge does not register a second vote listener or intercept its internal Java vote handler. A verified Votifier/bot adapter can forward vote events to the website's authenticated vote intake if configured; visit counting from a public browser is never proof of a vote. A Discord chat bot is separate from this mod; the website documents the optional bridge adapter.

## Verification limits

The JAR has been compiled against the exact current 26.3 NeoForge APIs. `./gradlew build` also runs 16 local checks covering disabled/no-secret defaults, HTTPS and token restrictions, atomic journal replacement, oversized HTTP response cancellation, and retained/retried telemetry against a temporary localhost HTTP 503/200 fixture. No real website, game server or payment provider is contacted. It has not been installed or executed against the live server. Before production, use a separate disposable server and Stripe test mode to test join/leave, successful linking, duplicate webhooks, command acknowledgment retry and the IN_PROGRESS recovery flow.
