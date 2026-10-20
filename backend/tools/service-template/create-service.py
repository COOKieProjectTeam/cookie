#!/usr/bin/env python3
"""Create an unregistered Kotlin service scaffold; never creates business implementations."""
import argparse
from pathlib import Path
import re


def scaffold(service: str, destination: Path) -> None:
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,47}", service):
        raise ValueError("service must be a model id containing lowercase letters, digits or underscores")
    if destination.exists():
        raise ValueError("destination already exists; existing services are never overwritten")
    class_name = "".join(part.title() for part in service.split("_"))
    templates = Path(__file__).parent / "template"
    substitutions = {"__SERVICE__": service, "__CLASS__": class_name}
    files = {}
    for template, target in [("service", "build.gradle.kts"), ("domain", "domain/build.gradle.kts"),
                             ("application", "application/build.gradle.kts")]:
        content = (templates / f"{template}.gradle.kts").read_text()
        for source, value in substitutions.items():
            content = content.replace(source, value)
        files[target] = content
    files[f"src/main/kotlin/com/cookie/{service}/{class_name}Application.kt"] = f'''package com.cookie.{service}

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class {class_name}Application

fun main(args: Array<String>) {{
    runApplication<{class_name}Application>(*args)
}}
'''
    files["README.md"] = f'''# {class_name} service scaffold

Generated with backend/tools/service-template/create-service.py.

Before registering the three modules in settings.gradle.kts:
- implement handwritten handlers and the owned contracts/openapi/public/{service}.yaml;
- create service.yaml from the canonical service/event model, with only implemented roles;
- implement generated runtime probes and fail-closed configuration validation;
- add owned Flyway migrations, domain/application tests, integration tests and CI mapping;
- use Notification for consumer/delivery examples, Identity for publisher/auth examples.

This scaffold intentionally contains no fake business handlers, credentials or identity models.
'''
    destination.mkdir(parents=True)
    for relative, content in files.items():
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        scaffold(args.service, args.destination)
    except ValueError as error:
        parser.error(str(error))
