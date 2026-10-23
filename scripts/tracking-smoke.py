#!/usr/bin/env python3
"""B1–B2 HTTP scenario against the stack; leaves uniquely named demo data."""
import json
import os
import uuid
from urllib.error import HTTPError
from urllib.request import Request, build_opener, ProxyHandler

base = os.getenv("GATEWAY_URL", "http://localhost:8080").rstrip("/")
opener = build_opener(ProxyHandler({}))


def request(path, method="GET", body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    req = Request(base + path, data=data, headers={"Accept": "application/json", "Content-Type": "application/json"}, method=method)
    try:
        response = opener.open(req, timeout=15)
    except HTTPError as error:
        response = error
    with response:
        payload = response.read()
        assert response.code == expected, (path, response.code, payload.decode())
        return (json.loads(payload) if payload else None), response.headers


suffix = uuid.uuid4().hex[:12]
user, _ = request("/api/v1/users", "POST", {"nickname": "tracking_" + suffix, "password": "demo-password-123", "role": "ML_ENGINEER"}, 201)
project, _ = request("/api/v1/projects", "POST", {"name": "tracking-" + suffix, "ownerId": user["id"]}, 201)
experiments = "/api/v1/projects/" + project["id"] + "/experiments"
experiment, _ = request(experiments, "POST", {"name": "baseline"}, 201)
collection = "/api/v1/experiments/" + experiment["id"] + "/runs"
run, headers = request(collection, "POST", {"authorId": user["id"], "name": "training"}, 201)
assert headers["Location"].endswith("/api/v1/runs/" + run["id"])
path = "/api/v1/runs/" + run["id"]
request(path + "/start", "POST")
request(path + "/metrics/batch", "POST", {"metrics": [{"name": "loss", "value": 0.2, "step": 0}, {"name": "loss", "value": 0.1, "step": 1}]}, 201)
rows, headers = request(path + "/metrics?size=1")
assert len(rows) == 1 and headers["X-Total-Count"] == "2"
request(path + "/metrics/batch", "POST", {"metrics": [{"name": "accuracy", "value": 0.9, "step": 0}, {"name": "loss", "value": 0.1, "step": 1}]}, 409)
_, headers = request(path + "/metrics")
assert headers["X-Total-Count"] == "2", "Failed batch must be rolled back"
artifact, _ = request(path + "/artifacts", "POST", {"name": "model", "type": "MODEL", "path": "local/model.bin", "sizeBytes": 42}, 201)
request(path + "/complete", "POST")
request(path + "/metrics", "POST", {"name": "loss", "value": 0.05, "step": 2}, 422)
request(path + "/artifacts/" + artifact["id"])
rows, headers = request(collection + "?page=0&size=1")
assert len(rows) == 1 and headers["X-Has-Next"] == "false" and "X-Total-Count" not in headers
request("/internal/artifacts/" + artifact["id"], expected=404)
request(experiments + "/" + experiment["id"], "DELETE", expected=204)
request(collection, "POST", {"authorId": user["id"], "name": "after-delete"}, 404)
request(path)  # History survives experiment soft deletion.
print("Tracking B1–B2 smoke checks passed")
