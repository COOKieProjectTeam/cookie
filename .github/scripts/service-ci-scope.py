#!/usr/bin/env python3
"""Select service checks from their descriptors without a third-party YAML parser.

The `ci` value is a JSON object (also valid YAML flow syntax). Keeping this one
field JSON-compatible lets the first CI job validate it with Python's stdlib.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
NAME = re.compile(r"[a-z][a-z0-9_]*\Z")
FIELDS = {"check", "image", "runtime_inputs", "test_inputs", "descriptor_outputs"}
PROJECT_DEPENDENCY = re.compile(
    r'\b(api|implementation|runtimeOnly|compileOnly|testImplementation|testRuntimeOnly|testCompileOnly)'
    r'\s*\(\s*project\s*\(\s*"(:[a-zA-Z0-9:_-]+)"'
)


def validate_project_dependencies(root: Path, source: Path, ci: dict) -> None:
    """Reject forgotten direct Gradle module dependencies instead of skipping CI."""
    build = source.with_name("build.gradle.kts").read_text()
    for configuration, module in PROJECT_DEPENDENCY.findall(build):
        directory = module.lstrip(":").replace(":", "/")
        if directory.startswith(f"backend/services/{source.parent.name}/"):
            continue  # All source sets of this service are selected together.
        patterns = ci["runtime_inputs"]
        if configuration.startswith("test"):
            patterns = patterns + ci["test_inputs"]
        dependency_build = root / directory / "build.gradle.kts"
        source_sets = ("main",)
        if dependency_build.exists() and "kotlin-multiplatform" in dependency_build.read_text():
            source_sets = ("commonMain", "jvmMain")
        required = [f"{directory}/build.gradle.kts"] + [f"{directory}/src/{name}/kotlin/Dependency.kt" for name in source_sets]
        if any(not any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns) for path in required):
            raise ValueError(f"{source}: ci inputs omit {configuration} project dependency {module}")


def load_services(root: Path) -> list[dict]:
    workflow = (root / ".github/workflows/ci.yml").read_text()
    # A descriptor must select a real job exported by Detect changes, never a
    # boolean that no workflow reads. New services register one job and descriptor.
    exported = set(re.findall(r"^      ([a-z][a-z0-9_]*): \$\{\{ steps\.scope\.outputs\.\1 \}\}$", workflow, re.M))
    jobs = set(re.findall(r"^  ([a-z][a-z0-9_]*):$", workflow, re.M))
    services = []
    outputs = set()
    descriptors = sorted((root / "backend/services").glob("*/service.yaml"))
    if not descriptors:
        raise ValueError("No service descriptors found")
    for source in descriptors:
        text = source.read_text()
        service_id = source.parent.name
        if not re.search(rf"^id: {re.escape(service_id)}$", text, re.M):
            raise ValueError(f"{source}: descriptor id must match its directory")
        starts = list(re.finditer(r"^ci:\s*(?=\{)", text, re.M))
        if len(starts) != 1:
            raise ValueError(f"{source}: exactly one JSON-compatible ci mapping is required")
        ci, end = json.JSONDecoder().raw_decode(text[starts[0].end():])
        remainder = text[starts[0].end() + end:]
        if remainder and not remainder.startswith("\n"):
            raise ValueError(f"{source}: invalid content after ci mapping")
        if not isinstance(ci, dict) or set(ci) != FIELDS:
            raise ValueError(f"{source}: ci requires exactly {sorted(FIELDS)}")
        check = ci["check"]
        if not isinstance(check, str) or not NAME.fullmatch(check) or check not in exported or check not in jobs:
            raise ValueError(f"{source}: ci.check must name an exported CI job")
        if check in outputs:
            raise ValueError(f"{source}: duplicate CI output {check}")
        outputs.add(check)
        image = ci["image"]
        if image is not None and (not isinstance(image, str) or image not in exported or image != check + "_image"):
            raise ValueError(f"{source}: ci.image must name an exported service image flag or null")
        if image is not None:
            outputs.add(image)
        for field in ("runtime_inputs", "test_inputs", "descriptor_outputs"):
            if not isinstance(ci[field], list) or any(not isinstance(value, str) or not value for value in ci[field]):
                raise ValueError(f"{source}: ci.{field} must be a list of nonempty strings")
            if len(set(ci[field])) != len(ci[field]):
                raise ValueError(f"{source}: ci.{field} contains duplicates")
        for pattern in ci["runtime_inputs"] + ci["test_inputs"]:
            if pattern.startswith("/") or ".." in pattern.split("/") or "\n" in pattern:
                raise ValueError(f"{source}: invalid repository-relative CI input {pattern!r}")
        if any(output not in {"service_contracts", "compose"} for output in ci["descriptor_outputs"]):
            raise ValueError(f"{source}: unsupported descriptor output")
        validate_project_dependencies(root, source, ci)
        services.append({"id": service_id, **ci})
    return services


def select(services: list[dict], paths: list[str]) -> set[str]:
    selected = set()
    for path in paths:
        for service in services:
            own = path.startswith(f"backend/services/{service['id']}/")
            if own:
                selected.add(service["check"])
                if path == f"backend/services/{service['id']}/service.yaml":
                    selected.update(service["descriptor_outputs"])
                if service["image"] and ("/src/main/" in path or path.endswith("/build.gradle.kts")):
                    selected.add(service["image"])
            # Platform test implementations don't enter consumer artifacts or test
            # classpaths. Their own check is selected by detect-ci-scope.sh.
            if path.startswith("backend/platform/") and "/src/test/" in path:
                continue
            if any(fnmatch.fnmatchcase(path, pattern) for pattern in service["runtime_inputs"]):
                selected.add(service["check"])
                if service["image"]:
                    selected.add(service["image"])
            if any(fnmatch.fnmatchcase(path, pattern) for pattern in service["test_inputs"]):
                selected.add(service["check"])
    return selected


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--inventory", action="store_true")
    args = parser.parse_args()
    try:
        services = load_services(ROOT)
        if args.inventory:
            for service in services:
                print(service["check"], service["image"] or "-", sep="\t")
        else:
            paths = sys.stdin.buffer.read().decode().split("\0")
            for output in sorted(select(services, [path for path in paths if path])):
                print(output)
    except (ValueError, OSError) as error:
        print(f"Invalid CI service metadata: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
