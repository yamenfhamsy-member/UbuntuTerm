#!/usr/bin/env python3
"""Generate stub R and BuildConfig classes so that the sources can be type checked with javac.

The Android build generates these classes from the resources. For the local pre-flight check
they are generated from the resource files of the repository instead.
"""

import os
import re
import sys
import xml.etree.ElementTree as ET


def collect_res(res_dirs):
    """Return {type: set(names)} for the given resource directories."""
    if isinstance(res_dirs, str):
        res_dirs = [res_dirs]
    res = {}
    for res_dir in res_dirs:
        _collect_res_dir(res_dir, res)
    return res


def _collect_res_dir(res_dir, res):
    if not os.path.isdir(res_dir):
        return

    for entry in sorted(os.listdir(res_dir)):
        path = os.path.join(res_dir, entry)
        if os.path.isdir(path):
            # Resource directories, e.g. "layout" or "drawable".
            rtype = entry.split("-")[0]
            if rtype in ("layout", "menu", "drawable", "mipmap", "anim", "animator",
                         "color", "xml", "raw", "font", "navigation", "transition"):
                for item in sorted(os.listdir(path)):
                    if item.startswith("."):
                        continue
                    res.setdefault(rtype, set()).add(item.split(".")[0])
            continue
        if entry.startswith("layout"):
            res.setdefault("layout", set()).add(entry.split(".")[0])
        elif entry.startswith("menu"):
            res.setdefault("menu", set()).add(entry.split(".")[0])
        elif entry.startswith("drawable"):
            res.setdefault("drawable", set()).add(entry.split(".")[0])
        elif entry.startswith("mipmap"):
            res.setdefault("mipmap", set()).add(entry.split(".")[0])
        elif entry.startswith("anim"):
            res.setdefault("anim", set()).add(entry.split(".")[0])
        elif entry.startswith("color"):
            res.setdefault("color", set()).add(entry.split(".")[0])
        elif entry.startswith("xml"):
            res.setdefault("xml", set()).add(entry.split(".")[0])
        elif entry.startswith("raw"):
            res.setdefault("raw", set()).add(entry.split(".")[0])
        elif entry.startswith("font"):
            res.setdefault("font", set()).add(entry.split(".")[0])

    # Identifiers that are declared inside the layout files with "@+id/...".
    for root_dir, _dirs, files in os.walk(res_dir):
        for name in files:
            if not name.endswith(".xml"):
                continue
            try:
                with open(os.path.join(root_dir, name), "r", errors="ignore") as handle:
                    content = handle.read()
            except OSError:
                continue
            for match in re.finditer(r'@\+id/([A-Za-z0-9_]+)', content):
                res.setdefault("id", set()).add(match.group(1))

    values_dir = os.path.join(res_dir, "values")
    if os.path.isdir(values_dir):
        for entry in sorted(os.listdir(values_dir)):
            if not entry.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(values_dir, entry)).getroot()
            except ET.ParseError:
                continue
            for child in root:
                name = child.attrib.get("name")
                if not name:
                    continue
                tag = child.tag
                if tag == "string":
                    res.setdefault("string", set()).add(name)
                elif tag == "style":
                    res.setdefault("style", set()).add(name)
                elif tag == "color":
                    res.setdefault("color", set()).add(name)
                elif tag == "dimen":
                    res.setdefault("dimen", set()).add(name)
                elif tag == "bool":
                    res.setdefault("bool", set()).add(name)
                elif tag == "integer":
                    res.setdefault("integer", set()).add(name)
                elif tag == "array":
                    res.setdefault("array", set()).add(name)
                elif tag == "attr":
                    res.setdefault("attr", set()).add(name)
                elif tag == "item":
                    res.setdefault("id", set()).add(name)
    return res


