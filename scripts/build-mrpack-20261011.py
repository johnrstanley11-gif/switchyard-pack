"""Build the CLIENT .mrpack of the private 26.3 pack from pack-26.3-private/manifest.json, and optionally unpack it the
way a launcher would (--install DIR: download every listed file from its URL, verify hashes, apply overrides).

Official mods are referenced by their Modrinth download URL; privately built mods travel inside the pack under
overrides/mods with their licence files, following the layout of the existing dist/Classic-Engineering-0.3.0.mrpack.
Output: pack-26.3-private/dist/Classic-Engineering-<version>.mrpack (+ .sha256). Never touches the live pack.
"""
from pathlib import Path
import hashlib
import json
import sys
import urllib.request
import zipfile

PORT = Path(__file__).resolve().parents[1]
PACK = PORT / "pack-26.3-private"
VERSION = "26.3-0.1.0-test16"
NAME = "Classic Engineering"
OUT = PACK / "dist" / ("Classic-Engineering-" + VERSION + ".mrpack")
# slug -> (source folder under sources/, licence file names to carry)
LICENCES = {
    "mekanism": ("Mekanism-26.3", ["LICENSE"]), "mekanism-generators": ("Mekanism-26.3", ["LICENSE"]),
    "classic-pipes": ("ClassicPipes", ["LICENSE", "LICENSE.txt", "LICENSE.md"]),
    "additional-enchanted-miner": ("AdditionalEnchantedMiner", ["LICENSE"]),
    "codechicken-lib": ("CodeChickenLib", ["LICENSE.txt", "LICENSE"]), "cb-multipart": ("CBMultipart", ["LICENSE.txt", "LICENSE"]),
    "ender-storage": ("EnderStorage", ["LICENSE.txt", "LICENSE"]),
    "project-red-core": ("ProjectRed", ["LICENSE.md", "LICENSE", "LICENSE.txt"]),
    "iron-chests": ("IronChests", ["LICENSE", "LICENSE.txt"]), "building-wands": ("BuildingWands", ["LICENSE", "LICENSE.txt"]),
    "farmers-delight": ("FarmersDelight", ["LICENSE", "LICENSE.txt"]), "projecte": ("ProjectE", ["LICENSE", "LICENSE.txt"]),
    "classic-crates": ("ClassicCrates", ["LICENSE"]), "classic-spruce-needles": ("ClassicSpruceNeedles", ["LICENSE"]),
}


# Extra notice files carried in the pack (path in pack -> file in the workspace)
EXTRA = {
    # Multiplayer list with the Switchyard server already in it (claude-session/build-servers-dat.py); since test14.
    "overrides/servers.dat": "pack-26.3-private/servers.dat",
    "overrides/licenses/Sodium-private-build-NF26.3.0.65.patch": "claude-session/pr-sodium-private-20261011/Sodium-0.9.2-private-NF26.3.0.65.patch",
    "overrides/licenses/Sodium-private-build-wrapoperation.patch": "claude-session/pr-sodium-private-20261011/Sodium-0.9.2-private-NF26.3.0.65-wrapoperation.patch",
}
NOTICE = """Classic Engineering 26.3 - notices

This pack bundles builds of open-source mods that were ported or patched for Minecraft 26.3 / NeoForge 26.3.0.65-beta by
the Switchyard server team. They are NOT official releases of those mods; do not report problems with them to the
original authors. Each mod keeps its own licence (see the licenses folder and MOD-CREDITS.json).

Sodium: the bundled jar is Sodium 0.9.2 with two small patches (included in the licenses folder) so that it starts on
NeoForge 26.3.0.65-beta. Sodium is licensed under the Polyform Shield License 1.0.0:
https://github.com/CaffeineMC/sodium/blob/dev/LICENSE.md
Iris is the unmodified official release (LGPL-3.0).

Source code and patches for every modified mod are available from the server team on request (Switchyard forums or
Discord), as required by the LGPL/GPL-licensed mods among them.
"""


def digest(data, name):
    return hashlib.new(name, data).hexdigest()


