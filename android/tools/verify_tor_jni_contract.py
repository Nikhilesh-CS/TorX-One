"""Check the pinned Tor JNI contract in defined members of the actual APK DEX.

Contract inspected in info.guardianproject:tor-android:0.4.9.13 classes.jar
and libtor.so: native code looks up torConfiguration:J and torControlFd:I.
App teardown also reflects torThread and controlPortThread:Ljava/lang/Thread;
to prove native completion and prevent a stale watcher attaching to a replacement.
Both threads must remain instance fields in the pinned dependency.
This is a packaging regression check, not a full DEX verifier or device test.
DEX layout: https://source.android.com/docs/core/runtime/dex-format
"""
import argparse
import hashlib
import json
import re
import stat
import struct
import zipfile
import zlib
from pathlib import Path

TOR_CLASS = "Lorg/torproject/jni/TorService;"
PINNED_ARTIFACT = "info.guardianproject:tor-android:0.4.9.13"
REQUIRED_FIELDS = {"torConfiguration": "J", "torControlFd": "I", "torThread": "Ljava/lang/Thread;",
                   "controlPortThread": "Ljava/lang/Thread;"}
# Values are (descriptor, is_static), independently inspected with javap -p -s.
REQUIRED_METHODS = {
    "createTorConfiguration": ("()Z", False),
    "mainConfigurationFree": ("()V", False),
    "prepareFileDescriptor": ("(Ljava/lang/String;)Ljava/io/FileDescriptor;", True),
    "mainConfigurationSetCommandLine": ("([Ljava/lang/String;)Z", False),
    "mainConfigurationSetupControlSocket": ("()Z", False),
    "runMain": ("()I", False),
}
MAX_APK_BYTES = 512 * 1024 * 1024
MAX_DEX_BYTES = 128 * 1024 * 1024
MAX_TOTAL_DEX_BYTES = 256 * 1024 * 1024
MAX_DEX_FILES = 32
MAX_STRING_BYTES = 1024 * 1024
DEX_NAME = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex")
ACC_STATIC = 0x8
ACC_NATIVE = 0x100


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