def write_r(package, res_dir, out_dir):
    res = collect_res(res_dir)
    pkg_dir = os.path.join(out_dir, *package.split("."))
    os.makedirs(pkg_dir, exist_ok=True)
    lines = ["package %s;" % package, "", "public final class R {"]
    for rtype in sorted(res):
        lines.append("    public static final class %s {" % rtype)
        for name in sorted(res[rtype]):
            # AAPT turns the dots of a resource name into underscores.
            lines.append("        public static final int %s = 0x7f000000;" % name.replace(".", "_"))
        lines.append("    }")
    lines.append("}")
    path = os.path.join(pkg_dir, "R.java")
    with open(path, "w") as handle:
        handle.write("\n".join(lines) + "\n")
    return path


def write_androidx_r(res_dirs, out_dir):
    """Write the R stubs of the AndroidX artifacts that are referenced by the sources."""
    styles = set()
    for res_dir in res_dirs:
        styles |= collect_res(res_dir).get("style", set())

    packages = {
        "androidx.appcompat": {
            "style": {"Theme_AppCompat_Light_Dialog", "Theme_AppCompat_Dialog",
                      "Theme_AppCompat_Light", "Theme_AppCompat"},
        },
        "androidx.core": {
            "style": {"Widget_AppCompat", "Theme_AppCompat_Light"},
            "drawable": {"abc_ic_ab_back_material", "notification_icon_small"},
        },
        "androidx.recyclerview": {"layout": {"abc_alert_dialog_title_material"}},
    }

    for package, types in packages.items():
        pkg_dir = os.path.join(out_dir, *package.split("."))
        os.makedirs(pkg_dir, exist_ok=True)
        lines = ["package %s;" % package, "", "public final class R {"]
        for rtype, names in types.items():
            lines.append("    public static final class %s {" % rtype)
            for name in sorted(names):
                lines.append("        public static final int %s = 0x7f000000;" % name)
            lines.append("    }")
        lines.append("}")
        with open(os.path.join(pkg_dir, "R.java"), "w") as handle:
            handle.write("\n".join(lines) + "\n")


def write_build_config(package, out_dir, package_name, version_name, version_code, variant):
    pkg_dir = os.path.join(out_dir, *package.split("."))
    os.makedirs(pkg_dir, exist_ok=True)
    body = f"""package {package};

public final class BuildConfig {{
    public static final boolean DEBUG = false;
    public static final String APPLICATION_ID = "{package_name}";
    public static final String BUILD_TYPE = "{variant}";
    public static final int VERSION_CODE = {version_code};
    public static final String VERSION_NAME = "{version_name}";
    public static final String TERMUX_PACKAGE_VARIANT = "apt-android-7";
}}
"""
    path = os.path.join(pkg_dir, "BuildConfig.java")
    with open(path, "w") as handle:
        handle.write(body)
    return path


def main():
    if len(sys.argv) < 4:
        print("usage: gen_r_stub.py <repo root> <out dir> <app package id>", file=sys.stderr)
        return 1

    root, out, package_id = sys.argv[1], sys.argv[2], sys.argv[3]
    out = os.path.abspath(out)
    os.makedirs(out, exist_ok=True)

    # In an Android build the R class of a library contains the merged resources of the whole
    # application, so the stubs use the union of all resource directories.
    res_dirs = [
        os.path.join(root, "app/src/main/res"),
        os.path.join(root, "termux-shared/src/main/res"),
        os.path.join(root, "terminal-view/src/main/res"),
        os.path.join(root, "terminal-emulator/src/main/res"),
    ]

    write_r("com.termux", res_dirs, out)
    write_r("com.termux.shared", res_dirs, out)
    write_r("com.termux.view", res_dirs, out)
    write_r("com.termux.terminal", res_dirs, out)
    write_androidx_r(res_dirs, out)
    write_build_config("com.termux", out, package_id, "1.0.0", 1, "release")
    write_build_config("com.termux.shared", out, package_id, "1.0.0", 1, "release")
    write_build_config("com.termux.terminal", out, package_id, "1.0.0", 1, "release")
    write_build_config("com.termux.view", out, package_id, "1.0.0", 1, "release")
    return 0


if __name__ == "__main__":
    sys.exit(main())
