"""he-16: switch DriveDeck's own phone module off; the unit's phone app (Topway com.tw.bt) handles every call.

Applied once to build/drivedeck-apktool-src.tgz (the patched smali tree build.sh builds from); running it again
changes nothing. It edits the archive in memory, so it also works on Windows, where the tree's case-only file name
pairs (c/d8.smali, c/D8.smali) cannot be extracted side by side.

    python build/patches/he16-phone.py

What it changes (jadx names in brackets, DriveDeck 0.6.7):
  c/Z7.g()V             return at once: no HFP / PBAP profile proxies, so no 1-second call poll, no call state,
                        no call overlay, no call notification, no call log writes, no hicar takeover broadcast.
  c/Z7.e(KeyEvent)Z     false: never swallows CALL / ENDCALL / HEADSETHOOK (answered or hung up by itself).
  c/d8.k()Z [C0039d8]   false: the "DriveDeck incoming call" overlay never shows (backstop).
  c/C8.d()Z             false: never binds com.tw.bt to send it the hicar takeover broadcast (backstop).
  MainActivity.P()V     DriveDeck's phone screen (icon, PhoneEntry alias, home tile, PHONE action) opens Topway's
                        phone screen instead: HeCall.openPhone(Context); its own dialler only if that fails.
"""
import io
import os
import sys
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TGZ = os.path.join(ROOT, "build", "drivedeck-apktool-src.tgz")
MARK = "# he-16"

RET_FALSE = "    " + MARK + ": DriveDeck phone module off\n    const/4 v0, 0x0\n\n    return v0\n"
RET_VOID = "    " + MARK + ": DriveDeck phone module off\n    return-void\n"
OPEN_TOPWAY = ("    " + MARK + ": the unit's phone app instead of DriveDeck's\n"
               "    invoke-static {p0}, Lru/drivedeck/launcher/HeCall;->openPhone(Landroid/content/Context;)Z\n\n"
               "    move-result v0\n\n"
               "    if-eqz v0, :he16_own_dialler\n\n"
               "    return-void\n\n"
               "    :he16_own_dialler\n")

PATCHES = [
    ("build_src/smali/c/Z7.smali", ".method public final g()V", RET_VOID),
    ("build_src/smali/c/Z7.smali", ".method public final e(Landroid/view/KeyEvent;)Z", RET_FALSE),
    ("build_src/smali/c/d8.smali", ".method public final k()Z", RET_FALSE),
    ("build_src/smali/c/C8.smali", ".method public final d()Z", RET_FALSE),
    ("build_src/smali/ru/drivedeck/launcher/MainActivity.smali", ".method public final P()V", OPEN_TOPWAY),
]


def patch_method(text, header, body):
    """Insert body right after the .locals line of the method that starts with header."""
    start = text.index(header + "\n")
    end = text.index(".end method", start)
    method = text[start:end]
    if MARK in method:
        return text, False
    loc = method.index("    .locals ")
    line_end = method.index("\n", loc) + 1
    locals_n = int(method[loc:line_end].split()[1])
    if locals_n < 1:
        raise SystemExit("%s needs a register" % header)
    method = method[:line_end] + "\n" + body + method[line_end:]
    return text[:start] + method + text[end:], True


def main():
    with tarfile.open(TGZ, "r:gz") as src:
        members = [(m, src.extractfile(m).read() if m.isfile() else None) for m in src.getmembers()]
    by_name = {m.name: i for i, (m, _) in enumerate(members)}
    changed = 0
    for name, header, body in PATCHES:
        i = by_name[name]
        m, data = members[i]
        text, did = patch_method(data.decode("utf-8"), header, body)
        if did:
            members[i] = (m, text.encode("utf-8"))
            changed += 1
            print("patched", name, header)
        else:
            print("already", name, header)
    if not changed:
        return
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode="w:gz") as dst:
        for m, data in members:
            if data is not None:
                m.size = len(data)
                dst.addfile(m, io.BytesIO(data))
            else:
                dst.addfile(m)
    with open(TGZ, "wb") as f:
        f.write(out.getvalue())
    print("wrote", TGZ)


if __name__ == "__main__":
    sys.exit(main())
