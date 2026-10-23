"""Encode only the fixed synthetic ticket accepted by the offline PFC fixture."""
import base64
import json

value = {"identifier": {"ssn": "demo-ssn", "iPin": "demo-ipin"},
         "ticketIds": ["demo-mfa-ticket"], "code": "demo-code"}
encoded = base64.urlsafe_b64encode(json.dumps(value, separators=(",", ":")).encode()).decode().rstrip("=")
assert json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))) == value
print(encoded)