def build():
    manifest = json.loads((PACK / "manifest.json").read_text(encoding="utf-8"))
    files, credits, overrides, missing_licences = [], [], {}, []
    for entry in manifest["mods"]:
        if entry.get("heldBack") or entry["side"] == "server":
            continue
        jar = PACK / "client/mods" / entry["filename"]
        data = jar.read_bytes()
        if entry["origin"].startswith("official"):
            if digest(data, "sha512") != entry["sha512"]:
                raise RuntimeError("Hash drift: " + entry["filename"])
            files.append({"path": "mods/" + entry["filename"], "hashes": {"sha1": digest(data, "sha1"), "sha512": entry["sha512"]},
                          "env": {"client": "required", "server": "required" if entry["side"] == "both" else "unsupported"},
                          "downloads": [entry["url"]], "fileSize": len(data)})
            credits.append({"slug": entry["slug"], "file": entry["filename"], "source": "Modrinth official release", "version": entry.get("version"),
                            "releaseType": entry.get("releaseType"), "url": entry["url"]})
        else:
            overrides["overrides/mods/" + entry["filename"]] = data
            credit = {"slug": entry["slug"], "file": entry["filename"], "source": "Built privately for Minecraft 26.3 in this project", "sha256": entry["sha256"]}
            folder, names = LICENCES.get(entry["slug"], LICENCES.get("project-red-core") if entry["slug"].startswith("project-red-") else (None, []))
            licence = next((PORT / "sources" / folder / n for n in names if folder and (PORT / "sources" / folder / n).is_file()), None)
            if licence:
                target = "overrides/licenses/" + folder + "-" + licence.name
                overrides[target] = licence.read_bytes()
                credit["licence"] = target[len("overrides/"):]
            else:
                missing_licences.append(entry["slug"])
            credits.append(credit)
    index = {"formatVersion": 1, "game": "minecraft", "versionId": VERSION, "name": NAME,
             "summary": "TEST BUILD for the Switchyard 26.3 server: an unofficial Tekkit-inspired survival anarchy pack. Not play-tested.",
             "files": sorted(files, key=lambda f: f["path"]), "dependencies": {"minecraft": "26.3", "neoforge": "26.3.0.65-beta"}}
    for target, source in EXTRA.items():
        overrides[target] = (PORT / source).read_bytes()
    overrides["overrides/NOTICE.txt"] = NOTICE.encode()
    overrides["overrides/MOD-CREDITS.json"] = (json.dumps({"pack": NAME, "version": VERSION, "mods": credits}, indent=2) + "\n").encode()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("modrinth.index.json", json.dumps(index, indent=2) + "\n")
        for name in sorted(overrides):
            archive.writestr(name, overrides[name])
    OUT.with_name(OUT.name + ".sha256").write_text(digest(OUT.read_bytes(), "sha256") + "  " + OUT.name + "\n", encoding="utf-8")
    # CurseForge-format zip: that app cannot follow Modrinth links, so every mod jar travels inside it.
    cf = OUT.with_name("Classic-Engineering-" + VERSION + "-CurseForge.zip")
    cf_manifest = {"minecraft": {"version": "26.3", "modLoaders": [{"id": "neoforge-26.3.0.65-beta", "primary": True}]},
                   "manifestType": "minecraftModpack", "manifestVersion": 1, "name": NAME, "version": VERSION, "author": "Switchyard",
                   "files": [], "overrides": "overrides"}
    with zipfile.ZipFile(cf, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(cf_manifest, indent=2) + "\n")
        for name in sorted(overrides):
            archive.writestr(name, overrides[name])
        for item in index["files"]:
            archive.write(PACK / "client" / item["path"], "overrides/" + item["path"])
    cf.with_name(cf.name + ".sha256").write_text(digest(cf.read_bytes(), "sha256") + "  " + cf.name + "\n", encoding="utf-8")
    print(json.dumps({"curseforgeZip": cf.relative_to(PORT).as_posix(), "bytes": cf.stat().st_size}))
    print(json.dumps({"pack": OUT.relative_to(PORT).as_posix(), "bytes": OUT.stat().st_size, "downloadedMods": len(files),
                      "bundledMods": sum(1 for n in overrides if n.startswith("overrides/mods/")), "licenceFiles": sum(1 for n in overrides if "/licenses/" in n),
                      "bundledWithoutLicenceFile": missing_licences}))


PHONE_SKIP = {"sodium", "iris"}       # desktop renderer mods; they do not suit Android GL translation layers
FML_TOML = """#Phone build: NeoForge's early loading window cannot create a GL context on Android launchers, so it is off.
earlyWindowControl = false
"""


PHONE_VERSION = VERSION               # since test3: adds the phone UI kit (claude-session/build-phone-ui-20261011.py)
PHONE_UI = PORT / "designs-26.3/phone-ui"
PHONE_README = """Switchyard phone controls
=========================
Switchyard-controls.json in this folder is a touch layout made for this pack. Launchers do not pick it up by
themselves, but the pack copies it into the launcher's control folder the first time the game starts. After that, in
MojoLauncher: Settings > custom controls > Load > Switchyard-controls, then choose it as the default layout.

options.txt holds phone-sized game settings (bigger interface, shorter view distance). Change anything you like in
Options; a pack update puts these defaults back.
"""