class Dex:
    """Bounded reader for the tables and class_data needed by this check."""

    def __init__(self, data):
        self.data = data
        self.strings = {}
        if len(data) < 112 or len(data) > MAX_DEX_BYTES:
            raise ValueError("Truncated or oversized DEX")
        if data[:8] not in {b"dex\n035\0", b"dex\n037\0", b"dex\n038\0",
                            b"dex\n039\0", b"dex\n040\0"}:
            raise ValueError("Unsupported DEX magic/version (requires standard DEX 035-040)")
        if self.u32(32) != len(data) or self.u32(36) != 112 or self.u32(40) != 0x12345678:
            raise ValueError("Invalid DEX size, header or endian tag")
        if hashlib.sha1(data[32:]).digest() != data[12:32]:
            raise ValueError("DEX SHA-1 signature mismatch")
        if zlib.adler32(data[12:]) & 0xffffffff != self.u32(8):
            raise ValueError("DEX Adler-32 checksum mismatch")
        self.data_size, self.data_off = self.u32(104), self.u32(108)
        self.bounds(self.data_off, self.data_size)
        if self.data_off < 112 or self.data_off + self.data_size != len(data):
            raise ValueError("Invalid DEX data section")
        self.tables = {}
        ranges = []
        for name, header_off, stride in (("string", 56, 4), ("type", 64, 4),
                                          ("proto", 72, 12), ("field", 80, 8),
                                          ("method", 88, 8), ("class", 96, 32)):
            count, offset = self.u32(header_off), self.u32(header_off + 4)
            if count == 0:
                if offset != 0:
                    raise ValueError("Nonzero offset for empty DEX table")
            else:
                self.bounds(offset, count * stride)
                if offset < 112 or offset % 4 or offset + count * stride > self.data_off:
                    raise ValueError("Invalid DEX table extent")
                ranges.append((offset, offset + count * stride))
            self.tables[name] = (count, offset, stride)
        ranges.sort()
        if any(right[0] < left[1] for left, right in zip(ranges, ranges[1:])):
            raise ValueError("Overlapping DEX tables")

    def bounds(self, offset, size, in_data=False):
        lower = self.data_off if in_data else 0
        if offset < lower or size < 0 or offset > len(self.data) - size:
            raise ValueError("DEX offset/count outside bounds")

    def u32(self, offset):
        self.bounds(offset, 4)
        return struct.unpack_from("<I", self.data, offset)[0]

    def item(self, table, index):
        count, offset, stride = self.tables[table]
        if index < 0 or index >= count:
            raise ValueError("DEX table index outside bounds")
        return offset + index * stride

    def uleb(self, offset):
        result = 0
        for shift in range(0, 35, 7):
            self.bounds(offset, 1, in_data=True)
            byte = self.data[offset]
            offset += 1
            if shift == 28 and byte > 0x0f:
                raise ValueError("Invalid 32-bit DEX ULEB128")
            result |= (byte & 0x7f) << shift
            if byte < 0x80:
                return result, offset
        raise ValueError("Unterminated DEX ULEB128")

    def string(self, index):
        if index in self.strings:
            return self.strings[index]
        offset = self.u32(self.item("string", index))
        units, offset = self.uleb(offset)
        end = self.data.find(b"\0", offset, min(len(self.data), offset + MAX_STRING_BYTES + 1))
        if end < 0:
            raise ValueError("Unterminated or oversized DEX string")
        try:
            # DEX MUTF-8 uses C0 80 for null and CESU-8 for surrogate pairs.
            value = self.data[offset:end].replace(b"\xc0\x80", b"\0").decode("utf-8", "surrogatepass")
        except UnicodeDecodeError as error:
            raise ValueError("Invalid DEX string encoding") from error
        if len(value.encode("utf-16-le", "surrogatepass")) // 2 != units:
            raise ValueError("Invalid DEX string UTF-16 length")
        self.strings[index] = value
        return value

    def type(self, index):
        return self.string(self.u32(self.item("type", index)))

    def proto(self, index):
        offset = self.item("proto", index)
        self.string(self.u32(offset))  # Validate shorty index, even though unused.
        result = self.type(self.u32(offset + 4))
        params = self.u32(offset + 8)
        args = []
        if params:
            self.bounds(params, 4, in_data=True)
            count = self.u32(params)
            if params % 4 or count > 65535:
                raise ValueError("Invalid DEX parameter list")
            self.bounds(params + 4, count * 2, in_data=True)
            args = [self.type(struct.unpack_from("<H", self.data, params + 4 + i * 2)[0])
                    for i in range(count)]
        return "(" + "".join(args) + ")" + result

    def members(self, class_index, offset):
        if not offset:
            return {}, {}
        counts = []
        for _ in range(4):
            value, offset = self.uleb(offset)
            counts.append(value)
        if sum(counts[:2]) > self.tables["field"][0] or sum(counts[2:]) > self.tables["method"][0]:
            raise ValueError("Invalid DEX class member count")
        fields, methods = {}, {}
        seen_fields, seen_methods = set(), set()
        for group, count in enumerate(counts):
            member_index = 0
            for position in range(count):
                difference, offset = self.uleb(offset)
                flags, offset = self.uleb(offset)
                if position and difference == 0:
                    raise ValueError("Duplicate DEX encoded member")
                member_index += difference
                table = "field" if group < 2 else "method"
                seen = seen_fields if group < 2 else seen_methods
                if member_index in seen:
                    raise ValueError("Duplicate DEX member definition")
                seen.add(member_index)
                owner, type_or_proto, name_index = struct.unpack_from("<HHI", self.data,
                                                                     self.item(table, member_index))
                if owner != class_index:
                    raise ValueError("DEX member belongs to another class")
                name = self.string(name_index)
                if group < 2:
                    if bool(flags & ACC_STATIC) != (group == 0):
                        raise ValueError("Invalid DEX static/instance field grouping")
                    key = (name, self.type(type_or_proto))
                    if key in fields:
                        raise ValueError("Duplicate DEX field definition")
                    fields[key] = flags
                else:
                    code, offset = self.uleb(offset)
                    if code:
                        self.bounds(code, 16, in_data=True)
                    if flags & ACC_NATIVE and code:
                        raise ValueError("Native DEX method has a code body")
                    key = (name, self.proto(type_or_proto))
                    if key in methods:
                        raise ValueError("Duplicate DEX method definition")
                    methods[key] = flags
        return fields, methods

    def tor_definitions(self):
        found = []
        seen_classes = set()
        for i in range(self.tables["class"][0]):
            offset = self.item("class", i)
            class_index = self.u32(offset)
            descriptor = self.type(class_index)
            if descriptor in seen_classes:
                raise ValueError("Duplicate DEX class definition")
            seen_classes.add(descriptor)
            if descriptor == TOR_CLASS:
                found.append(self.members(class_index, self.u32(offset + 24)))
        return found


