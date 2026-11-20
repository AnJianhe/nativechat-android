import os, struct, sys, zipfile, zlib

BASE = "apk/base.apk"
OUT = sys.argv[1] if len(sys.argv) > 1 else "apk/app-unsigned.apk"

def build():
    src = zipfile.ZipFile(BASE)
    plan = []
    for name in ["AndroidManifest.xml", "resources.arsc"]:
        if name in src.namelist():
            plan.append((name, src.read(name), False, 4))
    for name in src.namelist():
        if name.startswith("res/") and not name.endswith("/"):
            plan.append((name, src.read(name), True, None))
    with open("apk/classes.dex", "rb") as f:
        plan.append(("classes.dex", f.read(), True, None))

    out = bytearray()
    central = []
    for name, data, compress, align in plan:
        raw = name.encode()
        payload, method = data, 0
        if compress and data:
            compressor = zlib.compressobj(9, zlib.DEFLATED, -15)
            packed = compressor.compress(data) + compressor.flush()
            if len(packed) < len(data):
                payload, method = packed, 8
        extra = b""
        if align:
            pad = (-(len(out) + 30 + len(raw))) % align
            if pad and pad < 4:
                pad += align
            if pad:
                extra = struct.pack("<HH", 0xD935, pad - 4) + b"\0" * (pad - 4)
        offset = len(out)
        out += struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0, method, 0, 0x21,
                           zlib.crc32(data) & 0xFFFFFFFF, len(payload), len(data), len(raw), len(extra))
        out += raw + extra + payload
        central.append((name, raw, method, zlib.crc32(data) & 0xFFFFFFFF, len(payload), len(data), offset))

    cd_start = len(out)
    for name, raw, method, crc, csize, usize, offset in central:
        out += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0, method, 0, 0x21,
                           crc, csize, usize, len(raw), 0, 0, 0, 0, 0, offset)
        out += raw
    out += struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central), len(out) - cd_start, cd_start, 0)
    open(OUT, "wb").write(out)
    print("wrote", OUT, len(out) // 1024, "KB, entries", len(central))

if __name__ == "__main__":
    build()
