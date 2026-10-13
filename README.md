# Classic Engineering — the Switchyard modpack

A Tekkit Classic-inspired tech pack for **Minecraft 26.3 / NeoForge 26.3.0.65-beta**, played on the Switchyard server.

- Server: `mc.jorry.live` (already in the multiplayer list when you install the pack)
- Website, downloads and how to join: https://mc.jorry.live
- Current build: **26.3-0.1.0-test15**

This repository is here so you can see exactly what is in the pack before you install it: every mod, where it comes from,
and the full source or patch of everything we built ourselves. Nothing here is needed to play; use the downloads on the site.

## Downloads

The pack files for this build are attached to the matching GitHub release and served from the website:

- `Classic-Engineering-26.3-0.1.0-test15.mrpack` (27 MB)
- `Classic-Engineering-26.3-0.1.0-test15-CurseForge.zip` (77 MB)
- `Classic-Engineering-26.3-0.1.0-test15-Phone.mrpack` (26 MB)

Checksums: `pack/SHA256SUMS.txt`.

## What is in the repository

| Folder | Contents |
|---|---|
| `pack/` | `modrinth.index.json` (PC) and `modrinth.index-phone.json` (phone): the exact file lists with hashes and download URLs; `MOD-CREDITS.json`; `NOTICE.txt`; `manifest.json` (our build manifest); `servers.dat`; bundled licence files; the phone touch-layout kit |
| `mods/` | full source of the mods written for this pack (see below) |
| `ports/` | for each third-party mod we had to build ourselves for 26.3: the upstream repository and commit, its licence, and our complete patch |
| `server/` | the server icon and the server's mod list |
| `scripts/` | the scripts that build the packs, `servers.dat`, the server icon, the website's pack.json and this repository |

### Mods written for this pack

- `mods/ClassicCrates` (MIT): Switchyard menu, vote listener (Votifier v1/v2), crate keys, vote crates, 560 cosmetic skins
- `mods/ClassicCastleGuard` (MIT): spawn district protection, rented plots, growing land claims, /kit, /tpa, /vote, quarry placement rules
- `mods/ClassicSpruceNeedles` (MIT): falling spruce needle particles
- `mods/CommunityBridge` (MIT): server <-> website bridge: account linking, votes, chat relay, reward commands
- `mods/GoHomeAlready` (MIT (reconstructed from the official 1.2.0 binary, see SOURCE-PROVENANCE.txt)): /sethome, /home, /spawn with warm-up and cooldown

Each folder is a Gradle project (ModDevGradle, Java 25). They resolve a few privately built dependencies from a local Maven
folder (`../../maven` in our workspace); point `build.gradle` at your own copies of those jars to build them elsewhere.

### Third-party mods built here

Minecraft 26.3 is new, so several mods had no official 26.3 release when the pack was made. For those we checked out the
upstream source, ported it to NeoForge 26.3 and built it ourselves. The jars in the pack are therefore **unofficial
builds**: please do not report problems with them to the original authors. Every change is in `ports/<Mod>/<Mod>-26.3.patch`,
a `git diff --binary` against the listed upstream commit; `ports/<Mod>/UPSTREAM.md` has the details.

