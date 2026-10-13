"""Assembles the public GitHub repository of the Classic Engineering pack (https://github.com/johnrstanley11-gif/switchyard-pack)
into ports/minecraft-26.3/github/switchyard-pack/ so players can read what is in the pack before downloading it:

  README.md            what the pack is, how to get it, the full mod table (official release or ported here, with upstream commit)
  pack/                the Modrinth pack indexes (PC and phone), MOD-CREDITS.json, NOTICE.txt, manifest.json, servers.dat,
                       the licence files bundled in the pack, the phone UI kit
  mods/<Name>/         full source of the mods written for this pack (Classic Crates, Classic Castle Guard, Classic Spruce
                       Needles, Community Bridge) and the reconstructed Go Home Already port (MIT)
  ports/<Mod>/         for every third-party mod we built ourselves: UPSTREAM.md (repository, baseline commit, licence) and
                       <Mod>-26.3.patch = the complete diff of our working tree against that commit (git diff --binary, with
                       untracked files included through a temporary index, so the real index is never touched)
  server/              server-icon.png and the server's mod list (no configs: they hold tokens and the world seed)
  scripts/             the build scripts (mrpack, pack.json for the site, servers.dat, server icon, this file)

It never deletes .git in the output folder, refuses to continue if a secret-looking value is found in the output, and
does not commit or push: do that by hand after reading the summary. Pack downloads are attached as GitHub release assets.
"""
from pathlib import Path
import fnmatch
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

PORT = Path(__file__).resolve().parents[1]
ROOT = PORT.parents[1]
PACK = PORT / "pack-26.3-private"
OUT = PORT / "github" / "switchyard-pack"
REPO_URL = "https://github.com/johnrstanley11-gif/switchyard-pack"
SITE = "https://mc.jorry.live"
SERVER = "mc.jorry.live"

# Our own mods (full source). name -> (source folder, licence summary, what it is)
OWN = {
    "ClassicCrates": (PORT / "sources/ClassicCrates", "MIT", "Switchyard menu, vote listener (Votifier v1/v2), crate keys, vote crates, 560 cosmetic skins"),
    "ClassicCastleGuard": (PORT / "sources/ClassicCastleGuard", "MIT", "spawn district protection, rented plots, growing land claims, /kit, /tpa, /vote, quarry placement rules"),
    "ClassicSpruceNeedles": (PORT / "sources/ClassicSpruceNeedles", "MIT", "falling spruce needle particles"),
    "CommunityBridge": (ROOT / "server-mod", "MIT", "server <-> website bridge: account linking, votes, chat relay, reward commands"),
    "GoHomeAlready": (PORT / "sources/GoHomeAlready", "MIT (reconstructed from the official 1.2.0 binary, see SOURCE-PROVENANCE.txt)", "/sethome, /home, /spawn with warm-up and cooldown"),
}
OWN_SKIP = {".gradle", "build", "run", "run-client", "run-server", "run-gametest", "run-data", "run-dev", "logs", ".port-logs"}
# Third-party mods we built: folder under sources/ -> (display name, licence file names, pack jar glob, extra git pathspec excludes)
PORTS = {
    "AdditionalEnchantedMiner": ("Additional Enchanted Miner (QuarryPlus)", ["LICENSE", "LICENSE.txt"], "additionalenchantedminer-*.jar", []),
    "BuildingWands": ("Building Wands", ["LICENSE", "LICENSE.txt"], "wands-*.jar", []),
    "CBMultipart": ("CB Multipart", ["LICENSE.txt", "LICENSE"], "CBMultipart-*.jar", []),
    "ClassicPipes": ("Classic Pipes", ["LICENSE", "LICENSE.txt", "LICENSE.md"], "classicpipes-*.jar", []),
    "CodeChickenLib": ("CodeChicken Lib", ["LICENSE.txt", "LICENSE"], "CodeChickenLib-*.jar", []),
    "EnderStorage": ("Ender Storage", ["LICENSE.txt", "LICENSE"], "EnderStorage-*.jar", []),
    "FarmersDelight": ("Farmer's Delight", ["LICENSE", "LICENSE.txt"], "FarmersDelight-*.jar", []),
    "IronChests": ("Iron Chests", ["LICENSE", "LICENSE.txt"], "ironchest-*.jar", []),
    "Mekanism-26.3": ("Mekanism + Mekanism Generators", ["LICENSE"], "Mekanism*-26.3-*.jar", []),
    "ProjectE": ("ProjectE", ["LICENSE", "LICENSE.txt"], "projecte-*.jar", [":!.port-logs", ":!port-status.json"]),
    "ProjectRed": ("Project Red (core, transmission, integration, expansion, exploration, illumination)", ["LICENSE.md", "LICENSE", "LICENSE.txt"], "ProjectRed-*.jar", [":!runtime", ":!*.log.*"]),
    "Sodium-26.3-port": ("Sodium (client only; Polyform Shield 1.0.0)", ["LICENSE.md", "LICENSE"], "sodium-*.jar", []),
}
COMMON_EXCLUDES = [":!run-*", ":!*.log", ":!logs", ":!build", ":!.gradle", ":!**/build/**"]
SCRIPTS = ["build-mrpack-20261011.py", "build-pack-site-json.py", "build-servers-dat.py", "build-server-icon.py", "build-github-repo.py"]
SECRET = re.compile(r"(?i)(token|secret|password|passwd|api[_-]?key|private[_-]?key)\s*[=:]\s*[\"']?([A-Za-z0-9+/_\-]{24,})")
SECRET_SKIP_SUFFIXES = {".png", ".jar", ".dat", ".ico", ".ogg", ".class"}


