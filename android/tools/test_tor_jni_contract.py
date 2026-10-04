"""JNI packaging regressions using small DEX fixtures, without Gradle or a phone."""
import hashlib
import stat
import struct
import tempfile
import unittest
import warnings
import zipfile
import zlib
from pathlib import Path
from unittest.mock import patch

import verify_tor_jni_contract as tool


# Fixture contracts are literal, independent of the verifier's required sets.
FIELDS = [("torConfiguration", "J"), ("torControlFd", "I"), ("torThread", "Ljava/lang/Thread;"),
          ("controlPortThread", "Ljava/lang/Thread;")]
METHODS = [
    ("createTorConfiguration", (), "Z", 0x102),
    ("mainConfigurationFree", (), "V", 0x102),
    ("prepareFileDescriptor", ("Ljava/lang/String;",), "Ljava/io/FileDescriptor;", 0x10a),
    ("mainConfigurationSetCommandLine", ("[Ljava/lang/String;",), "Z", 0x102),
    ("mainConfigurationSetupControlSocket", (), "Z", 0x102),
    ("runMain", (), "I", 0x102),
]


def leb(value):
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7f) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def checksums(data):
    data = bytearray(data)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into("<I", data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


def fixture(fields=None, methods=None, omit_fields=(), omit_methods=(), static_fields=(),
            class_name="Lorg/torproject/jni/TorService;", duplicate_class=False):
    """Construct header/tables/map/class_data and correctly checksummed DEX 035.

    Omitted definitions stay in the ID tables: references and literal strings
    must never be mistaken for member definitions.
    """
    fields = list(FIELDS if fields is None else fields)
    methods = list(METHODS if methods is None else methods)
    types = {class_name, "Ljava/lang/Object;"}
    types.update(descriptor for _, descriptor in fields)
    for _, args, result, _ in methods:
        types.update(args)
        types.add(result)

    def shorty(args, result):
        return "".join(value if len(value) == 1 else "L" for value in (result, *args))

    strings = sorted(types | {name for name, _ in fields} | {m[0] for m in methods}
                     | {shorty(args, result) for _, args, result, _ in methods})
    string_index = {value: i for i, value in enumerate(strings)}
    types = sorted(types, key=string_index.__getitem__)
    type_index = {value: i for i, value in enumerate(types)}
    protos = sorted({(args, result) for _, args, result, _ in methods},
                    key=lambda p: (type_index[p[1]], tuple(type_index[a] for a in p[0])))
    proto_index = {value: i for i, value in enumerate(protos)}
    fields.sort(key=lambda f: (string_index[f[0]], type_index[f[1]]))
    methods.sort(key=lambda m: (string_index[m[0]], proto_index[(m[1], m[2])]))
    sizes = [len(strings), len(types), len(protos), len(fields), len(methods), 2 if duplicate_class else 1]
    strides = [4, 4, 12, 8, 8, 32]
    data = bytearray(112 + sum(size * stride for size, stride in zip(sizes, strides)))
    offsets, offset = [], 112
    for size, stride in zip(sizes, strides):
        offsets.append(offset if size else 0)
        offset += size * stride
    data_start = len(data)
    string_data_start = len(data)
    for i, value in enumerate(strings):
        struct.pack_into("<I", data, offsets[0] + 4 * i, len(data))
        data += leb(len(value)) + value.encode("ascii") + b"\0"
    for i, value in enumerate(types):
        struct.pack_into("<I", data, offsets[1] + 4 * i, string_index[value])
    param_offsets = []
    for i, (args, result) in enumerate(protos):
        params = 0
        if args:
            data += b"\0" * (-len(data) % 4)
            params = len(data)
            param_offsets.append(params)
            data += struct.pack("<I", len(args))
            data += struct.pack("<" + "H" * len(args), *(type_index[a] for a in args))
        struct.pack_into("<III", data, offsets[2] + 12 * i,
                         string_index[shorty(args, result)], type_index[result], params)
    for i, (name, descriptor) in enumerate(fields):
        struct.pack_into("<HHI", data, offsets[3] + 8 * i,
                         type_index[class_name], type_index[descriptor], string_index[name])
    for i, (name, args, result, _) in enumerate(methods):
        struct.pack_into("<HHI", data, offsets[4] + 8 * i,
                         type_index[class_name], proto_index[(args, result)], string_index[name])
    static = [(i, 0xa) for i, field in enumerate(fields)
              if field[0] not in omit_fields and field[0] in static_fields]
    instance = [(i, 0x2) for i, field in enumerate(fields)
                if field[0] not in omit_fields and field[0] not in static_fields]
    direct = [(i, method[3]) for i, method in enumerate(methods) if method[0] not in omit_methods]
    class_data = len(data)
    data += b"".join(leb(len(group)) for group in (static, instance, direct, []))
    for group_index, group in enumerate((static, instance, direct)):
        previous = 0
        for index, flags in group:
            data += leb(index - previous) + leb(flags)
            if group_index == 2:
                data += b"\0"  # native method code_off
            previous = index
    for i in range(sizes[5]):
        struct.pack_into("<IIIIIIII", data, offsets[5] + 32 * i,
                         type_index[class_name], 1, type_index["Ljava/lang/Object;"],
                         0, 0xffffffff, 0, class_data, 0)
    data += b"\0" * (-len(data) % 4)
    map_offset = len(data)
    map_items = [(0, 1, 0)] + [(i + 1, size, start) for i, (size, start) in
                              enumerate(zip(sizes, offsets)) if size]
    map_items += [(0x2002, len(strings), string_data_start), (0x2000, 1, class_data),
                  (0x1000, 1, map_offset)]
    if param_offsets:
        map_items.append((0x1001, len(param_offsets), min(param_offsets)))
    map_items.sort(key=lambda item: item[2])
    data += struct.pack("<I", len(map_items))
    data += b"".join(struct.pack("<HHII", kind, 0, count, start)
                      for kind, count, start in map_items)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    struct.pack_into("<I", data, 52, map_offset)
    for i, (count, start) in enumerate(zip(sizes, offsets)):
        struct.pack_into("<II", data, 56 + 8 * i, count, start)
    struct.pack_into("<II", data, 104, len(data) - data_start, data_start)
    return checksums(data)


class TorJniContractTest(unittest.TestCase):
    def verify_entries(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "fixture.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    for name, data in entries:
                        archive.writestr(name, data)
            report = tool.verify(apk)
            self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), report["apk_sha256"])
            return report

    def verify_dex(self, data):
        return self.verify_entries([("classes.dex", data)])

    def test_defined_contract_passes_with_artifact_hash(self):
        report = self.verify_dex(fixture())
        self.assertEqual("VERIFIED", report["status"])
        self.assertEqual({"torConfiguration": "J", "torControlFd": "I",
                          "torThread": "Ljava/lang/Thread;", "controlPortThread": "Ljava/lang/Thread;"},
                         report["instance_fields"])
        self.assertEqual(6, len(report["native_methods"]))

    def test_contract_can_be_in_later_dex(self):
        report = self.verify_entries([("classes.dex", fixture(class_name="Lexample/Other;")),
                                      ("classes2.dex", fixture())])
        self.assertEqual(["classes.dex", "classes2.dex"], report["dex_entries"])

    def test_missing_field_definition_fails_even_when_id_and_string_remain(self):
        for name, _ in FIELDS:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing JNI instance field " + name):
                self.verify_dex(fixture(omit_fields=(name,)))

    def test_wrong_field_descriptor_fails(self):
        for name, descriptor in FIELDS:
            fields = [(n, "I" if descriptor == "J" else "J") if n == name else (n, d)
                      for n, d in FIELDS]
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Missing JNI instance field"):
                self.verify_dex(fixture(fields=fields))

    def test_static_field_cannot_satisfy_instance_lookup(self):
        with self.assertRaisesRegex(ValueError, "Missing JNI instance field"):
            self.verify_dex(fixture(static_fields=("torConfiguration",)))

    def test_native_completion_field_must_be_defined_with_exact_thread_type_and_instance_owner(self):
        for thread_name in ("torThread", "controlPortThread"):
            wrong_type = [(name, "Ljava/lang/Runnable;" if name == thread_name else descriptor)
                          for name, descriptor in FIELDS]
            cases = {"missing": fixture(omit_fields=(thread_name,)), "wrong_type": fixture(fields=wrong_type),
                     "static": fixture(static_fields=(thread_name,))}
            for case, data in cases.items():
                with self.subTest(field=thread_name, case=case), self.assertRaisesRegex(
                        ValueError, "Missing JNI instance field " + thread_name):
                    self.verify_dex(data)

    def test_missing_native_method_definition_fails_even_when_reference_remains(self):
        for name, _, _, _ in METHODS:
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "JNI native method " + name):
                self.verify_dex(fixture(omit_methods=(name,)))

    def test_removed_native_flag_fails(self):
        methods = [(n, args, result, flags & ~0x100) if n == "runMain" else (n, args, result, flags)
                   for n, args, result, flags in METHODS]
        with self.assertRaisesRegex(ValueError, "JNI native method runMain"):
            self.verify_dex(fixture(methods=methods))

    def test_changed_method_descriptor_fails(self):
        methods = [(n, args, "J", flags) if n == "runMain" else (n, args, result, flags)
                   for n, args, result, flags in METHODS]
        with self.assertRaisesRegex(ValueError, "JNI native method runMain"):
            self.verify_dex(fixture(methods=methods))

    def test_changed_native_static_flag_fails(self):
        methods = [(n, args, result, flags & ~0x8) if n == "prepareFileDescriptor" else (n, args, result, flags)
                   for n, args, result, flags in METHODS]
        with self.assertRaisesRegex(ValueError, "JNI native method prepareFileDescriptor"):
            self.verify_dex(fixture(methods=methods))

    def test_renamed_class_fails(self):
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.verify_dex(fixture(class_name="Lexample/Renamed;"))

    def test_duplicate_class_within_and_across_dex_fails(self):
        with self.assertRaisesRegex(ValueError, "Duplicate DEX class"):
            self.verify_dex(fixture(duplicate_class=True))
        with self.assertRaisesRegex(ValueError, "exactly one"):
            self.verify_entries([("classes.dex", fixture()), ("classes2.dex", fixture())])

    def test_duplicate_zip_dex_entry_fails(self):
        with self.assertRaisesRegex(ValueError, "Duplicate or symlink"):
            self.verify_entries([("classes.dex", fixture())] * 2)

    def test_duplicate_member_definitions_fail(self):
        with self.assertRaisesRegex(ValueError, "Duplicate DEX field definition"):
            self.verify_dex(fixture(fields=FIELDS + [FIELDS[0]]))
        with self.assertRaisesRegex(ValueError, "Duplicate DEX method definition"):
            self.verify_dex(fixture(methods=METHODS + [METHODS[0]]))

    def test_zip_symlink_dex_entry_fails(self):
        entry = zipfile.ZipInfo("classes.dex")
        entry.create_system = 3
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaisesRegex(ValueError, "Duplicate or symlink"):
            self.verify_entries([(entry, fixture())])

    def test_truncated_and_tampered_dex_fail(self):
        data = fixture()
        for invalid in (data[:20], data[:-1], data[:200] + bytes([data[200] ^ 1]) + data[201:]):
            with self.subTest(size=len(invalid)), self.assertRaises(ValueError):
                self.verify_dex(invalid)

    def test_out_of_bounds_table_rejected_with_valid_checksums(self):
        data = bytearray(fixture())
        struct.pack_into("<I", data, 56, 0xffffffff)
        with self.assertRaisesRegex(ValueError, "bounds"):
            self.verify_dex(checksums(data))

    def test_unterminated_class_data_uleb_rejected(self):
        data = bytearray(fixture())
        class_table = struct.unpack_from("<I", data, 100)[0]
        class_data = struct.unpack_from("<I", data, class_table + 24)[0]
        data[class_data:class_data + 5] = b"\xff" * 5
        with self.assertRaisesRegex(ValueError, "ULEB128"):
            self.verify_dex(checksums(data))

    def test_bounded_dex_inventory(self):
        with patch.object(tool, "MAX_TOTAL_DEX_BYTES", 10):
            with self.assertRaisesRegex(ValueError, "bounded size"):
                self.verify_dex(fixture())

    def test_missing_or_unexpected_dex_name_fails(self):
        for entries in ([('AndroidManifest.xml', b"fixture")], [('classes1.dex', fixture())]):
            with self.subTest(entries=entries[0][0]), self.assertRaises(ValueError):
                self.verify_entries(entries)


if __name__ == "__main__":
    unittest.main()
