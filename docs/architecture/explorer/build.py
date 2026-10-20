#!/usr/bin/env python3
"""Build the offline architecture explorer from repository-owned contracts.

Run with the environment described in README.md. No network requests, repository
credentials, or current timestamps enter the generated artifact.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any
from urllib.parse import unquote, urlsplit

try:
    import yaml
except ImportError:
    raise SystemExit("Install explorer/requirements.txt into an isolated Python environment first.")

EXPLORER = Path("docs/architecture/explorer")
MODEL = Path("docs/architecture/model")
METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")
SOURCE_ROOTS = ("backend/", "contracts/", "docs/architecture/", "docs/adr/", "deploy/", "infra/terraform/")
SOURCE_SUFFIXES = {".kt", ".kts", ".yaml", ".yml", ".json", ".md", ".sql", ".dbml", ".mmd", ".sh", ".tf", ".tftpl", ".conf", ".py"}
NODE_ALIASES = {"mobile_client", "gateway", "identity_db", "notification_db", "nats", "redis", "smtp", "apns", "fcm", "object_storage"}


class BuildError(ValueError):
    """Invalid or unsafe repository input."""


class UniqueKeyLoader(yaml.SafeLoader):
    pass


# OpenAPI uses YAML 1.2 booleans. PyYAML's default YAML 1.1 resolver otherwise
# silently changes enum values such as `yes`, `no`, `on` and `off` into booleans.
UniqueKeyLoader.yaml_implicit_resolvers = {
    initial: [(tag, expression) for tag, expression in resolvers if tag != "tag:yaml.org,2002:bool"]
    for initial, resolvers in yaml.SafeLoader.yaml_implicit_resolvers.items()
}
UniqueKeyLoader.add_implicit_resolver("tag:yaml.org,2002:bool", re.compile(r"^(?:true|True|TRUE|false|False|FALSE)$"), list("tTfF"))


def unique_mapping(loader: UniqueKeyLoader, node: yaml.MappingNode, deep: bool = False) -> dict:
    loader.flatten_mapping(node)
    result = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in result:
            raise BuildError(f"Duplicate YAML key {key!r} at line {key_node.start_mark.line + 1}")
        result[key] = loader.construct_object(value_node, deep=deep)
    return result


UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def plain(value: Any, ancestors: frozenset[int] = frozenset()) -> Any:
    if isinstance(value, (dict, list)):
        if id(value) in ancestors:
            raise BuildError("Recursive YAML aliases are not supported")
        ancestors = ancestors | {id(value)}
    if isinstance(value, dict):
        return {str(key): plain(child, ancestors) for key, child in value.items()}
    if isinstance(value, list):
        return [plain(child, ancestors) for child in value]
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.isoformat()
    return value


class Repository:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.sources: dict[str, str] = {}
        self.documents: dict[str, Any] = {}
        self.lines: dict[str, dict[tuple[str, ...], int]] = {}
        self.origins: dict[int, tuple[str, tuple[str, ...]]] = {}

    def path(self, relative: str | Path) -> Path:
        name = str(relative)
        path = Path(name)
        if path.is_absolute() or "\\" in name or "\x00" in name:
            raise BuildError(f"Source path must be repository-relative: {name}")
        resolved = (self.root / path).resolve()
        if not resolved.is_relative_to(self.root):
            raise BuildError(f"Source escapes repository: {name}")
        return resolved

    def relative(self, path: Path) -> str:
        return path.relative_to(self.root).as_posix()

    def read_source(self, relative: str | Path) -> str:
        path = self.path(relative)
        name = self.relative(path)
        if not name.startswith(SOURCE_ROOTS) or path.suffix not in SOURCE_SUFFIXES:
            raise BuildError(f"Unsupported source file: {name}")
        if any(part.startswith(".") or part in {"build", "secrets", "node_modules", "__pycache__"} for part in Path(name).parts):
            raise BuildError(f"Private/generated source cannot be embedded: {name}")
        if path.stat().st_size > 1_000_000:
            raise BuildError(f"Source exceeds 1 MB: {name}")
        text = path.read_text(encoding="utf-8")
        if "\x00" in text:
            raise BuildError(f"Binary source cannot be embedded: {name}")
        self.sources[name] = text
        return text

    def document(self, relative: str | Path) -> Any:
        name = self.relative(self.path(relative))
        if name not in self.documents:
            text = self.read_source(name)
            try:
                parsed = yaml.load(text, Loader=UniqueKeyLoader)
                node = yaml.compose(text, Loader=yaml.SafeLoader)
            except yaml.YAMLError as error:
                raise BuildError(f"Invalid YAML in {name}: {error}") from error
            self.documents[name] = plain(parsed)
            locations: dict[tuple[str, ...], int] = {}

            def visit(current: yaml.Node, pointer: tuple[str, ...], ancestors: frozenset[int]) -> None:
                locations[pointer] = current.start_mark.line + 1
                if id(current) in ancestors:
                    return
                ancestors = ancestors | {id(current)}
                if isinstance(current, yaml.MappingNode):
                    for key, value in current.value:
                        visit(value, pointer + (str(key.value),), ancestors)
                elif isinstance(current, yaml.SequenceNode):
                    for index, value in enumerate(current.value):
                        visit(value, pointer + (str(index),), ancestors)

            if node is not None:
                visit(node, (), frozenset())
            self.lines[name] = locations
        return self.documents[name]

    def source(self, relative: str | Path, *pointer: str) -> dict[str, Any]:
        name = self.relative(self.path(relative))
        self.document(name)
        return {"path": name, "line": self.lines[name].get(tuple(pointer), 1)}

    def resolve(self, value: Any, document_path: str, stack: tuple[tuple[str, str], ...] = (),
                location: tuple[str, ...] = ()) -> Any:
        if isinstance(value, list):
            return [self.resolve(child, document_path, stack, location + (str(index),)) for index, child in enumerate(value)]
        if not isinstance(value, dict):
            return value
        if "$ref" not in value:
            result = {key: self.resolve(child, document_path, stack, location + (key,)) for key, child in value.items()}
            self.origins[id(result)] = (document_path, location)
            return result
        reference = value["$ref"]
        if not isinstance(reference, str):
            raise BuildError(f"Non-string $ref in {document_path}")
        parsed = urlsplit(reference)
        if parsed.scheme or parsed.netloc or parsed.query:
            raise BuildError(f"Only local $refs are supported: {reference}")
        target_path = self.path(Path(document_path).parent / unquote(parsed.path)) if parsed.path else self.path(document_path)
        target_name = self.relative(target_path)
        if not target_name.startswith("contracts/openapi/") or target_path.suffix not in {".yaml", ".yml", ".json"}:
            raise BuildError(f"$ref must target an OpenAPI source: {reference}")
        fragment = unquote(parsed.fragment)
        if fragment and not fragment.startswith("/"):
            raise BuildError(f"$ref requires a JSON pointer: {reference}")
        pointer = tuple(fragment[1:].split("/")) if fragment else ()
        if any(re.search(r"~(?![01])", part) for part in pointer):
            raise BuildError(f"Invalid JSON pointer: {reference}")
        pointer = tuple(part.replace("~1", "/").replace("~0", "~") for part in pointer)
        target = self.document(target_name)
        try:
            for part in pointer:
                if isinstance(target, list):
                    if not re.fullmatch(r"0|[1-9][0-9]*", part):
                        raise KeyError(part)
                    target = target[int(part)]
                else:
                    target = target[part]
        except (KeyError, IndexError, TypeError) as error:
            raise BuildError(f"Unresolved $ref {reference} in {document_path}") from error
        key = (target_name, fragment)
        siblings = {name: self.resolve(child, document_path, stack, location + (name,)) for name, child in value.items() if name != "$ref"}
        if key in stack:
            return {"$ref": f"{target_name}#{fragment}", "x-explorer-recursive": True, **siblings}
        resolved = self.resolve(target, target_name, stack + (key,), pointer)
        if not isinstance(resolved, dict):
            if siblings:
                raise BuildError(f"Cannot attach $ref siblings to a scalar: {reference}")
            return resolved
        result = {**resolved, **siblings, "x-explorer-source": resolved.get("x-explorer-source", self.source(target_name, *pointer))}
        self.origins[id(result)] = self.origins.get(id(resolved), (target_name, pointer))
        return result


def operation_path(contract: dict, item: dict, operation: dict, path: str) -> str:
    servers = operation.get("servers", item.get("servers", contract.get("servers", [])))
    base = urlsplit(servers[0]["url"]).path.rstrip("/") if servers else ""
    if base and (path == base or path.startswith(base + "/")):
        return path
    return base + path


def operations_for(repository: Repository, service: str, path: str, visibility: str) -> list[dict]:
    contract = repository.document(path)
    if not isinstance(contract, dict) or "openapi" not in contract:
        raise BuildError(f"Not an OpenAPI contract: {path}")
    result = []
    for route, raw_item in contract.get("paths", {}).items():
        if not route.startswith("/"):
            continue
        item = repository.resolve(raw_item, path, location=("paths", route))
        for method in METHODS:
            if method not in item:
                continue
            operation = item[method]
            operation_id = operation.get("operationId")
            if not isinstance(operation_id, str) or not operation_id:
                raise BuildError(f"Missing operationId: {path} {method} {route}")
            parameters: dict[tuple[str, str], dict] = {}
            for parameter in [*item.get("parameters", []), *operation.get("parameters", [])]:
                parameters[(parameter["in"], parameter["name"])] = parameter
            source_path, source_pointer = repository.origins.get(id(operation), (path, ("paths", route, method)))
            operation_source = repository.source(source_path, *source_pointer)
            result.append({
                **operation,
                "id": f"{service}:{operation_id}" if visibility == "internal" else operation_id,
                "originalOperationId": operation_id,
                "service": service,
                "method": method.upper(),
                "path": operation_path(contract, item, operation, route),
                "contractPath": path,
                "summary": operation.get("summary", operation_id),
                "description": operation.get("description", ""),
                "security": operation.get("security", repository.resolve(contract.get("security", []), path)),
                "securitySchemes": repository.resolve(contract.get("components", {}).get("securitySchemes", {}), path),
                "parameters": list(parameters.values()),
                "requestBody": operation.get("requestBody"),
                "responses": operation.get("responses", {}),
                "visibility": visibility,
                "status": "implemented",
                "source": operation_source,
            })
    return result


def validate_details(repository: Repository, details: list[dict], operation_ids: set[str], node_ids: set[str]) -> None:
    # Collect IDs before walking references so links can target any detail file,
    # independently of filename order (including forward references).
    flow_ids: set[str] = set()
    fact_ids: set[str] = set()
    for detail in details:
        for collection, known in (("flows", flow_ids), ("facts", fact_ids)):
            for item in detail.get(collection, []):
                item_id = item.get("id") if isinstance(item, dict) else None
                if not isinstance(item_id, str) or not item_id or item_id in known:
                    raise BuildError(f"Missing/duplicate {collection} id: {item_id}")
                known.add(item_id)

    def walk(value: Any, context: str) -> None:
        if isinstance(value, list):
            for index, child in enumerate(value):
                walk(child, f"{context}[{index}]")
        elif isinstance(value, dict):
            if "path" in value and "line" in value:
                source = repository.read_source(value["path"])
                line = value["line"]
                if type(line) is not int or not 1 <= line <= max(1, len(source.splitlines())):
                    raise BuildError(f"Invalid source line in {context}: {line}")
            for key, child in value.items():
                if key == "operationIds":
                    if not isinstance(child, list) or any(item not in operation_ids for item in child):
                        raise BuildError(f"Unknown operationIds in {context}: {child}")
                elif key == "operationNotes":
                    if not isinstance(child, dict) or set(child) - operation_ids:
                        raise BuildError(f"Unknown operationNotes keys in {context}")
                elif key == "flowId":
                    if not isinstance(child, str) or child not in flow_ids:
                        raise BuildError(f"Unknown flowId in {context}: {child}")
                elif key == "nextFlows":
                    if not isinstance(child, list) or any(not isinstance(item, str) or item not in flow_ids for item in child):
                        raise BuildError(f"Unknown nextFlows in {context}: {child}")
                elif key == "steps":
                    if not isinstance(child, list) or any(not isinstance(step, dict) or step.get("node") not in node_ids for step in child):
                        raise BuildError(f"Unknown flow step node in {context}")
                walk(child, f"{context}.{key}")

    for detail in details:
        service_id = detail.get("serviceId", detail.get("service"))
        if service_id and service_id not in node_ids | {"infrastructure"}:
            raise BuildError(f"Unknown detail service: {service_id}")
        walk(detail, "details")


def build_data(root: Path) -> dict:
    repository = Repository(root)
    services_path = (MODEL / "services.yaml").as_posix()
    events_path = (MODEL / "events.yaml").as_posix()
    calls_path = (MODEL / "sync-calls.yaml").as_posix()
    service_model = repository.document(services_path)
    event_model = repository.document(events_path)
    call_model = repository.document(calls_path)
    descriptors = {}
    for descriptor_path in sorted((repository.root / "backend/services").glob("*/service.yaml")):
        name = repository.relative(descriptor_path)
        descriptor = repository.document(name)
        service_id = descriptor.get("id")
        if not service_id or service_id != descriptor_path.parent.name or service_id in descriptors:
            raise BuildError(f"Invalid/duplicate service descriptor id: {name}")
        descriptors[service_id] = (descriptor, name)
    services = []
    seen = set()
    for index, service in enumerate(service_model.get("services", [])):
        service_id = service["id"]
        if service_id in seen:
            raise BuildError(f"Duplicate model service: {service_id}")
        seen.add(service_id)
        descriptor, descriptor_path = descriptors.get(service_id, (None, None))
        services.append({**service, "status": "implemented" if descriptor else "planned", "descriptor": descriptor,
                         "source": repository.source(services_path, "services", str(index)),
                         "descriptorSource": repository.source(descriptor_path) if descriptor_path else None})
    for service_id, (descriptor, path) in descriptors.items():
        if service_id not in seen:
            services.append({**descriptor, "name": descriptor.get("name", service_id), "status": "implemented",
                             "descriptor": descriptor, "source": repository.source(path), "descriptorSource": repository.source(path)})
    for service in services:
        if service.get("dbml"):
            repository.read_source(service["dbml"])
            service["dbmlSource"] = {"path": service["dbml"], "line": 1}
    operations = []
    for service_id, (descriptor, _) in descriptors.items():
        public = descriptor.get("public_openapi")
        if public:
            operations.extend(operations_for(repository, service_id, public["source"], "public"))
        if descriptor.get("runtime_openapi"):
            operations.extend(operations_for(repository, service_id, descriptor["runtime_openapi"], "internal"))
    operation_ids = {operation["id"] for operation in operations}
    if len(operation_ids) != len(operations):
        raise BuildError("Duplicate active operation ID")
    events = []
    for index, event in enumerate(event_model.get("events", [])):
        identity = {"type": event["type"], "version": event["version"]}
        producer = descriptors.get(event["producer"], ({}, ""))[0]
        implemented_consumers = [consumer for consumer in event.get("consumers", [])
                                 if identity in descriptors.get(consumer, ({}, ""))[0].get("messaging", {}).get("consumes", [])]
        events.append({**event, "subject": f"cookie.events.{event['type']}.v{event['version']}",
                       "status": "implemented" if identity in producer.get("messaging", {}).get("publishes", []) else "planned",
                       "implementedConsumers": implemented_consumers,
                       "plannedConsumers": [consumer for consumer in event.get("consumers", []) if consumer not in implemented_consumers],
                       "source": repository.source(events_path, "events", str(index))})
    external = {system["id"] for system in service_model.get("external_systems", [])}
    calls = []
    for index, call in enumerate(call_model.get("calls", [])):
        for callee in call.get("callees", [call.get("callee")]):
            if not callee:
                raise BuildError(f"Call has no callee: {call}")
            calls.append({**call, "callee": callee,
                          "status": "implemented" if call["caller"] in descriptors and callee in (set(descriptors) | external) else "planned",
                          "source": repository.source(calls_path, "calls", str(index))})
    details = []
    for path in sorted((repository.root / EXPLORER / "details").glob("*.json")):
        name = repository.relative(path)
        detail = json.loads(repository.read_source(name))
        if not isinstance(detail, dict):
            raise BuildError(f"Details must be a JSON object: {name}")
        details.append({**detail, "source": {"path": name, "line": 1}})
    nodes = {service["id"] for service in services} | external | NODE_ALIASES | set(service_model.get("infrastructure", {}))
    validate_details(repository, details, operation_ids, nodes)
    data = {"services": services, "operations": operations, "events": events, "calls": calls,
            "infrastructure": service_model.get("infrastructure", {}), "externalSystems": service_model.get("external_systems", []),
            "deployment": service_model.get("deployment", {}), "defaults": service_model.get("defaults", {}),
            "envelope": event_model.get("envelope", {}), "transport": event_model.get("transport", {}),
            "callRules": call_model.get("rules", []), "details": details, "sources": dict(sorted(repository.sources.items()))}
    build_inputs = {name: (repository.root / EXPLORER / name).read_text(encoding="utf-8")
                    for name in ("build.py", "requirements.txt", "template.html", "style.css", "app.js")
                    if (repository.root / EXPLORER / name).exists()}
    fingerprint = hashlib.sha256(json.dumps({"data": data, "buildInputs": build_inputs}, ensure_ascii=False,
                                           sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    dates = [str(model.get("updated_at", "")) for model in (service_model, event_model, call_model)]
    return {"meta": {"revision": f"sha256:{fingerprint[:12]}", "modelUpdatedAt": max(dates), "fingerprint": fingerprint}, **data}


def javascript(data: dict) -> str:
    serialized = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    # JSON.parse preserves JSON property names such as __proto__; evaluating the
    # same JSON as a JavaScript object literal would change its semantics.
    literal = json.dumps(serialized, ensure_ascii=False).replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
    return "// Generated by docs/architecture/explorer/build.py; do not edit.\nwindow.ARCHITECTURE_DATA = JSON.parse(" + literal + ");\n"


def render_index(directory: Path, data_js: str) -> str:
    template = (directory / "template.html").read_text(encoding="utf-8")
    def escape_end_tag(text: str, tag: str) -> str:
        return re.sub(r"</" + tag, lambda match: "<\\/" + match.group()[2:], text, flags=re.IGNORECASE)

    replacements = {
        "<!-- EXPLORER_STYLE -->": "<style>\n" + escape_end_tag((directory / "style.css").read_text(encoding="utf-8"), "style") + "\n</style>",
        "<!-- EXPLORER_DATA -->": "<script>\n" + data_js + "</script>",
        "<!-- EXPLORER_SCRIPT -->": "<script>\n" + escape_end_tag((directory / "app.js").read_text(encoding="utf-8"), "script") + "\n</script>",
    }
    for marker in replacements:
        if template.count(marker) != 1:
            raise BuildError(f"Template must contain exactly one {marker}")
    # Match the original template once; embedded source text can legitimately
    # contain the same marker strings and must never be interpreted as a slot.
    pattern = re.compile("|".join(re.escape(marker) for marker in replacements))
    return pattern.sub(lambda match: replacements[match.group()], template)


def generate(root: Path, *, check: bool = False, data_only: bool = False) -> list[str]:
    directory = root / EXPLORER
    data_js = javascript(build_data(root))
    outputs = {directory / "data.js": data_js}
    if not data_only:
        outputs[directory / "index.html"] = render_index(directory, data_js)
    changed = []
    for path, content in outputs.items():
        if not path.exists() or path.read_text(encoding="utf-8") != content:
            changed.append(path.relative_to(root).as_posix())
            if not check:
                path.write_text(content, encoding="utf-8", newline="\n")
    return changed


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--check", action="store_true", help="Fail on artifact drift without writing files")
    parser.add_argument("--data-only", action="store_true", help="Generate/check data.js while UI assets are under construction")
    args = parser.parse_args(argv)
    try:
        changed = generate(args.root.resolve(), check=args.check, data_only=args.data_only)
    except (BuildError, OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        print(f"Architecture explorer: {error}", file=sys.stderr)
        return 1
    if args.check and changed:
        print("Architecture explorer is stale: " + ", ".join(changed), file=sys.stderr)
        return 1
    print("Architecture explorer: " + ("up to date" if args.check else "generated " + ", ".join(changed) if changed else "unchanged"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
