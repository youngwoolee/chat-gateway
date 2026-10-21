"""Offline mock only: encode synthetic SHINHAN results for the supplied authReqIds."""
import base64
import json
import re
import sys

ids = sys.argv[1:]
if not 1 <= len(ids) <= 8 or len(set(ids)) != len(ids) or any(not re.fullmatch(r"ar_[A-Za-z0-9_-]{43}", value) for value in ids):
    sys.exit("Usage: python3 src/offline/http/encode-offline-mfa.py ar_<43 chars> [ar_<43 chars> ...]")
values = [{"authReqId": value, "tranID": "local:" + value, "method": "SHINHAN"} for value in ids]
print(base64.urlsafe_b64encode(json.dumps(values, separators=(",", ":")).encode()).decode().rstrip("="))