- `ports/AdditionalEnchantedMiner`: Additional Enchanted Miner (QuarryPlus), baseline https://github.com/Kotori316/QuarryPlus@2d4a3f0bb3, patch 116 files changed, 2205 insertions(+), 354 deletions(-)
- `ports/BuildingWands`: Building Wands, baseline https://github.com/nicguzzo/wands@1cfc884a1d, patch 65 files changed, 2096 insertions(+), 711 deletions(-)
- `ports/CBMultipart`: CB Multipart, baseline https://github.com/TheCBProject/CBMultipart@5d321534d4, patch 49 files changed, 466 insertions(+), 347 deletions(-)
- `ports/ClassicPipes`: Classic Pipes, baseline https://github.com/J4gm/classicpipes@ce4fbb4a3c, patch 70 files changed, 278 insertions(+), 149 deletions(-)
- `ports/CodeChickenLib`: CodeChicken Lib, baseline https://github.com/TheCBProject/CodeChickenLib@2fa08644d6, patch 85 files changed, 810 insertions(+), 667 deletions(-)
- `ports/EnderStorage`: Ender Storage, baseline https://github.com/TheCBProject/EnderStorage@4111fa4558, patch 30 files changed, 237 insertions(+), 155 deletions(-)
- `ports/FarmersDelight`: Farmer's Delight, baseline https://github.com/vectorwing/FarmersDelight@22fe987639, patch 1202 files changed, 13174 insertions(+), 11726 deletions(-)
- `ports/IronChests`: Iron Chests, baseline https://github.com/progwml6/ironchest@9174022fb1, patch 96 files changed, 1006 insertions(+), 510 deletions(-)
- `ports/Mekanism-26.3`: Mekanism + Mekanism Generators, baseline https://github.com/mekanism/Mekanism@feb19590eb, patch 30 files changed, 31 insertions(+), 247 deletions(-)
- `ports/ProjectE`: ProjectE, baseline https://github.com/sinkillerj/ProjectE@f432b0c668, patch 983 files changed, 8196 insertions(+), 8494 deletions(-)
- `ports/ProjectRed`: Project Red (core, transmission, integration, expansion, exploration, illumination), baseline https://github.com/MrTJP/ProjectRed@e631f983c9, patch 1742 files changed, 14462 insertions(+), 13542 deletions(-)
- `ports/Sodium-26.3-port`: Sodium (client only; Polyform Shield 1.0.0), baseline https://github.com/CaffeineMC/sodium@03198cb36a, patch 3 files changed, 7 insertions(+), 7 deletions(-)

Sodium is licensed under the Polyform Shield License 1.0.0; the bundled build is the official 0.9.2 source with two small
start-up patches (see `ports/Sodium-26.3-port`). Iris is the unmodified official release.

## Full mod list (47 mods)