def run(args, cwd=None, env=None):
    return subprocess.run(args, cwd=cwd, env=env, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def load_module(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), PORT / "claude-session" / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def clean_output():
    OUT.mkdir(parents=True, exist_ok=True)
    for child in OUT.iterdir():
        if child.name == ".git":
            continue
        shutil.rmtree(child) if child.is_dir() else child.unlink()


def copy_tree(source, target, skip):
    for path in source.rglob("*"):
        rel = path.relative_to(source)
        if any(part in skip for part in rel.parts):
            continue
        if path.is_file():
            dest = target / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)


def excluded(path, excludes):
    """Untracked files left out of a patch: run folders, logs, build output, plus the port's own extra pathspecs."""
    parts = path.replace("\\", "/").split("/")
    name = parts[-1]
    if any(part.startswith("run-") or part in {"run", "logs", "build", ".gradle", ".port-logs"} for part in parts[:-1]):
        return True
    if name.endswith(".log") or ".log." in name:
        return True
    return any(fnmatch.fnmatch(path.replace("\\", "/"), e[2:] if e.startswith(":!") else e) or parts[0] == (e[2:] if e.startswith(":!") else e) for e in excludes)


def export_patch(folder, excludes):
    """Diff of the working tree (tracked changes + untracked files) against HEAD, through a temporary index."""
    source = PORT / "sources" / folder
    head = run(["git", "rev-parse", "HEAD"], cwd=source).strip()
    remote = run(["git", "remote", "get-url", "origin"], cwd=source).strip()
    if remote.endswith(".git"):
        remote = remote[:-4]
    with tempfile.TemporaryDirectory() as temp:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(temp) / "index"))
        run(["git", "read-tree", "HEAD"], cwd=source, env=env)
        untracked = [u for u in run(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=source).split("\x00") if u and not excluded(u, excludes)]
        for start in range(0, len(untracked), 150):             # explicit paths: "." would trip over ignored folders
            run(["git", "add", "--intent-to-add", "--"] + untracked[start:start + 150], cwd=source, env=env)
        patch = subprocess.run(["git", "diff", "HEAD", "--binary", "--no-color", "--", "."] + COMMON_EXCLUDES + excludes, cwd=source, env=env,
                               check=True, capture_output=True).stdout
        stat = run(["git", "diff", "HEAD", "--shortstat", "--", "."] + COMMON_EXCLUDES + excludes, cwd=source, env=env).strip()
    return head, remote, patch, stat


