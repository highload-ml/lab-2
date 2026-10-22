#!/usr/bin/env python3
"""Developer 1 stack smoke test; uses only the Python standard library."""
import json
import os
import time
import uuid
from urllib.request import Request, urlopen, build_opener, ProxyHandler, install_opener
from urllib.error import HTTPError, URLError

# Local smoke probes must not use the workstation HTTP proxy.
install_opener(build_opener(ProxyHandler({})))

GATEWAY = os.getenv("GATEWAY_URL", "http://localhost:8080")
CONFIG = os.getenv("CONFIG_SERVER_URL", "http://localhost:8888")
EUREKA = os.getenv("EUREKA_URL", "http://localhost:8761")

def request(base, path, method="GET", body=None, expected=200):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    try:
        response = urlopen(Request(base + path, data=data, headers=headers, method=method), timeout=10)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read()
        assert response.code == expected, (path, response.code, payload.decode())
        return (json.loads(payload) if payload else None), response.headers

# Do not mistake process health for discovery readiness.
for attempt in range(60):
    try:
        apps, _ = request(EUREKA, "/eureka/apps")
        names = {item["name"] for item in apps["applications"]["application"]}
        assert {"PROJECT-SERVICE", "TRACKING-SERVICE", "REGISTRY-SERVICE", "API-GATEWAY"} <= names
        request(GATEWAY, "/api/v1/projects")
        break
    except (AssertionError, URLError, KeyError):
        if attempt == 59:
            raise
        time.sleep(2)

for name in ("project-service", "tracking-service", "registry-service", "api-gateway"):
    data, _ = request(CONFIG, "/" + name + "/default")
    assert data["name"] == name and data["propertySources"]
for name in ("project", "tracking", "registry"):
    spec, _ = request(GATEWAY, "/v3/api-docs/" + name)
    assert "openapi" in spec
request(GATEWAY, "/internal/projects/" + str(uuid.uuid4()), expected=404)

suffix = uuid.uuid4().hex[:12]
user, _ = request(GATEWAY, "/api/v1/users", "POST",
                  {"nickname": "smoke_" + suffix, "password": "smoke-password-123", "role": "ML_ENGINEER"}, 201)
project, _ = request(GATEWAY, "/api/v1/projects", "POST",
                     {"name": "smoke-" + suffix, "ownerId": user["id"]}, 201)
path = "/api/v1/projects/" + project["id"] + "/experiments"
experiment, headers = request(GATEWAY, path, "POST", {"name": "baseline"}, 201)
assert headers["Location"].startswith(GATEWAY + path + "/"), headers["Location"]
rows, headers = request(GATEWAY, path + "?size=100")
assert len(rows) == 1 and headers["X-Total-Count"] == "1"
request(GATEWAY, path + "/" + experiment["id"], "DELETE", expected=204)
request(GATEWAY, path + "/" + experiment["id"], expected=404)
rows, headers = request(GATEWAY, path)
assert rows == [] and headers["X-Total-Count"] == "0"
request(GATEWAY, "/api/v1/projects/" + project["id"] + "/members/" + user["id"], "DELETE", expected=422)
print("Developer 1 smoke checks passed; tracking/registry business APIs belong to B1–B5.")
