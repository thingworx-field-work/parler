"""Unit tests for describe_entity_schema evidence harvesting helpers."""

from __future__ import annotations

import unittest

from test_scripts.harvest_describe_entity_schema_from_stream import _entity_aliases, _scrub


class HarvestDescribeEntitySchemaTest(unittest.TestCase):
    def test_scrub_replaces_entity_name_in_description_without_broad_masking(self) -> None:
        aliases = _entity_aliases("PTC.MfgModel.DefaultWorkunit_TT")
        doc = {
            "entityName": "PTC.MfgModel.DefaultWorkunit_TT",
            "description": "Default template {PTC.MfgModel.DefaultWorkunit_TT} for work units",
            "items": [{"name": "PTCAddressLine1"}],
        }

        out = _scrub(doc, aliases)

        pseudo = aliases["PTC.MfgModel.DefaultWorkunit_TT"]
        self.assertEqual(out["entityName"], pseudo)
        self.assertEqual(out["description"], f"Default template {{{pseudo}}} for work units")
        self.assertEqual(out["items"][0]["name"], "PTCAddressLine1")


if __name__ == "__main__":
    unittest.main()
