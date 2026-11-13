#!/usr/bin/env python3
"""Check that every class the application uses is really inside the APK.

A library can reference the classes of an optional dependency. Such a class is not packaged
into the APK, and the application dies with a "Failed resolution of: L…;" error as soon as
the code path is used. This happened with the XZ support of commons-compress: the classes of
``org.tukaani:xz`` were referenced but never packaged, so the bundled rootfs archive could
not be decompressed on the device.

The script reads the dex files of an APK and reports every class that is referenced by one of
them but not defined in any of them. The classes of the Android platform and the optional
dependencies of the libraries that the application uses are ignored, they are listed below.

Usage:
    tools/dex-audit.py path/to/app.apk
"""

import struct
import sys
import zipfile

# The classes of the Android platform, they are always available on the device.
PLATFORM_PREFIXES = (
    "Ljava/", "Ljavax/", "Landroid/", "Landroidx/", "Ldalvik/", "Lkotlin/", "Lsun/",
    "Lorg/w3c/", "Lorg/xml/", "Lorg/xmlpull/", "Lorg/json/", "Lorg/apache/http/",
    "Lorg/intellij/", "Lorg/jetbrains/", "Lorg/gradle/", "Lcom/android/",
)

# The optional dependencies of the libraries that the application uses. The application never
# calls the code paths that need them, so their classes are not packaged and not needed.
OPTIONAL_PREFIXES = (
    # The pack200 support of commons-compress.
    "Lorg/objectweb/asm/", "Lorg/apache/commons/compress/harmony/",
    # The annotations and the optional helpers of commons-compress.
    "Lorg/checkerframework/", "Lafu/org/checkerframework/",
    "Lorg/apache/commons/lang3/",
    # The zstd and the brotli support of commons-compress.
    "Lcom/github/luben/", "Lorg/brotli/",
    # The commonmark parser that markwon only uses in its extensions.
    "Lorg/commonmark/",
    # The window extensions of androidx.window, they are loaded reflectively and are
    # provided by the manufacturer of the device.
    "Landroidx/window/extensions/", "Landroidx/window/sidecar/",
)


def read_dex(data):
    """Return the referenced types and the defined classes of a dex file."""
    if data[:4] != b"dex\n":
        raise ValueError("not a dex file")
    header = struct.unpack_from("<14I", data, 56)
    (string_ids_size, string_ids_off, type_ids_size, type_ids_off, _, _,
     _, _, _, _, class_defs_size, class_defs_off, _, _) = header

    strings = []
    for index in range(string_ids_size):
        offset = struct.unpack_from("<I", data, string_ids_off + 4 * index)[0]
        position = offset
        while True:
            byte = data[position]
            position += 1
            if not byte & 0x80:
                break
        end = data.index(b"\x00", position)
        strings.append(data[position:end].decode("utf-8", "replace"))

    types = [strings[struct.unpack_from("<I", data, type_ids_off + 4 * index)[0]]
             for index in range(type_ids_size)]

    defined = set()
    for index in range(class_defs_size):
        class_index = struct.unpack_from("<I", data, class_defs_off + 32 * index)[0]
        defined.add(types[class_index])

    return types, defined


def main(argv):
    if len(argv) != 2:
        print("usage: dex-audit.py <apk>", file=sys.stderr)
        return 2

    apk = argv[1]
    referenced = set()
    defined = set()

    with zipfile.ZipFile(apk) as archive:
        names = [name for name in archive.namelist()
                 if name.startswith("classes") and name.endswith(".dex")]
        if not names:
            print("[FAIL] The APK does not contain a dex file", file=sys.stderr)
            return 1
        for name in sorted(names):
            types, classes = read_dex(archive.read(name))
            referenced.update(types)
            defined.update(classes)
            print("[INFO] %s: %d classes" % (name, len(classes)))

    missing = set()
    for name in referenced:
        if not name.startswith("L") or not name.endswith(";") or name.startswith("["):
            continue
        if name in defined:
            continue
        if name.startswith(PLATFORM_PREFIXES) or name.startswith(OPTIONAL_PREFIXES):
            continue
        missing.add(name)

    if missing:
        print("[FAIL] %d classes are used but not packaged into the APK:" % len(missing),
              file=sys.stderr)
        for name in sorted(missing):
            print("       " + name, file=sys.stderr)
        return 1

    print("[PASS] Every class that the application uses is inside the APK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