def pack_files():
    desktop = sorted((PACK / "dist").glob("Classic-Engineering-*.mrpack"), key=lambda p: p.stat().st_mtime)
    desktop = [p for p in desktop if not p.name.endswith("-Phone.mrpack")]
    version = re.search(r'^VERSION = "([^"]+)"', (PORT / "claude-session/build-mrpack-20261011.py").read_text(encoding="utf-8"), re.M).group(1)
    names = ["Classic-Engineering-%s.mrpack" % version, "Classic-Engineering-%s-CurseForge.zip" % version, "Classic-Engineering-%s-Phone.mrpack" % version]
    paths = [PACK / "dist" / n for n in names]
    for p in paths:
        if not p.is_file():
            raise SystemExit("Build the packs first: missing " + p.name)
    return version, paths


def write_pack_folder(version, paths):
    target = OUT / "pack"
    target.mkdir(parents=True)
    with zipfile.ZipFile(paths[0]) as desktop:
        for name in desktop.namelist():
            if name == "modrinth.index.json":
                (target / "modrinth.index.json").write_bytes(desktop.read(name))
            elif name.startswith("overrides/") and not name.startswith("overrides/mods/") and not name.endswith("/"):
                dest = target / name[len("overrides/"):]
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(desktop.read(name))
    with zipfile.ZipFile(paths[2]) as phone:
        (target / "modrinth.index-phone.json").write_bytes(phone.read("modrinth.index.json"))
        for name in ("overrides/config/fml.toml", "overrides/options.txt", "overrides/Switchyard-controls.json", "overrides/PHONE-CONTROLS-README.txt"):
            dest = target / "phone" / name[len("overrides/"):]
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(phone.read(name))
    shutil.copyfile(PACK / "manifest.json", target / "manifest.json")
    sums = []
    for p in paths:
        sums.append((p.with_name(p.name + ".sha256")).read_text(encoding="utf-8").strip())
    (target / "SHA256SUMS.txt").write_text("\n".join(sums) + "\n", encoding="utf-8")
    return sorted(str(p.relative_to(target)).replace("\\", "/") for p in target.rglob("*") if p.is_file())


def write_own_mods():
    rows = []
    for name, (source, licence, what) in OWN.items():
        target = OUT / "mods" / name
        copy_tree(source, target, OWN_SKIP)
        count = sum(1 for p in target.rglob("*") if p.is_file())
        rows.append((name, licence, what, count))
    return rows


def write_ports(manifest):
    rows = []
    jars = {Path(e["filename"]).name for e in manifest["mods"] if not e["origin"].startswith("official") and not e.get("heldBack")}
    for folder, (display, licence_names, jar_glob, excludes) in PORTS.items():
        source = PORT / "sources" / folder
        head, remote, patch, stat = export_patch(folder, excludes)
        target = OUT / "ports" / folder
        target.mkdir(parents=True)
        patch_name = folder + "-26.3.patch"
        (target / patch_name).write_bytes(patch)
        licence = next((source / n for n in licence_names if (source / n).is_file()), None)
        if licence:
            shutil.copyfile(licence, target / ("UPSTREAM-" + licence.name))
        in_pack = sorted(j for j in jars if Path(j).match(jar_glob))
        provenance = source / "SOURCE-PROVENANCE.txt"
        (target / "UPSTREAM.md").write_text(
            "# %s\n\n" % display +
            "- Upstream repository: %s\n- Baseline commit: `%s` (%s/commit/%s)\n" % (remote, head, remote, head) +
            "- Our changes: `%s` (%s), apply with `git apply --binary %s` on a checkout of that commit\n" % (patch_name, stat or "no tracked changes", patch_name) +
            "- Upstream licence: %s\n" % (("`UPSTREAM-" + licence.name + "`") if licence else "see the upstream repository") +
            "- Jar(s) in the pack: %s\n" % (", ".join("`%s`" % j for j in in_pack) or "none") +
            ("\n" + provenance.read_text(encoding="utf-8", errors="replace") if provenance.is_file() else "") +
            "\nThis is an unofficial build for Minecraft 26.3 / NeoForge 26.3.0.65-beta made for the Switchyard server. "
            "Do not report problems with it to the upstream authors.\n", encoding="utf-8")
        rows.append((display, remote, head, stat, len(patch), in_pack))
    return rows


