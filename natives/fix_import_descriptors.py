"""Post-link fixer for the GNU-ld + llvm-dlltool import-descriptor off-by-N bug.

The mixed toolchain (rustup windows-gnu + llvm-ar-as-dlltool import libs) can emit an
import thunk array whose IMAGE_IMPORT_DESCRIPTOR points N entries past the array's real
start. The Windows loader binds only from the declared FirstThunk onward, leaving the
leading entries holding hint/name RVAs forever. Any `jmp [rip]` thunk through an orphaned
slot then EXECUTES import metadata -> 0xc0000005 (DEP) on whatever thread first calls it
(observed: kernel32!Sleep from rayon worker idle paths -> intermittent, untraceable JVM
death with no hs_err and no crash report).

Fix: for each descriptor, walk BACKWARD from the declared FirstThunk/OriginalFirstThunk
while the preceding qword pair looks like a live entry (non-null, identical plausible
name-RVA in both tables), then rewind both descriptor fields to the true array start.
Verifies by re-scanning .text for indirect calls/jmps into unbound import slots.

Standard library only (no pip packages) - this runs on contributors' machines as a
mandatory build step.

Usage: python fix_import_descriptors.py <dll-path>
Exit codes: 0 = clean (fixed or nothing to fix), 1 = orphans remain / error.
"""
import struct
import sys


class Pe:
    """Minimal PE32+ reader: sections, rva mapping, import descriptors."""

    def __init__(self, path):
        with open(path, "rb") as f:
            self.data = bytearray(f.read())
        d = self.data
        if d[0:2] != b"MZ":
            raise ValueError("not an MZ executable")
        e_lfanew = struct.unpack_from("<I", d, 0x3C)[0]
        if d[e_lfanew:e_lfanew + 4] != b"PE\0\0":
            raise ValueError("PE signature not found")
        coff = e_lfanew + 4
        num_sections = struct.unpack_from("<H", d, coff + 2)[0]
        opt_size = struct.unpack_from("<H", d, coff + 16)[0]
        opt = coff + 20
        if struct.unpack_from("<H", d, opt)[0] != 0x20B:
            raise ValueError("not PE32+ (x64)")
        self.size_of_image = struct.unpack_from("<I", d, opt + 56)[0]
        # DataDirectory[1] = imports (directories start at opt+112 for PE32+)
        self.import_dir_rva = struct.unpack_from("<I", d, opt + 112 + 8)[0]
        sect_off = opt + opt_size
        self.sections = []
        for i in range(num_sections):
            o = sect_off + i * 40
            name = d[o:o + 8].rstrip(b"\0").decode("ascii", "replace")
            vsize, va, rawsize, rawptr = struct.unpack_from("<IIII", d, o + 8)
            self.sections.append((name, va, max(vsize, rawsize), rawptr, rawsize))

    def rva_to_off(self, rva):
        for _name, va, size, rawptr, rawsize in self.sections:
            if va <= rva < va + size:
                delta = rva - va
                return rawptr + delta if delta < rawsize else None
        return None

    def section(self, name):
        for s in self.sections:
            if s[0] == name:
                return s
        return None

    def qword(self, rva):
        off = self.rva_to_off(rva)
        if off is None or off + 8 > len(self.data):
            return None
        return struct.unpack_from("<Q", self.data, off)[0]

    def descriptors(self):
        """Yield (index, orig_first_thunk, name_rva, first_thunk)."""
        rva = self.import_dir_rva
        idx = 0
        while True:
            off = self.rva_to_off(rva + idx * 20)
            if off is None:
                return
            ilt, _ts, _fc, name, iat = struct.unpack_from("<IIIII", self.data, off)
            if ilt == 0 and name == 0 and iat == 0:
                return
            yield idx, ilt, name, iat
            idx += 1

    def thunk_count(self, iat_rva):
        n = 0
        while True:
            v = self.qword(iat_rva + n * 8)
            if not v:
                return n
            n += 1


def analyze(pe):
    """Return orphaned thunk sites: indirect call/jmp into import slots the loader
    will never bind."""
    idata = pe.section(".idata")
    text = pe.section(".text")
    if idata is None or text is None:
        return []
    ilo, ihi = idata[1], idata[1] + idata[2]

    bound = []
    for _idx, _ilt, _name, iat in pe.descriptors():
        bound.append((iat, iat + pe.thunk_count(iat) * 8))

    def is_bound(rva):
        return any(a <= rva < b for a, b in bound)

    t_off, t_size = text[3], text[4]
    tb = pe.data[t_off:t_off + t_size]
    trva = text[1]
    orphans = []
    for i in range(len(tb) - 6):
        if tb[i] == 0xFF and tb[i + 1] in (0x15, 0x25):
            disp = struct.unpack_from("<i", tb, i + 2)[0]
            target = trva + i + 6 + disp
            if ilo <= target < ihi and not is_bound(target):
                orphans.append((trva + i, target))
    return orphans


def fix(path):
    pe = Pe(path)
    orphans = analyze(pe)
    if not orphans:
        print("[fix-imports] %s: no orphaned thunks - nothing to do" % path)
        return 0

    print("[fix-imports] %s: %d orphaned thunk site(s) - rewinding descriptors"
          % (path, len(orphans)))

    patches = []  # (file_offset, u32 value)
    for idx, ilt, name_rva, iat in pe.descriptors():
        if not ilt or not iat:
            continue
        back = 0
        while True:
            nxt = back + 8
            vi, va = pe.qword(ilt - nxt), pe.qword(iat - nxt)
            # a live leading entry: both tables mirror a plausible hint/name RVA
            if not vi or not va or vi != va or vi >= pe.size_of_image:
                break
            back = nxt
        if back:
            dll_name = "?"
            name_off = pe.rva_to_off(name_rva)
            if name_off is not None:
                dll_name = bytes(pe.data[name_off:name_off + 32]).split(b"\0")[0].decode(
                    "ascii", "replace")
            print("  %s: rewinding ILT 0x%x->0x%x, IAT 0x%x->0x%x (%d recovered entrie(s))"
                  % (dll_name, ilt, ilt - back, iat, iat - back, back // 8))
            desc_off = pe.rva_to_off(pe.import_dir_rva + idx * 20)
            patches.append((desc_off + 0, ilt - back))
            patches.append((desc_off + 16, iat - back))

    if not patches:
        print("[fix-imports] ERROR: orphans found but no descriptor could be rewound")
        return 1

    with open(path, "r+b") as f:
        for off, val in patches:
            f.seek(off)
            f.write(struct.pack("<I", val))

    remaining = analyze(Pe(path))
    if remaining:
        print("[fix-imports] ERROR: %d orphaned thunk(s) REMAIN after patch" % len(remaining))
        for site, tgt in remaining:
            print("  site 0x%x -> 0x%x" % (site, tgt))
        return 1
    print("[fix-imports] OK: %d descriptor(s) rewound, no orphaned thunks remain"
          % (len(patches) // 2))
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: python fix_import_descriptors.py <dll-path>")
        sys.exit(1)
    sys.exit(fix(sys.argv[1]))
