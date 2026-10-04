"""Classify safe TORX_DIAG evidence. Missing remote logs never prove packet loss."""
import argparse
import json
import re
from pathlib import Path

FIELDS = re.compile(r"\b(event|relationship|conversation|delivery|sequence|transport|state|attempt|elapsedMs|present)=([A-Za-z0-9_]+)")


def parse(lines):
    return [dict(FIELDS.findall(line)) for line in lines if "TORX_DIAG" in line and "event=" in line]


def classify(events, paired_window=False):
    deliveries = {}
    for event in events:
        delivery = event.get("delivery", "none")
        if re.fullmatch(r"[a-f0-9]{12}", delivery):
            deliveries.setdefault(delivery, []).append(event)
    result = []
    for delivery, rows in sorted(deliveries.items()):
        relationship = next((e.get("relationship") for e in rows if e.get("relationship") not in (None, "none")), "none")
        if not re.fullmatch(r"[a-f0-9]{12}", relationship):
            relationship = "none"
        # Concurrent receipts/media/controls share a relationship. Its unscoped
        # failures cannot be attributed to every delivery in the capture.
        scoped = rows
        names = {e.get("event") for e in scoped}
        if "message_delivered" in names:
            state = "AUTHENTICATED_ACK_CONFIRMED"
        elif names & {"queue_auth_failure", "decrypt_failure", "connection_not_found"}:
            state = "D_RECEIVER_REJECTED"
        elif "ack_received" in names:
            state = "VERIFIED_ACK_COMMIT_UNCONFIRMED"
        elif "db_commit" in names and "ack_enqueued" in names:
            state = "E_REVERSE_ACK_UNCONFIRMED"
        elif any(e.get("event") == "route_lookup" and e.get("present") == "false" for e in scoped):
            state = "A_AUTHENTICATED_ROUTE_MISSING"
        elif "tor_connect_timeout" in names:
            state = "B_TOR_CONNECT_TIMEOUT"
        elif "transport_accepted" in names and "receiver_frame" not in names:
            state = "C_NO_RECEIVER_FRAME_IN_PAIRED_WINDOW" if paired_window else "REMOTE_CAPTURE_REQUIRED"
        elif "relationship_degraded" in names:
            state = "WAITING_FOR_PEER_REPAIR"
        else:
            state = "INSUFFICIENT_EVIDENCE"
        result.append({"delivery": delivery, "relationship": relationship, "classification": state})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logs", nargs="+", type=Path)
    parser.add_argument("--paired-complete-window", action="store_true", help="Both peers captured the complete same test window")
    args = parser.parse_args()
    events = parse(line for path in args.logs for line in path.read_text(encoding="utf-8", errors="replace").splitlines())
    print(json.dumps(classify(events, args.paired_complete_window), indent=2))


if __name__ == "__main__":
    main()