| Mod | Version | Side | Where it comes from |
|---|---|---|---|
| Additional Enchanted Miner (QuarryPlus) | additionalenchantedminer-neoforge-26.3-26.20.159-port263.1 | client + server | built here from upstream: `ports/AdditionalEnchantedMiner` |
| Anti Xray | neoforge-1.4.16+26.3 | server | official release: https://cdn.modrinth.com/data/sml2FMaA/versions/ETqF9yGq/antixray-neoforge-1.4.16%2B26.3-all.jar |
| Applied Energistics 2 | 26.3.1-alpha | client + server | official release: https://cdn.modrinth.com/data/XxWD5pD3/versions/PDPQ4WfW/appliedenergistics2-26.3.1-alpha.jar |
| Architectury API | 22.0.5+neoforge | client + server | official release: https://cdn.modrinth.com/data/lhGA9TYQ/versions/dZ7iNvS4/architectury-neoforge-22.0.5.jar |
| Building Wands | wands-26.3-3.2.1-port263.1 | client + server | built here from upstream: `ports/BuildingWands` |
| CB Multipart | CBMultipart-26.3-3.6.0-port263.1 | client + server | built here from upstream: `ports/CBMultipart` |
| Chunky | 1.5.4 | server | official release: https://cdn.modrinth.com/data/fALzjamp/versions/EyCqftOK/Chunky-NeoForge-1.5.4.jar |
| Classic Crates (Switchyard menu, vote crates) | ClassicCrates-26.3-1.3.0 | client + server | written for this pack: `mods/ClassicCrates` |
| Classic Pipes | classicpipes-neoforge-26.3-1.1.7-port.26.3.1 | client + server | built here from upstream: `ports/ClassicPipes` |
| Classic Spruce Needles | ClassicSpruceNeedles-26.3-1.0.0-port263.1 | client + server | written for this pack: `mods/ClassicSpruceNeedles` |
| Classic Castle Guard | ClassicCastleGuard-26.3-1.9.0-port263.1 | server | written for this pack: `mods/ClassicCastleGuard` |
| Cloth Config | 26.3.159+neoforge | client + server | official release: https://cdn.modrinth.com/data/9s6osm5g/versions/GchRXPnb/cloth-config-neoforge-26.3.159.jar |
| Clumps | 26.3.2 | client + server | official release: https://cdn.modrinth.com/data/Wnxd13zP/versions/kV095inH/Clumps-neoforge-26.3-26.3.2.jar |
| CodeChicken Lib | CodeChickenLib-26.3-4.7.0-port263.1 | client + server | built here from upstream: `ports/CodeChickenLib` |
| Community Bridge | CommunityBridge-26.3-1.2.0 | server | written for this pack: `mods/CommunityBridge` |
| Ender Storage | EnderStorage-26.3-2.14.0-port263.1 | client + server | built here from upstream: `ports/EnderStorage` |
| Farmer's Delight | FarmersDelight-26.3-1.3.4-port263.1 | client + server | built here from upstream: `ports/FarmersDelight` |
| FerriteCore | 9.0.0-neoforge | client + server | official release: https://cdn.modrinth.com/data/uXXizFIs/versions/LtVvw4uS/ferritecore-9.0.0-neoforge.jar |
| Gohomealready | go_home_already-26.3-1.2.0-port263.1 | server | written for this pack: `mods/GoHomeAlready` |
| Gravestone | neoforge-1.0.41+26.3 | client + server | official release: https://cdn.modrinth.com/data/RYtXKJPr/versions/9Ns9qBAE/gravestone-neoforge-1.0.41%2B26.3.jar |
| GuideME | 26.3.3-alpha | client + server | official release: https://cdn.modrinth.com/data/Ck4E7v7R/versions/69ocM6po/guideme-26.3.3-alpha.jar |
| Iris Shaders | 1.11.7+26.3-neoforge | client | official release: https://cdn.modrinth.com/data/YL57xq9U/versions/Ag5S66CF/iris-neoforge-1.11.7%2Bmc26.3.jar |
| Iron Chests | ironchest-26.3-neoforge-16.7-port263.1 | client + server | built here from upstream: `ports/IronChests` |
| Jade | 26.3.5+neoforge | client + server | official release: https://cdn.modrinth.com/data/nvQzSEkH/versions/dVB8U7Td/Jade-mc26.3-NeoForge-26.3.5.jar |
| JamLib | 2.3.1+26.3.x | client + server | official release: https://cdn.modrinth.com/data/IYY9Siz8/versions/Rj4DDcb2/jamlib-neoforge-2.3.1%2B26.3.x.jar |
| Just Enough Items | 31.10.0.63 | client + server | official release: https://cdn.modrinth.com/data/u6dRKJwZ/versions/sG36f9tq/jei-26.3-neoforge-31.10.0.63.jar |
| Lithium | mc26.3-0.26.2-neoforge | server | official release: https://cdn.modrinth.com/data/gvQqBUqZ/versions/1rb9wI5K/lithium-neoforge-0.26.2%2Bmc26.3.jar |
| Mekanism | Mekanism-26.3-10.8.0.homebaked | client + server | built here from upstream: `ports/Mekanism-26.3` |
| Mekanism Generators | MekanismGenerators-26.3-10.8.0.homebaked | client + server | built here from upstream: `ports/Mekanism-26.3` |
| Mezz Config | 0.6.8 | client + server | official release: https://cdn.modrinth.com/data/7tEfOcA7/versions/WFeT02Wv/mezz_config-26.3-neoforge-0.6.8-standalone.jar |
| Open Parties and Claims | neoforge-26.3-0.32.8 | client + server | official release: https://cdn.modrinth.com/data/gF3BGWvG/versions/qY3IicVC/open-parties-and-claims-neoforge-26.3-0.32.8.jar |
| Project Red: Core | ProjectRed-26.3-5.0-port263.1-core | client + server | built here from upstream: `ports/ProjectRed` |
| Project Red: Expansion | ProjectRed-26.3-5.0-port263.1-expansion | client + server | built here from upstream: `ports/ProjectRed` |
| Project Red: Exploration | ProjectRed-26.3-5.0-port263.1-exploration | client + server | built here from upstream: `ports/ProjectRed` |
| Project Red: Illumination | ProjectRed-26.3-5.0-port263.1-illumination | client + server | built here from upstream: `ports/ProjectRed` |
| Project Red: Integration | ProjectRed-26.3-5.0-port263.1-integration | client + server | built here from upstream: `ports/ProjectRed` |
| Project Red: Transmission | ProjectRed-26.3-5.0-port263.1-transmission | client + server | built here from upstream: `ports/ProjectRed` |
| ProjectE | projecte-1.1.0-port26.3-alpha.1 | client + server | built here from upstream: `ports/ProjectE` |
| Scalable Cat's Force | 6.1.1-mc26.3-3.9.0-neoforge | client + server | official release: https://cdn.modrinth.com/data/zr0QMQMo/versions/RrcS9php/ScalableCatsForce-NeoForge-6.1.1-mc26.3-3.9.0-all.jar |
| Servercore | 1.5.20+26.3 | server | official release: https://cdn.modrinth.com/data/4WWQxlQP/versions/bfczZvad/servercore-neoforge-1.5.20%2B26.3.jar |
| Simple Voice Chat | neoforge-2.6.24+26.3 | client + server | official release: https://cdn.modrinth.com/data/9eGKb6K1/versions/3rpCvvMZ/voicechat-neoforge-2.6.24%2B26.3.jar |
| Simple Backups | 26.3.1 | server | official release: https://cdn.modrinth.com/data/fzSKSXVK/versions/AKMINm7t/SimpleBackups-26.3.1.jar |
| Sodium | sodium-neoforge-0.9.2-SNAPSHOT+mc26.3-local | client | built here from upstream: `ports/Sodium-26.3-port` |
| Sort It Out! | 1.5.0+26.3.x | client + server | official release: https://cdn.modrinth.com/data/jcOSOvm1/versions/PaKIr2pu/sort_it_out-neoforge-1.5.0%2B26.3.x.jar |
| Spark | 1.10.187-neoforge | client + server | official release: https://cdn.modrinth.com/data/l6YH9Als/versions/HwHxykP9/spark-1.10.187-neoforge.jar |
| Xaero's Minimap | neoforge-26.3-26.6.0 | client + server | official release: https://cdn.modrinth.com/data/1bokaNcj/versions/j2Xo3Dhc/xaerominimap-neoforge-26.3-26.6.0.jar |
| Xaero's World Map | neoforge-26.3-1.47.0 | client + server | official release: https://cdn.modrinth.com/data/NcUtCpym/versions/p5t2Xn8F/xaeroworldmap-neoforge-26.3-1.47.0.jar |

## Licences

Every mod keeps its own licence: see `pack/licenses/`, `pack/MOD-CREDITS.json` and `ports/<Mod>/UPSTREAM-LICENSE*`. The mods in
`mods/` are MIT licensed (the LICENSE file in each folder). The pack layout, scripts and this text are MIT as well.
Minecraft is a trademark of Mojang/Microsoft; this pack is not affiliated with or endorsed by Mojang, NeoForged or any mod author.

## Building the packs

```
python scripts/build-mrpack-20261011.py          # PC .mrpack + CurseForge zip
python scripts/build-mrpack-20261011.py --phone  # phone .mrpack
```

The scripts expect the workspace layout of the server team (private jars under `pack-26.3-private/client/mods`); they are
published so the pack build is reproducible and inspectable, not as a turnkey tool.
