"""Behavioral checks for explorer discovery, contract fidelity and offline safety."""
import importlib.util
import json
from pathlib import Path, PureWindowsPath
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

BUILD_PATH = Path(__file__).resolve().parents[1] / "build.py"
SPEC = importlib.util.spec_from_file_location("explorer_build", BUILD_PATH)
build = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(build)


class ExplorerBuildTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="cookie-explorer-test-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write("docs/architecture/model/services.yaml", {
            "updated_at": "2026-09-17", "services": [{"id": "alpha", "name": "Alpha"}, {"id": "roadmap", "name": "Roadmap"}],
            "infrastructure": {"event_bus": {"product": "nats_jetstream"}},
            "external_systems": [{"id": "smtp", "name": "SMTP"}],
        })
        self.write("docs/architecture/model/events.yaml", {
            "updated_at": "2026-09-17", "envelope": {"required": {"event_id": "uuid"}},
            "events": [{"type": "alpha.changed", "version": 1, "producer": "alpha", "consumers": ["roadmap"]}],
        })
        self.write("docs/architecture/model/sync-calls.yaml", {"calls": [{"caller": "alpha", "callees": ["roadmap", "smtp"]}]})
        self.write("contracts/openapi/runtime.yaml", {
            "openapi": "3.0.3", "paths": {"/readyz": {"get": {
                "operationId": "readiness", "security": [], "responses": {"200": {"description": "Ready"}},
            }}},
        })
        self.add_service("alpha", "getAlpha")
        self.write("docs/architecture/explorer/template.html", "<!doctype html>\n<!-- EXPLORER_STYLE -->\n<!-- EXPLORER_DATA -->\n<!-- EXPLORER_SCRIPT -->\n")
        self.write("docs/architecture/explorer/style.css", "body { color: red; }")
        self.write("docs/architecture/explorer/app.js", "window.rendered = true;")

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value if isinstance(value, str) else json.dumps(value), encoding="utf-8")
        return path

    def add_service(self, name, operation_id):
        self.write(f"backend/services/{name}/service.yaml", {
            "id": name, "public_openapi": {"source": f"contracts/openapi/public/{name}.yaml"},
            "runtime_openapi": "contracts/openapi/runtime.yaml",
            "messaging": {"publishes": [{"type": f"{name}.changed", "version": 1}]},
        })
        self.write(f"contracts/openapi/public/{name}.yaml", {
            "openapi": "3.0.3", "servers": [{"url": "https://example.test/v1"}],
            "paths": {f"/{name}": {"get": {"operationId": operation_id, "responses": {"200": {"description": "OK"}}}}},
        })

    def test_new_descriptor_is_discovered_without_ui_registration(self):
        initial = build.build_data(self.root)
        self.assertEqual({service["id"]: service["status"] for service in initial["services"]}, {"alpha": "implemented", "roadmap": "planned"})
        # A roadmap contract cannot turn a target service into an implemented one.
        self.write("contracts/openapi/public/roadmap.yaml", {"openapi": "3.0.3", "paths": {}})
        self.add_service("beta", "getBeta")
        changed = build.build_data(self.root)
        self.assertEqual(next(service for service in changed["services"] if service["id"] == "beta")["status"], "implemented")
        self.assertEqual({operation["id"] for operation in changed["operations"]}, {"getAlpha", "alpha:readiness", "getBeta", "beta:readiness"})
        probe = next(operation for operation in changed["operations"] if operation["id"] == "beta:readiness")
        self.assertEqual((probe["visibility"], probe["path"]), ("internal", "/readyz"))
        self.assertEqual(changed["events"][0]["subject"], "cookie.events.alpha.changed.v1")
        self.assertEqual(changed["events"][0]["status"], "implemented")
        self.assertEqual([call["status"] for call in changed["calls"]], ["planned", "implemented"])
        self.assertEqual(changed["externalSystems"][0]["id"], "smtp")

    def test_relative_ref_uses_uri_paths_even_with_windows_native_paths(self):
        self.write("contracts/openapi/common.yaml", {"Id": {"type": "string", "format": "uuid"}})
        repository = build.Repository(self.root)
        document = "contracts/openapi/public/alpha.yaml"
        # Simulate Windows only at the URI/native-path boundary; filesystem I/O
        # continues on the test host. The same fixture also runs on Windows.
        with patch.object(build, "Path", side_effect=lambda value:
                          PureWindowsPath(value) if str(value) == document else Path(value)):
            resolved = repository.resolve({"$ref": "../common.yaml#/Id"}, document)
        self.assertEqual((resolved["type"], resolved["format"]), ("string", "uuid"))
        self.assertEqual(resolved["x-explorer-source"]["path"], "contracts/openapi/common.yaml")

    def test_local_refs_parameters_security_and_schema_constraints_survive(self):
        self.write("contracts/openapi/common.yaml", {
            "Id": {"name": "id", "in": "path", "required": True, "schema": {"type": "string", "pattern": "^[a-z]+$"}},
            "Payload": {"type": "object", "required": ["choice"], "additionalProperties": False, "properties": {
                "choice": {"nullable": True, "oneOf": [{"type": "string", "enum": ["yes", "no"]}, {"type": "integer", "minimum": 1}]},
                "label": {"allOf": [{"type": "string", "maxLength": 20}], "description": "Preserve me"},
            }},
        })
        self.write("contracts/openapi/public/alpha.yaml", {
            "openapi": "3.0.3", "servers": [{"url": "https://example.test/v1"}],
            "security": [{"accessToken": ["read"]}],
            "components": {"securitySchemes": {"accessToken": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}}},
            "paths": {"/alpha/{id}": {
                "parameters": [{"$ref": "../common.yaml#/Id"}, {"name": "limit", "in": "query", "schema": {"type": "integer", "maximum": 100}}],
                "get": {"operationId": "getAlpha", "parameters": [{"name": "limit", "in": "query", "schema": {"type": "integer", "maximum": 10}}],
                        "responses": {"200": {"description": "OK", "headers": {"Cache-Control": {"schema": {"type": "string", "example": "no-store"}}},
                                              "content": {"application/json": {"schema": {"$ref": "../common.yaml#/Payload"}, "example": {"choice": "yes"}}}}}},
                "post": {"operationId": "postAlpha", "security": [], "requestBody": {"required": True, "content": {"application/json": {"schema": {"$ref": "../common.yaml#/Payload"}, "examples": {"valid": {"value": {"choice": 2}}}}}},
                         "responses": {"204": {"description": "Saved"}}},
            }},
        })
        data = build.build_data(self.root)
        get = next(operation for operation in data["operations"] if operation["id"] == "getAlpha")
        post = next(operation for operation in data["operations"] if operation["id"] == "postAlpha")
        self.assertEqual(get["path"], "/v1/alpha/{id}")
        self.assertEqual(get["security"], [{"accessToken": ["read"]}])
        self.assertEqual(post["security"], [])
        self.assertEqual(get["securitySchemes"]["accessToken"]["bearerFormat"], "JWT")
        self.assertEqual(get["parameters"][0]["schema"]["pattern"], "^[a-z]+$")
        self.assertEqual(get["parameters"][1]["schema"]["maximum"], 10)
        payload = get["responses"]["200"]["content"]["application/json"]
        self.assertFalse(payload["schema"]["additionalProperties"])
        self.assertTrue(payload["schema"]["properties"]["choice"]["nullable"])
        self.assertEqual(payload["schema"]["properties"]["choice"]["oneOf"][1]["minimum"], 1)
        self.assertEqual(payload["schema"]["properties"]["label"]["allOf"][0]["maxLength"], 20)
        self.assertEqual(payload["example"], {"choice": "yes"})
        self.assertEqual(get["responses"]["200"]["headers"]["Cache-Control"]["schema"]["example"], "no-store")
        self.assertEqual(post["requestBody"]["content"]["application/json"]["examples"]["valid"]["value"]["choice"], 2)
        self.assertIn("contracts/openapi/common.yaml", data["sources"])

    def test_recursive_refs_are_bounded_and_keep_the_cycle_target(self):
        self.write("contracts/openapi/recursive.yaml", {"Node": {"type": "object", "properties": {"next": {"$ref": "#/Node", "nullable": True}}}})
        repository = build.Repository(self.root)
        resolved = repository.resolve({"$ref": "#/Node"}, "contracts/openapi/recursive.yaml")
        child = resolved["properties"]["next"]
        self.assertTrue(child["x-explorer-recursive"])
        self.assertTrue(child["nullable"])
        self.assertEqual(child["$ref"], "contracts/openapi/recursive.yaml#/Node")

    def test_referenced_path_items_keep_actual_operation_source_locations(self):
        self.write("contracts/openapi/common.yaml", "\n\nAlphaPath:\n  get:\n    operationId: getAlpha\n    responses:\n      '200':\n        description: OK\n")
        self.write("contracts/openapi/public/alpha.yaml", {"openapi": "3.0.3", "paths": {"/alpha": {"$ref": "../common.yaml#/AlphaPath"}}})
        operation = next(operation for operation in build.build_data(self.root)["operations"] if operation["id"] == "getAlpha")
        self.assertEqual(operation["source"], {"path": "contracts/openapi/common.yaml", "line": 5})

    def test_yaml_string_enums_and_duplicate_keys_do_not_silently_change(self):
        self.write("contracts/openapi/enum.yaml", "type: string\nenum: [yes, no, on, off]\nnullable: true\n")
        schema = build.Repository(self.root).document("contracts/openapi/enum.yaml")
        self.assertEqual(schema["enum"], ["yes", "no", "on", "off"])
        self.assertIs(schema["nullable"], True)
        self.write("contracts/openapi/duplicate.yaml", "maximum: 2\nmaximum: 20\n")
        with self.assertRaises(build.BuildError):
            build.Repository(self.root).document("contracts/openapi/duplicate.yaml")

    def test_ref_traversal_remote_refs_missing_refs_and_secret_sources_fail(self):
        repository = build.Repository(self.root)
        for reference in ("https://example.test/schema.yaml", "../../../../private.yaml#/X", "#/missing"):
            with self.subTest(reference=reference), self.assertRaises(build.BuildError):
                repository.resolve({"$ref": reference}, "contracts/openapi/public/alpha.yaml")
        self.write("deploy/production/.env.example", "PASSWORD=do-not-embed")
        self.write("deploy/production/secrets/credentials.json", "{}")
        for path in ("deploy/production/.env.example", "deploy/production/secrets/credentials.json"):
            with self.subTest(path=path), self.assertRaises(build.BuildError):
                repository.read_source(path)

    def test_details_validate_operation_ids_nodes_and_line_locations(self):
        path = "docs/architecture/explorer/details/alpha.json"
        detail = {"service": "alpha", "flows": [{"id": "alpha-read", "operationIds": ["getAlpha"],
                   "steps": [{"node": "alpha", "source": {"path": "backend/services/alpha/service.yaml", "line": 1}}]}]}
        self.write(path, detail)
        self.assertEqual(len(build.build_data(self.root)["details"]), 1)
        for field, replacement in (("operation", "missing"), ("node", "missing"), ("line", 999)):
            broken = json.loads(json.dumps(detail))
            if field == "operation":
                broken["flows"][0]["operationIds"] = [replacement]
            elif field == "node":
                broken["flows"][0]["steps"][0]["node"] = replacement
            else:
                broken["flows"][0]["steps"][0]["source"]["line"] = replacement
            self.write(path, broken)
            with self.subTest(field=field), self.assertRaises(build.BuildError):
                build.build_data(self.root)

    def test_deterministic_output_check_detects_drift_without_writing(self):
        self.assertEqual(len(build.generate(self.root)), 2)
        data = self.root / build.EXPLORER / "data.js"
        index = self.root / build.EXPLORER / "index.html"
        first = data.read_bytes(), index.read_bytes()
        self.assertEqual(build.generate(self.root), [])
        self.assertEqual(build.generate(self.root, check=True), [])
        self.assertEqual(first, (data.read_bytes(), index.read_bytes()))
        self.write("docs/architecture/explorer/style.css", "body { color: blue; }")
        self.assertEqual(len(build.generate(self.root, check=True)), 2)
        self.assertEqual(first, (data.read_bytes(), index.read_bytes()))
        check = subprocess.run([sys.executable, str(BUILD_PATH), "--root", str(self.root), "--check"], capture_output=True, text=True)
        self.assertEqual(check.returncode, 1)
        self.assertIn("stale", check.stderr)

    def test_cross_file_flow_links_allow_forward_refs_and_reject_unknown_ids(self):
        first = {"serviceId": "alpha", "flows": [{"id": "first", "operationIds": ["getAlpha"], "nextFlows": ["second"], "steps": []}],
                 "operationNotes": {"getAlpha": {"flowId": "second", "notes": []}}}
        second = {"serviceId": "infrastructure", "flows": [{"id": "second", "operationIds": [], "steps": []}]}
        self.write("docs/architecture/explorer/details/a.json", first)
        self.write("docs/architecture/explorer/details/z.json", second)
        self.assertEqual(len(build.build_data(self.root)["details"]), 2)
        for field in ("flowId", "nextFlows"):
            broken = json.loads(json.dumps(first))
            if field == "flowId":
                broken["operationNotes"]["getAlpha"]["flowId"] = "unknown"
            else:
                broken["flows"][0]["nextFlows"] = ["unknown"]
            self.write("docs/architecture/explorer/details/a.json", broken)
            with self.subTest(field=field), self.assertRaisesRegex(build.BuildError, field):
                build.build_data(self.root)

    def test_fact_ids_are_required_and_unique_across_detail_files(self):
        self.write("docs/architecture/explorer/details/a.json", {"facts": [{"id": "shared-fact", "value": "First"}]})
        self.write("docs/architecture/explorer/details/z.json", {"facts": [{"id": "second-fact", "value": "Second"}]})
        self.assertEqual(len(build.build_data(self.root)["details"]), 2)
        for fact in ({"id": "shared-fact"}, {"value": "Missing id"}):
            self.write("docs/architecture/explorer/details/z.json", {"facts": [fact]})
            with self.subTest(fact=fact), self.assertRaisesRegex(build.BuildError, "facts id"):
                build.build_data(self.root)

    def test_embedded_source_cannot_close_the_script_element(self):
        js = build.javascript({"source": "</script><script>alert('unexpected')</script>\u2028"})
        self.assertNotIn("</script", js)
        self.assertIn("<\\/script", js)
        self.assertIn("\\u2028", js)

    def test_template_markers_inside_embedded_sources_are_not_replaced(self):
        data_js = build.javascript({"source": "<!-- EXPLORER_SCRIPT -->"})
        self.write("docs/architecture/explorer/app.js", 'const tag = "</ScRiPt>";')
        result = build.render_index(self.root / build.EXPLORER, data_js)
        self.assertIn('<!-- EXPLORER_SCRIPT -->', result)
        self.assertEqual(result.count('const tag = "<\\/ScRiPt>"'), 1)

    @unittest.skipUnless(shutil.which("node"), "Node is required to check JavaScript runtime semantics")
    def test_javascript_preserves_special_json_property_names(self):
        data = {"properties": {"__proto__": {"type": "string"}, "constructor": {"type": "integer"}}}
        script = "global.window = {};\n" + build.javascript(data) + "\nconsole.log(JSON.stringify({keys: Object.keys(window.ARCHITECTURE_DATA.properties), value: window.ARCHITECTURE_DATA.properties.__proto__.type}));"
        result = subprocess.run(["node", "--input-type=commonjs", "-e", script], capture_output=True, text=True, check=True)
        self.assertEqual(json.loads(result.stdout), {"keys": ["__proto__", "constructor"], "value": "string"})


if __name__ == "__main__":
    unittest.main()
