#!/usr/bin/env python3
"""Check JNI config/result fields in the actual APK against the bundled sherpa AAR.

Usage: python3 tools/check-release-jni.py APK SHERPA_AAR
No third-party dependencies; run after R8, before publishing the voice build.
"""
import io
import struct
import sys
import zipfile


def class_fields(data):
    """Read non-static field names and JVM descriptors from a .class file."""
    pos = 8

    def read(fmt):
        nonlocal pos
        value = struct.unpack_from(">" + fmt, data, pos)
        pos += struct.calcsize(">" + fmt)
        return value[0] if len(value) == 1 else value

    pool = [None] * read("H")
    index = 1
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < len(pool):
        tag = read("B")
        if tag == 1:
            size = read("H")
            pool[index] = data[pos:pos + size].decode("utf-8", errors="replace")
            pos += size
        elif tag == 7:
            pool[index] = read("H")
        else:
            pos += sizes[tag]
        index += 2 if tag in (5, 6) else 1
    _, this, _ = read("HHH")
    owner = "L" + pool[pool[this]] + ";"
    interfaces = read("H")
    pos += 2 * interfaces
    fields = set()
    for _ in range(read("H")):
        flags, name, descriptor, count = read("HHHH")
        if not flags & 0x0008:
            fields.add((owner, pool[name], pool[descriptor]))
        for _ in range(count):
            _, size = read("HI")
            pos += size
    return owner, fields


def dex_fields(data):
    """Read fields actually defined in DEX, excluding unused field references."""
    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def uleb(offset):
        value = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            value |= (byte & 127) << shift
            if byte < 128:
                return value, offset
            shift += 7

    count, offset = u32(56), u32(60)
    strings = []
    for index in range(count):
        _, start = uleb(u32(offset + index * 4))
        strings.append(data[start:data.index(0, start)].decode("utf-8", errors="replace"))
    count, offset = u32(64), u32(68)
    types = [strings[u32(offset + index * 4)] for index in range(count)]
    count, offset = u32(80), u32(84)
    identifiers = []
    for index in range(count):
        owner, descriptor, name = struct.unpack_from("<HHI", data, offset + index * 8)
        identifiers.append((types[owner], strings[name], types[descriptor]))
    count, offset = u32(96), u32(100)
    classes, fields = set(), set()
    for index in range(count):
        base = offset + index * 32
        classes.add(types[u32(base)])
        pos = u32(base + 24)
        if not pos:
            continue
        static, pos = uleb(pos)
        instance, pos = uleb(pos)
        _, pos = uleb(pos)
        _, pos = uleb(pos)
        for length in (static, instance):
            field_index = 0
            for _ in range(length):
                delta, pos = uleb(pos)
                _, pos = uleb(pos)
                field_index += delta
                fields.add(identifiers[field_index])
    return classes, fields


def main():
    apk, aar = sys.argv[1:]
    required_classes, required_fields = set(), set()
    with zipfile.ZipFile(aar) as archive:
        with zipfile.ZipFile(io.BytesIO(archive.read("classes.jar"))) as jar:
            for name in jar.namelist():
                if name.startswith("com/k2fsa/sherpa/onnx/") and name.endswith(("Config.class", "Rule.class", "Result.class")):
                    owner, fields = class_fields(jar.read(name))
                    required_classes.add(owner)
                    required_fields.update(fields)
    if not required_classes or not required_fields:
        raise SystemExit("No sherpa JNI config/result fields found in the AAR")
    classes, fields = set(), set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                defined_classes, defined_fields = dex_fields(archive.read(name))
                classes.update(defined_classes)
                fields.update(defined_fields)
    missing_classes = required_classes - classes
    missing_fields = required_fields - fields
    if missing_classes or missing_fields:
        for owner in sorted(missing_classes):
            print("Missing JNI class:", owner, file=sys.stderr)
        for owner, name, descriptor in sorted(missing_fields):
            print(f"Missing JNI field: {owner} {name} {descriptor}", file=sys.stderr)
        raise SystemExit("Release JNI verification failed: fields were removed, renamed or changed by R8")
    print(f"Release JNI verified: {len(required_fields)} fields in {len(required_classes)} sherpa config/result classes")


if __name__ == "__main__":
    main()
