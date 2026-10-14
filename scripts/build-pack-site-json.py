"""Writes pack-26.3-private/dist/pack.json: what the Switchyard home page shows about the client pack (build, downloads,
mod list). Built from manifest.json and the pack files of the version set in build-mrpack-20261011.py, so the page
cannot drift from the published pack. Run after building the packs; the website deploy script uploads it.
--preview FILE also writes a stand-alone HTML preview of the home page panel (same markup as web/templates/home.php).
"""
from pathlib import Path
import html
import json
import re
import sys

PORT = Path(__file__).resolve().parents[1]
PACK = PORT / "pack-26.3-private"
VERSION = re.search(r'^VERSION = "([^"]+)"', (PORT / "claude-session/build-mrpack-20261011.py").read_text(encoding="utf-8"), re.M).group(1)
PHONE_SKIP = {"sodium", "iris"}
GROUPS = ["Tech and machines", "Storage, tools and survival", "Maps, voice and interface", "Libraries"]
# slug -> (display name, version shown, group index). Every client-pack mod must be listed: the script stops otherwise.
MODS = {
    "mekanism": ("Mekanism", "10.8.0", 0), "mekanism-generators": ("Mekanism Generators", "10.8.0", 0),
    "ae2": ("Applied Energistics 2", "26.3.1 alpha", 0), "projecte": ("ProjectE", "1.1.0", 0),
    "project-red-core": ("Project Red: Core", "5.0", 0), "project-red-transmission": ("Project Red: Transmission", "5.0", 0),
    "project-red-integration": ("Project Red: Integration", "5.0", 0), "project-red-expansion": ("Project Red: Expansion", "5.0", 0),
    "project-red-exploration": ("Project Red: Exploration", "5.0", 0), "project-red-illumination": ("Project Red: Illumination", "5.0", 0),
    "classic-pipes": ("Classic Pipes", "1.1.7", 0), "additional-enchanted-miner": ("Additional Enchanted Miner (QuarryPlus)", "26.20.159", 0),
    "farmers-delight": ("Farmer's Delight", "1.3.4", 0),
    "iron-chests": ("Iron Chests", "16.7", 1), "ender-storage": ("Ender Storage", "2.14.0", 1), "building-wands": ("Building Wands", "3.2.1", 1),
    "sort-it-out": ("Sort It Out!", "1.5.0", 1), "gravestone-mod": ("Gravestone", "1.0.41", 1), "clumps": ("Clumps", "26.3.2", 1),
    "open-parties-and-claims": ("Open Parties and Claims", "0.32.8", 1), "classic-crates": ("Classic Crates (Switchyard menu, vote crates)", "1.4.0", 1),
    "classic-spruce-needles": ("Classic Spruce Needles", "1.0.0", 1),
    "jei": ("Just Enough Items", "31.10.0", 2), "jade": ("Jade", "26.3.5", 2), "xaeros-minimap": ("Xaero's Minimap", "26.6.0", 2),
    "xaeros-world-map": ("Xaero's World Map", "1.47.0", 2), "simple-voice-chat": ("Simple Voice Chat", "2.6.24", 2),
    "sodium": ("Sodium", "0.9.2", 2), "iris": ("Iris Shaders", "1.11.7", 2), "spark": ("Spark", "1.10.187", 2), "ferrite-core": ("FerriteCore", "9.0.0", 2),
    "codechicken-lib": ("CodeChicken Lib", "4.7.0", 3), "cb-multipart": ("CB Multipart", "3.6.0", 3), "architectury-api": ("Architectury API", "22.0.5", 3),
    "cloth-config": ("Cloth Config", "26.3.159", 3), "guideme": ("GuideME", "26.3.3 alpha", 3), "jamlib": ("JamLib", "2.3.1", 3),
    "mezzconfig": ("Mezz Config", "0.6.8", 3), "scalable-cats-force": ("Scalable Cat's Force", "3.9.0", 3),
}
SOURCE = "https://github.com/johnrstanley11-gif/switchyard-pack"   # public repo with the pack files, our mods and the port patches
FILES = [("PC: Modrinth App, Prism, ATLauncher", "Classic-Engineering-%s.mrpack"), ("PC: CurseForge App", "Classic-Engineering-%s-CurseForge.zip"),
         ("Phone: MojoLauncher, Amethyst", "Classic-Engineering-%s-Phone.mrpack")]


