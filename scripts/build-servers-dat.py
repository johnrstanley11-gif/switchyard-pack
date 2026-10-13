"""Writes pack-26.3-private/servers.dat: the multiplayer server list that the client packs install (build-mrpack
bundles it as overrides/servers.dat), so the Switchyard server is already in the list the first time the game starts.
Format checked against Minecraft 26.3 (ServerList/ServerData): uncompressed NBT, root compound with a "servers" list of
compounds {name, ip, icon = base64 PNG, hidden = 0}. The icon comes from server-icon.png (build-server-icon.py), so the
entry shows it even before the first status ping. Launchers re-apply overrides on pack updates, which resets a player's
own list to this one; that is the normal behaviour of server packs."""
from pathlib import Path
import base64
import struct

PORT = Path(__file__).resolve().parents[1]
PACK = PORT / "pack-26.3-private"
SERVERS = [{"name": "Switchyard", "ip": "mc.jorry.live"}]


def tag_string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def named(tag_type, name, payload):
    return bytes([tag_type]) + tag_string(name) + payload


def compound(entries):
    return b"".join(entries) + b"\x00"


def build():
    icon = base64.b64encode((PACK / "server-icon.png").read_bytes()).decode("ascii")
    servers = []
    for server in SERVERS:
        servers.append(compound([named(8, "name", tag_string(server["name"])), named(8, "ip", tag_string(server["ip"])),
                                 named(8, "icon", tag_string(icon)), named(1, "hidden", b"\x00")]))
    servers_list = bytes([10]) + struct.pack(">i", len(servers)) + b"".join(servers)      # list of compounds
    root = named(10, "", compound([named(9, "servers", servers_list)]))
    out = PACK / "servers.dat"
    out.write_bytes(root)
    print(out, out.stat().st_size, "bytes", [s["name"] + " = " + s["ip"] for s in SERVERS])


def read_back(path):
    """Small independent parser used as a check that the file is well formed."""
    data = path.read_bytes()
    pos = 0

    def string():
        nonlocal pos
        length = struct.unpack(">H", data[pos:pos + 2])[0]
        pos += 2
        value = data[pos:pos + length].decode("utf-8")
        pos += length
        return value

    assert data[pos] == 10
    pos += 1
    assert string() == ""
    assert data[pos] == 9
    pos += 1
    assert string() == "servers"
    assert data[pos] == 10
    pos += 1
    count = struct.unpack(">i", data[pos:pos + 4])[0]
    pos += 4
    found = []
    for _ in range(count):
        entry = {}
        while data[pos] != 0:
            tag_type = data[pos]
            pos += 1
            name = string()
            if tag_type == 8:
                entry[name] = string()
            elif tag_type == 1:
                entry[name] = data[pos]
                pos += 1
            else:
                raise AssertionError("unexpected tag " + str(tag_type))
        pos += 1
        found.append(entry)
    pos += 1
    assert pos == len(data)
    return found


if __name__ == "__main__":
    build()
    for entry in read_back(PACK / "servers.dat"):
        print({k: (v if k != "icon" else "png " + str(len(base64.b64decode(v))) + " bytes") for k, v in entry.items()})