def write_server():
    target = OUT / "server"
    target.mkdir(parents=True)
    shutil.copyfile(PACK / "server-icon.png", target / "server-icon.png")
    mods = sorted(p.name for p in (PACK / "server-prod/mods").glob("*.jar"))
    (target / "MODS.txt").write_text("\n".join(mods) + "\n", encoding="utf-8")
    (target / "README.md").write_text(
        "# Server\n\nThe Switchyard server runs NeoForge 26.3.0.65-beta with the mods in `MODS.txt` (the client packs plus server-only mods: "
        "Castle Guard, Community Bridge, Go Home Already, Lithium, Chunky, ServerCore, AntiXray, Simple Backups).\n\n"
        "`server-icon.png` is the icon the server sends to the multiplayer list; the packs' `servers.dat` carries the same image.\n\n"
        "Server configuration files are not published: they contain the world seed, API tokens and vote-listener keys. "
        "The gameplay rules they set are described on the website: %s/page/server-info\n" % SITE, encoding="utf-8")
    return mods


def write_scripts():
    target = OUT / "scripts"
    target.mkdir(parents=True)
    for name in SCRIPTS:
        shutil.copyfile(PORT / "claude-session" / name, target / name)


def mod_table(manifest, site):
    names = site.MODS
    lines = ["| Mod | Version | Side | Where it comes from |", "|---|---|---|---|"]
    for entry in sorted(manifest["mods"], key=lambda e: names.get(e["slug"], (e["slug"],))[0].lower()):
        if entry.get("heldBack"):
            continue
        display = names.get(entry["slug"], (entry["slug"].replace("-", " ").title(),))[0]
        side = {"both": "client + server", "client": "client", "server": "server"}[entry["side"]]
        if entry["origin"].startswith("official"):
            origin = "official release: %s" % entry["url"] if entry.get("url") else "official release"
        else:
            own = next((n for n in OWN if n.lower().replace("-", "") in entry["filename"].lower().replace("-", "").replace("_", "")), None)
            port = next((f for f, (_, _, glob, _) in PORTS.items() if Path(entry["filename"]).match(glob)), None)
            origin = "written for this pack: `mods/%s`" % own if own else ("built here from upstream: `ports/%s`" % port if port else "built here")
        lines.append("| %s | %s | %s | %s |" % (display, entry.get("version") or Path(entry["filename"]).stem, side, origin))
    return "\n".join(lines)


