import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "deploy/docker/verify-pinned-images.sh"
DIGEST = "sha256:" + "a" * 64


class PinnedImagesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="cookie-image-pins-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.calls = self.directory / "calls"
        self.fixture = self.directory / "images.yaml"
        docker = self.directory / "docker"
        docker.write_text(
            '#!/usr/bin/env bash\n'
            'printf "%s\\n" "$@" >> "$MOCK_DOCKER_CALLS"\n'
            'exit "${MOCK_DOCKER_STATUS:-0}"\n'
        )
        docker.chmod(0o755)

    def verify(self, content, status=0):
        self.calls.write_text("")
        self.fixture.write_text(content)
        environment = os.environ.copy()
        environment.update(
            PATH=f"{self.directory}{os.pathsep}{environment['PATH']}",
            MOCK_DOCKER_CALLS=str(self.calls),
            MOCK_DOCKER_STATUS=str(status),
        )
        return subprocess.run(
            ["bash", str(SCRIPT), str(self.fixture)],
            env=environment, text=True, capture_output=True, timeout=5,
        )

    def test_inspection_preserves_digest_and_registry_port(self):
        for source, repository in (
            ("docker/dockerfile:1", "docker/dockerfile"),
            ("docker/dockerfile", "docker/dockerfile"),
            ("localhost:5000/team/image:v2", "localhost:5000/team/image"),
            ("localhost:5000/team/image", "localhost:5000/team/image"),
            ("registry.example/team/image:v3", "registry.example/team/image"),
            ("image:v4", "image"),
        ):
            with self.subTest(source=source):
                result = self.verify(f"image: {source}@{DIGEST}\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(
                    self.calls.read_text().splitlines(),
                    ["manifest", "inspect", f"{repository}@{DIGEST}"],
                )

    def test_dockerfile_and_compose_references_are_all_checked(self):
        result = self.verify(
            f"# syntax=docker/dockerfile:1@{DIGEST}\n"
            f"FROM example/build:2@{DIGEST} AS build\n"
            f"  image: example/runtime:3@{DIGEST}\n"
            f"FROM example/build:2@{DIGEST}\n"
            "  image: ${IDENTITY_IMAGE:?image is required}\n"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            self.calls.read_text().splitlines(),
            [
                part
                for repository in ("docker/dockerfile", "example/build", "example/runtime")
                for part in ("manifest", "inspect", f"{repository}@{DIGEST}")
            ],
        )

    def test_missing_or_malformed_digest_is_rejected(self):
        for reference in ("image:latest", "image@sha256:abc", "image@sha256:" + "A" * 64):
            with self.subTest(reference=reference):
                result = self.verify(f"image: {reference}\n")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("reference is not digest-pinned", result.stderr)
                self.assertEqual(self.calls.read_text(), "")

    def test_manifest_failure_is_not_ignored(self):
        result = self.verify(f"image: example/image:1@{DIGEST}\n", status=42)
        self.assertEqual(result.returncode, 42)
        self.assertEqual(
            self.calls.read_text().splitlines(),
            ["manifest", "inspect", f"example/image@{DIGEST}"],
        )


if __name__ == "__main__":
    unittest.main()
