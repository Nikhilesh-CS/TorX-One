"""Validate the complete bundled Unicode repertoire without compiling Android."""
from pathlib import Path
import unittest

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets/emoji"


def catalogue():
    category = ""
    entries = {}
    for line in (ASSETS / "emoji-test.txt").read_text(encoding="utf-8").splitlines():
        if line.startswith("# group: "):
            category = line.removeprefix("# group: ")
        elif line and not line.startswith("#") and ";" in line:
            points, rest = line.split(";", 1)
            status = rest.split("#", 1)[0].strip()
            if status in {"fully-qualified", "component"}:
                emoji = "".join(chr(int(point, 16)) for point in points.split())
                if emoji in entries:
                    raise AssertionError(f"Duplicate Unicode sequence: {points}")
                entries[emoji] = category
    return entries


class EmojiCatalogueTest(unittest.TestCase):
    def test_full_repertoire_fits_existing_reaction_protocol(self):
        entries = catalogue()
        self.assertGreater(len(entries), 3900)
        self.assertTrue(all(0 < len(value.encode("utf-16-le")) // 2 <= 32 for value in entries))
        self.assertGreaterEqual(len(set(entries.values())), 9)

    def test_quick_reactions_and_complex_sequences_present(self):
        entries = catalogue()
        for emoji in ("👍", "❤️", "😂", "😮", "😢", "🙏", "👩🏽‍💻", "🇮🇳", "1️⃣", "🏳️‍🌈"):
            self.assertIn(emoji, entries)
        for tone in range(0x1F3FB, 0x1F400):
            self.assertIn("👍" + chr(tone), entries)

    def test_unicode_source_and_license_are_bundled(self):
        data = (ASSETS / "emoji-test.txt").read_text(encoding="utf-8")
        self.assertIn("# Version: 18.0", data)
        self.assertIn("unicode.org", data)
        license_text = (ASSETS / "UNICODE-LICENSE.txt").read_text(encoding="utf-8")
        self.assertIn("UNICODE", license_text)
        self.assertIn("Permission", license_text)


if __name__ == "__main__":
    unittest.main()