def write_readme(version, paths, manifest, site, own_rows, port_rows):
    files = "\n".join("- `%s` (%d MB)" % (p.name, round(p.stat().st_size / 1048576)) for p in paths)
    own = "\n".join("- `mods/%s` (%s): %s" % (n, l, w) for n, l, w, _ in own_rows)
    ports = "\n".join("- `ports/%s`: %s, baseline %s@%s, patch %s" % (f, d, r, h[:10], s or "no tracked changes") for f, (d, r, h, s, _, _) in zip(PORTS, port_rows))
    (OUT / "README.md").write_text(f"""# Classic Engineering — the Switchyard modpack

A Tekkit Classic-inspired tech pack for **Minecraft 26.3 / NeoForge 26.3.0.65-beta**, played on the Switchyard server.

- Server: `{SERVER}` (already in the multiplayer list when you install the pack)
- Website, downloads and how to join: {SITE}
- Current build: **{version}**

This repository is here so you can see exactly what is in the pack before you install it: every mod, where it comes from,
and the full source or patch of everything we built ourselves. Nothing here is needed to play; use the downloads on the site.

## Downloads

The pack files for this build are attached to the matching GitHub release and served from the website:

{files}

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

{own}

Each folder is a Gradle project (ModDevGradle, Java 25). They resolve a few privately built dependencies from a local Maven
folder (`../../maven` in our workspace); point `build.gradle` at your own copies of those jars to build them elsewhere.

### Third-party mods built here

Minecraft 26.3 is new, so several mods had no official 26.3 release when the pack was made. For those we checked out the
upstream source, ported it to NeoForge 26.3 and built it ourselves. The jars in the pack are therefore **unofficial
builds**: please do not report problems with them to the original authors. Every change is in `ports/<Mod>/<Mod>-26.3.patch`,
a `git diff --binary` against the listed upstream commit; `ports/<Mod>/UPSTREAM.md` has the details.

{ports}

Sodium is licensed under the Polyform Shield License 1.0.0; the bundled build is the official 0.9.2 source with two small
start-up patches (see `ports/Sodium-26.3-port`). Iris is the unmodified official release.

## Full mod list ({sum(1 for e in manifest["mods"] if not e.get("heldBack"))} mods)

{mod_table(manifest, site)}

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
""", encoding="utf-8")


def write_licence():
    (OUT / "LICENSE").write_text("""MIT License

Copyright (c) 2026 Switchyard / Classic Engineering

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

This licence covers the pack layout, scripts and documentation in this repository and the mods under mods/ (each has its
own copy). Third-party mods keep their own licences: see pack/licenses, pack/MOD-CREDITS.json and ports/*/UPSTREAM.md.
""", encoding="utf-8")
    (OUT / ".gitignore").write_text("*.jar\nbuild/\n.gradle/\nrun*/\nlogs/\n", encoding="utf-8")


def scan_secrets():
    hits = []
    for path in OUT.rglob("*"):
        if not path.is_file() or path.suffix.lower() in SECRET_SKIP_SUFFIXES or ".git" in path.parts:
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in SECRET.finditer(text):
            value = match.group(2)
            if value.lower() in {"ssecretkeyspec", "privatekeyfile"} or value.startswith(("get", "load", "this", "new")):
                continue
            hits.append("%s: %s" % (path.relative_to(OUT), match.group(0)[:80]))
    return hits


def main():
    site = load_module("build-pack-site-json.py")
    manifest = json.loads((PACK / "manifest.json").read_text(encoding="utf-8"))
    version, paths = pack_files()
    clean_output()
    pack_listing = write_pack_folder(version, paths)
    own_rows = write_own_mods()
    port_rows = write_ports(manifest)
    server_mods = write_server()
    write_scripts()
    write_licence()
    write_readme(version, paths, manifest, site, own_rows, port_rows)
    hits = scan_secrets()
    total = sum(p.stat().st_size for p in OUT.rglob("*") if p.is_file() and ".git" not in p.parts)
    print(json.dumps({"out": OUT.relative_to(PORT).as_posix(), "version": version, "packFiles": pack_listing[:12], "ownMods": [(n, c) for n, _, _, c in own_rows],
                      "ports": [(f, s, b) for f, (_, _, _, s, b, _) in zip(PORTS, port_rows)], "serverMods": len(server_mods),
                      "bytesWithoutGit": total, "secretHits": hits}, indent=1))
    if hits:
        raise SystemExit("Secret-looking values found: fix them before committing.")


if __name__ == "__main__":
    main()
