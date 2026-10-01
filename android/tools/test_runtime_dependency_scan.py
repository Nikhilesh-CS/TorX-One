"""Regression checks for dependency scanner response handling; no live network."""
import unittest
from unittest.mock import patch
import scan_runtime_dependencies as scanner


def component(name="fixture", version="1"):
    return {"group": "org.example", "name": name, "version": version,
            "purl": f"pkg:maven/org.example/{name}@{version}"}


class ScannerTest(unittest.TestCase):
    def test_clean_response(self):
        with patch.object(scanner, "request", return_value=[{}]):
            self.assertEqual([], scanner.scan([component()])[0]["vulnerability_ids"])

    def test_duplicate_components_queried_once(self):
        with patch.object(scanner, "request", return_value=[{}]) as request:
            self.assertEqual(1, len(scanner.scan([component(), component()])))
            self.assertEqual(1, len(request.call_args.args[0]))

    def test_paginated_findings_are_preserved(self):
        with patch.object(scanner, "request", side_effect=[
            [{"vulns": [{"id": "TEST-1"}], "next_page_token": "page2"}],
            [{"vulns": [{"id": "TEST-2"}, {"id": "TEST-1"}]}]
        ]) as request:
            self.assertEqual(["TEST-1", "TEST-2"], scanner.scan([component()])[0]["vulnerability_ids"])
            self.assertEqual("page2", request.call_args.args[0][0]["page_token"])

    def test_repeated_pagination_token_fails(self):
        with patch.object(scanner, "request", return_value=[{"next_page_token": "repeat"}]):
            with self.assertRaises(ValueError):
                scanner.scan([component()])

    def test_network_errors_do_not_pass(self):
        with patch.object(scanner, "request", side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                scanner.scan([component()])

    def test_native_only_inventory_is_not_clean_maven_scan(self):
        with self.assertRaises(ValueError):
            scanner.scan([{"name": "tor", "version": "1", "purl": "pkg:generic/tor@1"}])


if __name__ == "__main__":
    unittest.main()
