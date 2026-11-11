"""Guard exact SQL row and audit-set scoring for the MySQL crash check."""

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import db_crash_check as check  # noqa: E402


class SnapshotValidation(unittest.TestCase):
    def setUp(self) -> None:
        self.ids = {f"id-{index}" for index in range(check.CLAIMS)}
        self.data = {
            "claims": [{"id": claim_id, "state": "SUBMITTED", "key": claim_id}
                       for claim_id in self.ids],
            "jobs": [{"claim_id": claim_id, "status": "PENDING", "attempts": "0"}
                     for claim_id in self.ids],
            "audit": [{"claim_id": claim_id, "action": "SUBMITTED"}
                      for claim_id in self.ids],
            "flyway_unsuccessful": 0,
        }

    def test_pending_set_and_final_state(self):
        self.assertTrue(check.validate_snapshot(self.data, self.ids, final=False)["ok"])
        for claim in self.data["claims"]:
            claim["state"] = "IN_REVIEW"
        for job in self.data["jobs"]:
            job["status"] = "DONE"
        self.data["audit"].extend(
            {"claim_id": claim_id, "action": "EXTRACTED"} for claim_id in self.ids)
        self.assertTrue(check.validate_snapshot(self.data, self.ids, final=True)["ok"])

    def test_duplicate_audit_or_extra_claim_cannot_pass(self):
        self.data["audit"].append({"claim_id": next(iter(self.ids)),
                                   "action": "SUBMITTED"})
        self.assertFalse(check.validate_snapshot(self.data, self.ids, final=False)["ok"])
        self.data["audit"].pop()
        self.data["claims"].append({"id": "unacknowledged", "state": "SUBMITTED",
                                     "key": "unacknowledged"})
        self.assertFalse(check.validate_snapshot(self.data, self.ids, final=False)["ok"])


if __name__ == "__main__":
    unittest.main()
