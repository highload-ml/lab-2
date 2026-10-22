#!/usr/bin/env python3
"""Registry B3 scenario through Gateway. Creates uniquely named demo data."""
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
user, _ = request("/api/v1/users", "POST", {"nickname": "registry_" + suffix, "password": "demo-password-123", "role": "ML_ENGINEER"}, 201)
project, _ = request("/api/v1/projects", "POST", {"name": "registry-" + suffix, "ownerId": user["id"]}, 201)
experiments = "/api/v1/projects/" + project["id"] + "/experiments"
experiment, _ = request(experiments, "POST", {"name": "baseline"}, 201)
versions = "/api/v1/projects/" + project["id"] + "/model-versions"


def source(name):
    run, _ = request("/api/v1/experiments/" + experiment["id"] + "/runs", "POST", {"authorId": user["id"], "name": name}, 201)
    path = "/api/v1/runs/" + run["id"]
    request(path + "/start", "POST")
    artifact, _ = request(path + "/artifacts", "POST", {"name": "model", "type": "MODEL", "path": "local/model.bin", "sizeBytes": 42}, 201)
    request(path + "/complete", "POST")
    return artifact


previous = None
for number in (1, 2):
    artifact = source("training-" + str(number))
    version, headers = request(versions, "POST", {"userId": user["id"], "artifactId": artifact["id"]}, 201)
    assert version["version"] == number and version["state"] == "NEW"
    assert headers["Location"].endswith(versions + "/" + version["id"])
    path = versions + "/" + version["id"]
    request(path + "/promote", "POST", {"userId": user["id"]}, 422)
    request(path + "/stage", "POST", {"userId": user["id"]})
    request(path + "/promote", "POST", {"userId": user["id"]})
    current, _ = request(versions + "/production")
    assert current["id"] == version["id"]
    if previous:
        old, _ = request(versions + "/" + previous)
        assert old["state"] == "ARCHIVED"
    request(versions, "POST", {"userId": user["id"], "artifactId": artifact["id"]}, 409)
    previous = version["id"]

# Historical source lookup must survive an experiment's soft deletion.
artifact = source("historical-training")
request(experiments + "/" + experiment["id"], "DELETE", expected=204)
request(versions, "POST", {"userId": user["id"], "artifactId": artifact["id"]}, 201)
rows, headers = request(versions + "?size=1")
assert len(rows) == 1 and rows[0]["version"] == 3 and headers["X-Total-Count"] == "3"
print("Registry B3 smoke checks passed")
