#!/usr/bin/env python3
"""Behavioral tests for descriptor-driven service fan-out."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("service_ci_scope", Path(__file__).with_name("service-ci-scope.py"))
scope = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(scope)


class ServiceScopeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        workflow = self.root / ".github/workflows/ci.yml"
        workflow.parent.mkdir(parents=True)
        workflow.write_text("jobs:\n  changes:\n    outputs:\n      example: ${{ steps.scope.outputs.example }}\n"
                            "      example_image: ${{ steps.scope.outputs.example_image }}\n  example:\n    steps: []\n")
        self.descriptor = self.root / "backend/services/example/service.yaml"
        self.descriptor.parent.mkdir(parents=True)
        self.descriptor.with_name("build.gradle.kts").write_text("// Synthetic service build.\n")
        self.ci = {"check": "example", "image": "example_image",
                   "runtime_inputs": ["backend/platform/starter-web/**"],
                   "test_inputs": ["backend/platform/starter-testing/**", "backend/services/dependency/src/main/**"],
                   "descriptor_outputs": ["service_contracts"]}
        self.save()

    def save(self):
        self.descriptor.write_text("schema_version: 1\nid: example\nci: " + json.dumps(self.ci, indent=2) + "\n")

    def selected(self, path):
        return scope.select(scope.load_services(self.root), [path])

    def test_new_descriptor_selects_shared_runtime_dependency_without_selector_edits(self):
        self.assertEqual({"example", "example_image"}, self.selected("backend/platform/starter-web/src/main/kotlin/New.kt"))

    def test_test_dependency_selects_check_without_rebuilding_image(self):
        self.assertEqual({"example"}, self.selected("backend/platform/starter-testing/src/main/kotlin/Fixture.kt"))

    def test_platform_own_test_does_not_select_consumer(self):
        self.assertEqual(set(), self.selected("backend/platform/starter-web/src/test/kotlin/Test.kt"))

    def test_test_only_process_dependency_selects_check_without_image(self):
        self.assertEqual({"example"}, self.selected("backend/services/dependency/src/main/kotlin/App.kt"))

    def test_own_source_tests_and_descriptor_have_distinct_effects(self):
        self.assertEqual({"example", "example_image"}, self.selected("backend/services/example/src/main/kotlin/App.kt"))
        self.assertEqual({"example"}, self.selected("backend/services/example/src/test/kotlin/Test.kt"))
        self.assertEqual({"example", "service_contracts"}, self.selected("backend/services/example/service.yaml"))

    def test_missing_descriptor_metadata_fails_closed(self):
        self.descriptor.write_text("schema_version: 1\nid: example\n")
        with self.assertRaisesRegex(ValueError, "ci mapping"):
            scope.load_services(self.root)

    def test_unknown_workflow_job_fails_closed(self):
        self.ci["check"] = "unregistered"
        self.save()
        with self.assertRaisesRegex(ValueError, "exported CI job"):
            scope.load_services(self.root)

    def test_invalid_dependency_and_extra_fields_fail_closed(self):
        self.ci["runtime_inputs"] = ["../external/**"]
        self.save()
        with self.assertRaisesRegex(ValueError, "repository-relative"):
            scope.load_services(self.root)
        self.ci["runtime_inputs"] = []
        self.ci["typo"] = True
        self.save()
        with self.assertRaisesRegex(ValueError, "requires exactly"):
            scope.load_services(self.root)

    def test_repository_descriptors_select_every_platform_runtime_consumer(self):
        services = scope.load_services(scope.ROOT)
        selected = scope.select(services, ["backend/platform/starter-web/src/main/kotlin/Changed.kt"])
        self.assertTrue({"identity", "notification", "user", "nutrition"}.issubset(selected))

    def test_omitted_gradle_dependency_fails_instead_of_silently_skipping_consumer(self):
        self.descriptor.with_name("build.gradle.kts").write_text(
            'dependencies { implementation(project(":backend:platform:starter-postgres")) }\n')
        with self.assertRaisesRegex(ValueError, "omit implementation project dependency"):
            scope.load_services(self.root)

    def test_runtime_dependency_cannot_be_declared_as_test_only(self):
        self.descriptor.with_name("build.gradle.kts").write_text(
            'dependencies { implementation(project(":backend:platform:starter-web")) }\n')
        self.ci["runtime_inputs"] = []
        self.ci["test_inputs"] = ["backend/platform/starter-web/**"]
        self.save()
        with self.assertRaisesRegex(ValueError, "omit implementation project dependency"):
            scope.load_services(self.root)


if __name__ == "__main__":
    unittest.main()
