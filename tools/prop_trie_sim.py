#!/usr/bin/env python3
"""Replica of bionic (Android 10) prop_area::find_property/find_prop_bt over a
pulled area file, so an insert can be validated locally instead of by guessing
on the device.

Ground truth: libc/system_properties/prop_area.cpp on android-10.0.0_r1.
  * offsets are relative to data_ (file + sizeof(prop_area) = 128)
  * root_node() is at data_+0
  * find_property walks dot-delimited segments: the FIRST segment is searched
    in root_node()->children, later segments in the previous node's children
  * find_prop_bt is a BST ordered by cmp_prop_name (LENGTH FIRST, then strncmp)
  * prop_bt  = {u32 namelen, u32 prop, u32 left, u32 right, u32 children, char name[]}
  * prop_info = {u32 serial, char value[92]} followed by the full name + NUL
"""
import struct
import sys

PA_HDR = 128
PA_SIZE = 128 * 1024
PROP_VALUE_MAX = 92
PROP_NAME_MAX = 32


def load(path):
    raw = open(path, "rb").read()
    bytes_used, serial, magic, version = struct.unpack_from("<IIII", raw, 0)
    data = raw[PA_HDR:]
    print(f"file={path} bytes_used={bytes_used} serial={serial} "
          f"magic={magic:#x} version={version:#x} data_size={len(data)}")
    return data, bytes_used


class Area:
    def __init__(self, data):
        self.d = data
        self.data_size = PA_SIZE - PA_HDR
        self.seen = set()

    def obj(self, off):
        if off > self.data_size:
            return None
        return off

    def u32(self, off):
        return struct.unpack_from("<I", self.d, off)[0]

    def node(self, off):
        if off is None or self.obj(off) is None:
            return None
        namelen, prop, left, right, children = struct.unpack_from("<IIIII", self.d, off)
        name = self.d[off + 20:off + 20 + namelen]
        return dict(off=off, namelen=namelen, prop=prop, left=left,
                    right=right, children=children, name=name.decode("latin1"))

    def prop_info(self, off):
        serial = self.u32(off)
        value = self.d[off + 4:off + 4 + PROP_VALUE_MAX].split(b"\0")[0]
        name = self.d[off + 96:off + 96 + PROP_NAME_MAX].split(b"\0")[0]
        return dict(off=off, serial=serial, value=value.decode("latin1"),
                    name=name.decode("latin1"))

    # --- bionic cmp_prop_name -------------------------------------------------
    @staticmethod
    def cmp_prop_name(one, two):
        if len(one) < len(two):
            return -1
        if len(one) > len(two):
            return 1
        return (one > two) - (one < two)

    def find_prop_bt(self, bt, name, trace=False, depth=0):
        current = bt
        while True:
            if current is None:
                if trace:
                    print("      find_prop_bt: fell off (null)")
                return None
            n = self.node(current)
            if n is None:
                if trace:
                    print(f"      find_prop_bt: offset {current} out of range")
                return None
            ret = self.cmp_prop_name(name, n["name"])
            if trace:
                print(f"      cmp('{name}', '{n['name']}') = {ret}  @data+{n['off']}")
            if ret == 0:
                return n
            if ret < 0:
                if n["left"] != 0:
                    current = n["left"]
                else:
                    if trace:
                        print("      -> would alloc LEFT here")
                    return None
            else:
                if n["right"] != 0:
                    current = n["right"]
                else:
                    if trace:
                        print("      -> would alloc RIGHT here")
                    return None

    def find(self, name, trace=False):
        remaining = name
        current = self.node(0)          # root_node()
        if trace:
            print(f"  find({name}): root@data+0 namelen={current['namelen']} "
                  f"children={current['children']}")
        while True:
            sep = remaining.find(".")
            want_subtree = sep >= 0
            seg = remaining[:sep] if want_subtree else remaining
            if not seg:
                if trace:
                    print("      empty segment -> nullptr")
                return None
            root = current["children"]
            if root == 0:
                if trace:
                    print(f"      node '{(current['name'] or '<root>')}' has no children "
                          f"-> would alloc '{seg}' here")
                return None
            cur = self.find_prop_bt(root, seg, trace=trace)
            if cur is None:
                return None
            if not want_subtree:
                if trace:
                    print(f"      leaf node '{cur['name']}' prop={cur['prop']}")
                if cur["prop"] == 0:
                    if trace:
                        print("      leaf has no prop (would alloc prop_info)")
                    return None
                return self.prop_info(cur["prop"])
            current = cur
            remaining = remaining[sep + 1:]

    def dump(self, off, depth=0, label=""):
        n = self.node(off)
        if n is None:
            return
        if off in self.seen:
            print("  " * depth + f"<cycle at {off}>")
            return
        self.seen.add(off)
        pinfo = ""
        if n["prop"]:
            p = self.prop_info(n["prop"])
            pinfo = f"  prop_info@+{n['prop']} serial={p['serial']} value='{p['value']}' nameafter='{p['name']}'"
        print("  " * depth + f"{label}'{n['name']}' (len {n['namelen']}) @data+{off} "
              f"L={n['left']} R={n['right']} C={n['children']}{pinfo}")
        for kid, lab in ((n["left"], "L: "), (n["right"], "R: "),
                         (n["children"], "C: ")):
            if kid:
                self.dump(kid, depth + 1, lab)


def main():
    data, bytes_used = load(sys.argv[1])
    a = Area(data)
    print("--- trie from root ------------------------------------------------")
    a.dump(0)
    print("--- lookups -------------------------------------------------------")
    for name in sys.argv[2:] or ["security.perf_harden", "service.adb.root",
                                 "service.adb.tcp.port"]:
        print(f"[{name}]")
        got = a.find(name, trace=True)
        print(f"  => {'FOUND ' + repr(got) if got else 'NOT FOUND'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