def build_phone():
    """Android (MojoLauncher / Amethyst) variant: no Sodium or Iris, early window disabled through config/fml.toml."""
    out = OUT.with_name("Classic-Engineering-" + PHONE_VERSION + "-Phone.mrpack")
    drop = {entry["filename"] for entry in json.loads((PACK / "manifest.json").read_text(encoding="utf-8"))["mods"] if entry["slug"] in PHONE_SKIP}
    with zipfile.ZipFile(OUT) as desktop, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as phone:
        index = json.loads(desktop.read("modrinth.index.json"))
        index["files"] = [f for f in index["files"] if f["path"].split("/")[-1] not in drop]
        index["versionId"] = PHONE_VERSION + "-phone"
        index["summary"] = "PHONE TEST BUILD for the Switchyard 26.3 server (no Sodium/Iris, early window off). Experimental."
        phone.writestr("modrinth.index.json", json.dumps(index, indent=2) + "\n")
        kept = 0
        for name in desktop.namelist():
            if name == "modrinth.index.json" or name.split("/")[-1] in drop or "Sodium-private-build" in name:
                continue
            phone.writestr(name, desktop.read(name))
            kept += name.startswith("overrides/mods/")
        phone.writestr("overrides/config/fml.toml", FML_TOML)
        phone.writestr("overrides/options.txt", (PHONE_UI / "options.txt").read_bytes())
        phone.writestr("overrides/Switchyard-controls.json", (PHONE_UI / "Switchyard-controls.json").read_bytes())
        phone.writestr("overrides/PHONE-CONTROLS-README.txt", PHONE_README)
        # Phone-sized mod configs (since test16; written by build-phone-ui-20261011.py): Xaero minimap/world map
        # profiles, Jade overlay, AE2 terminal style. Launchers copy overrides on install and on every pack update.
        configs = 0
        for path in sorted((PHONE_UI / "config").rglob("*")):
            if path.is_file():
                phone.writestr("overrides/config/" + path.relative_to(PHONE_UI / "config").as_posix(), path.read_bytes())
                configs += 1
        if configs == 0:
            raise SystemExit("designs-26.3/phone-ui/config is empty: run claude-session/build-phone-ui-20261011.py first")
    out.with_name(out.name + ".sha256").write_text(digest(out.read_bytes(), "sha256") + "  " + out.name + "\n", encoding="utf-8")
    print(json.dumps({"phonePack": out.relative_to(PORT).as_posix(), "bytes": out.stat().st_size, "downloadedMods": len(index["files"]), "bundledMods": kept}))
    return out


def install(target, pack=None):
    """What a launcher does with the pack: fetch listed files by URL, verify, then copy overrides."""
    target = Path(target).resolve()
    if PORT not in target.parents:
        raise SystemExit("Install target must be inside the port workspace")
    with zipfile.ZipFile(pack or OUT) as archive:
        index = json.loads(archive.read("modrinth.index.json"))
        for item in index["files"]:
            if item["env"]["client"] == "unsupported":
                continue
            request = urllib.request.Request(item["downloads"][0], headers={"User-Agent": "classic-engineering-pack-test/1.0"})
            data = urllib.request.urlopen(request, timeout=300).read()
            if digest(data, "sha512") != item["hashes"]["sha512"] or digest(data, "sha1") != item["hashes"]["sha1"] or len(data) != item["fileSize"]:
                raise RuntimeError("Download does not match the pack index: " + item["path"])
            path = target / item["path"]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        copied = 0
        for name in archive.namelist():
            if name.startswith("overrides/") and not name.endswith("/"):
                path = target / name[len("overrides/"):]
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(archive.read(name))
                copied += 1
    print(json.dumps({"installedTo": target.relative_to(PORT).as_posix(), "downloadedAndVerified": len(index["files"]), "overrideFiles": copied,
                      "modJars": len(list((target / "mods").glob("*.jar"))), "needs": index["dependencies"]}))


if __name__ == "__main__":
    if "--phone" in sys.argv:
        phone_pack = build_phone()
        if "--install" in sys.argv:
            install(sys.argv[sys.argv.index("--install") + 1], phone_pack)
    elif "--install" in sys.argv:
        install(sys.argv[sys.argv.index("--install") + 1])
    else:
        build()