def verify(apk):
    apk = Path(apk)
    if apk.is_symlink() or not apk.is_file() or apk.stat().st_size > MAX_APK_BYTES:
        raise ValueError("APK must be a regular, bounded file without a symlink")
    before = digest(apk)
    definitions, dex_names, seen, total = [], [], set(), 0
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            name = entry.filename
            if not DEX_NAME.fullmatch(name):
                if "/" not in name and name.startswith("classes") and name.endswith(".dex"):
                    raise ValueError("Unexpected APK DEX entry name")
                continue
            if name in seen or stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Duplicate or symlink APK DEX entry")
            seen.add(name)
            total += entry.file_size
            if len(seen) > MAX_DEX_FILES or entry.file_size > MAX_DEX_BYTES or total > MAX_TOTAL_DEX_BYTES:
                raise ValueError("APK DEX inventory exceeds its bounded size")
            with archive.open(entry) as stream:
                data = stream.read(MAX_DEX_BYTES + 1)
            if len(data) != entry.file_size:
                raise ValueError("Truncated or oversized APK DEX entry")
            definitions.extend(Dex(data).tor_definitions())
            dex_names.append(name)
    if "classes.dex" not in seen:
        raise ValueError("APK has no classes.dex")
    if len(definitions) != 1:
        raise ValueError("APK must define exactly one " + TOR_CLASS)
    fields, methods = definitions[0]
    for name, descriptor in REQUIRED_FIELDS.items():
        flags = fields.get((name, descriptor))
        if flags is None or flags & ACC_STATIC:
            raise ValueError("Missing JNI instance field " + name + ":" + descriptor)
    for name, (descriptor, is_static) in REQUIRED_METHODS.items():
        flags = methods.get((name, descriptor))
        if flags is None or not flags & ACC_NATIVE or bool(flags & ACC_STATIC) != is_static:
            raise ValueError("Missing or incompatible JNI native method " + name + descriptor)
    if digest(apk) != before:
        raise ValueError("APK changed during JNI contract verification")
    return dict(schema_version=1, status="VERIFIED", apk_sha256=before,
                contract_artifact=PINNED_ARTIFACT, class_descriptor=TOR_CLASS,
                dex_entries=sorted(dex_names), instance_fields=REQUIRED_FIELDS,
                native_methods={name: dict(descriptor=value[0], is_static=value[1])
                                for name, value in REQUIRED_METHODS.items()},
                limitations=["Pinned Tor JNI and app teardown reflection member packaging only; not a full DEX verifier",
                             "Device startup, native ABI loading and Tor bootstrap still require runtime validation"])


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--report", "--output", dest="output", type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.apk)
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(f"VERIFIED: TorService class, {len(report['instance_fields'])} instance fields and "
              f"{len(report['native_methods'])} native method contracts; APK SHA-256=" + report["apk_sha256"])
    except (ValueError, OSError, RuntimeError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Tor JNI contract verification failed: {error}\n")