def build():
    manifest = json.loads((PACK / "manifest.json").read_text(encoding="utf-8"))
    client = [e for e in manifest["mods"] if e["side"] != "server" and not e.get("heldBack")]
    missing = [e["slug"] for e in client if e["slug"] not in MODS]
    if missing:
        raise SystemExit("Add these mods to MODS in build-pack-site-json.py: " + ", ".join(missing))
    for entry in client:                                   # the version shown must be the one in the pack
        name, version, _ = MODS[entry["slug"]]
        if version.split(" ")[0] not in entry["filename"] and version.split(" ")[0] not in (entry.get("version") or ""):
            raise SystemExit("%s: page version %s is not in %s" % (entry["slug"], version, entry["filename"]))
    groups = []
    for index, title in enumerate(GROUPS):
        mods = sorted(({"name": MODS[e["slug"]][0], "version": MODS[e["slug"]][1], "pcOnly": e["slug"] in PHONE_SKIP}
                       for e in client if MODS[e["slug"]][2] == index), key=lambda m: m["name"].lower())
        groups.append({"title": title, "mods": mods})
    files = []
    for label, pattern in FILES:
        path = PACK / "dist" / (pattern % VERSION)
        files.append({"label": label, "file": path.name, "bytes": path.stat().st_size})
    data = {"name": "Classic Engineering", "build": VERSION.split("-", 1)[1], "version": VERSION, "minecraft": manifest["minecraft"],
            "loader": "NeoForge " + manifest["loader"].split(" ")[-1], "server": "mc.jorry.live",
            "modCount": len(client), "phoneModCount": sum(1 for e in client if e["slug"] not in PHONE_SKIP), "files": files, "groups": groups,
            "source": SOURCE}
    out = PACK / "dist" / "pack.json"
    out.write_text(json.dumps(data, indent=1) + "\n", encoding="utf-8")
    print(json.dumps({"written": out.relative_to(PORT).as_posix(), "build": data["build"], "mods": data["modCount"], "phoneMods": data["phoneModCount"],
                      "groups": [len(g["mods"]) for g in groups]}))
    return data


def preview(data, target):
    """Same markup as the pack panel in web/templates/home.php, with the live theme, for a look before deploying."""
    e = html.escape
    css = (PORT.parents[1] / "web/public/assets/theme.css").read_text(encoding="utf-8")
    size = lambda b: "%.0f MB" % (b / 1048576)
    parts = ['<section class="intro-panel pack-panel"><span class="intro-tag">THE MODPACK</span><h1>%s</h1>' % e(data["name"]),
             '<p class="pack-meta">Build <strong>%s</strong> <i>·</i> Minecraft %s <i>·</i> %s <i>·</i> %d mods <i>·</i> Server <code>%s</code></p>'
             % (e(data["build"]), e(data["minecraft"]), e(data["loader"]), data["modCount"], e(data["server"])), '<div class="pack-downloads">']
    for index, item in enumerate(data["files"]):
        parts.append('<a class="button%s" href="#"><span>⬇ %s</span><small>%s</small></a>' % (" button-gold" if index == 0 else "", e(item["label"]), size(item["bytes"])))
    parts.append('</div><p class="pack-help"><a class="text-link" href="#">How to install and join <span>→</span></a></p>')
    for group in data["groups"]:
        parts.append('<h2 class="pack-group">%s <span>%d</span></h2><ul class="pack-mods">' % (e(group["title"]), len(group["mods"])))
        for mod in group["mods"]:
            parts.append('<li><strong>%s</strong><span>%s%s</span></li>' % (e(mod["name"]), e(mod["version"]), " · PC only" if mod["pcOnly"] else ""))
        parts.append('</ul>')
    parts.append('</section>')
    page = ('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>preview</title><style>%s</style>'
            '<body><div class="site-width" style="max-width:700px;margin:20px auto">%s</div></body>') % (css, "".join(parts))
    Path(target).write_text(page, encoding="utf-8")
    print("preview written to", target)


if __name__ == "__main__":
    built = build()
    if "--preview" in sys.argv:
        preview(built, sys.argv[sys.argv.index("--preview") + 1])
