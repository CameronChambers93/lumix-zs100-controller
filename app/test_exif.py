"""Unit check for server.parse_exif. Run: python3 app/test_exif.py [path/to/photo.JPG] (defaults to the first JPG in the library)."""
import sys, unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import server

PATH = Path(sys.argv.pop(1)) if len(sys.argv) > 1 else next(server.LIBRARY.glob("P*.JPG"), None)


class ExifTest(unittest.TestCase):
    def test_library_jpeg(self):
        self.assertIsNotNone(PATH, "no JPG in the library")
        ex = server.parse_exif(server.read_head(PATH))
        self.assertIn("ZS100", ex["model"])
        self.assertTrue(0.5 < ex["aperture"] < 20 and ex["iso"] >= 80 and 0 < ex["shutter"] < 60)
        self.assertGreater(ex["width"], ex["height"])
        self.assertIsNotNone(server.exif_iso(ex["taken"]))

    def test_garbage(self):
        self.assertEqual(server.parse_exif(b"not a jpeg"), {})
        self.assertEqual(server.parse_exif(b"\xff\xd8\xff\xe1\x00\x10Exif\x00\x00II*\x00"), {})  # truncated
        self.assertEqual(server.parse_dur("0:01:05"), 65)


if __name__ == "__main__":
    unittest.main()
