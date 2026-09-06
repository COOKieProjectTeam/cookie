import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
IMAGES = {name: f"cr.yandex/example/identity@sha256:{name * 64}" for name in "abc"}

MOCK_TOOL = r'''
import json
import os
from pathlib import Path
import signal
import stat
import sys

tool = Path(sys.argv[0]).name
args = sys.argv[1:]
env = os.environ
release = Path(env["MOCK_RELEASE"])
image = env.get("IDENTITY_IMAGE", "")

def stage(name):
    entry = {
        "stage": name,
        "image": image,
        "release": release.read_text() if release.exists() else None,
    }
    with open(env["MOCK_LOG"], "a") as output:
        output.write(json.dumps(entry) + "\n")
    if image == env["MOCK_CANDIDATE"] and name == env["MOCK_STAGE"]:
        if env["MOCK_MODE"] == "interrupt":
            os.kill(int(Path(env["MOCK_PID_FILE"]).read_text()), signal.SIGTERM)
            sys.exit(1)
        return env["MOCK_MODE"] == "failure"
    return False

if tool == "docker":
    if args[0] == "login":
        sys.stdin.read()
    elif args[0] == "inspect":
        if args[-1] == "identity" and stage("health"):
            print("exited")
        else:
            print("healthy")
    elif args[0] == "compose" and "--file" in args:
        operation = args[args.index("--file") + 2:]
        if operation[0] == "ps":
            print(operation[-1])
        elif operation[0] == "up" and operation[-1] == "identity":
            sys.exit(1 if stage("start") else 0)
        elif operation[0] == "stop":
            stage("stop")
elif tool == "curl":
    if "http://127.0.0.1:8080/readyz" in args:
        print("503" if stage("published") else "200")
    else:
        print('{"access_token":"synthetic-iam-token"}')
elif tool == "jq":
    print(json.load(sys.stdin)["access_token"])
elif tool == "stat":
    path = Path(args[-1])
    if args[1] == "%u":
        if "identity" in path.parts:
            print(10001)
        elif "postgres" in path.parts:
            print(70)
        elif "nats" in path.parts and path.name not in ("ca.crt", "server.crt"):
            print(1000)
        else:
            print(0)
    elif args[1] == "%a":
        print(format(stat.S_IMODE(path.stat().st_mode), "o"))
elif tool != "flock":
    raise RuntimeError(f"Unexpected external command: {tool}")
'''


class ProductionDeployTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cookie-deploy-test-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.data = self.directory / "data"
        self.release = self.data / "releases/current.env"
        self.log = self.directory / "calls.jsonl"
        secrets = self.data / "secrets"
        for path in (self.data / "postgres", self.data / "nats", secrets / "identity/retiring"):
            path.mkdir(parents=True, mode=0o700)
        secret_files = {
            "postgres": ["admin-password", "identity-password", "identity-migration-password"],
            "identity": [
                "spring.datasource.password", "spring.flyway.password",
                "cookie.identity.rate-limit-hmac-key", "cookie.identity.nats-truststore-password",
                "identity-jwt-private.jwk", "notification-public.jwk", "nats-identity.creds",
                "nats-truststore.jks",
            ],
            "nats": ["auth.conf", "ca.crt", "server.crt", "server.key", "stream-admin.creds"],
        }
        for service, filenames in secret_files.items():
            folder = secrets / service
            folder.mkdir(exist_ok=True, mode=0o700)
            for filename in filenames:
                path = folder / filename
                path.write_text("synthetic-test-material\n")
                path.chmod(0o600)
        self.config = self.directory / "production.env"
        self.config.write_text(
            "YC_REGISTRY_ID=example\n"
            f"COOKIE_DATA_ROOT='{self.data}'\n"
            f"COOKIE_SECRET_ROOT='{secrets}'\n"
            "COOKIE_IDENTITY_ISSUER=https://example.invalid\n"
            "COOKIE_IDENTITY_BIND_IP=127.0.0.1\n"
        )
        self.config.chmod(0o600)
        self.script = self.directory / "deploy.sh"
        source = (ROOT / "deploy/production/deploy.sh").read_text()
        for before, after in (
            ('[[ "${EUID}" -eq 0 ]]', '[[ 0 -eq 0 ]]'),
            ("/srv/cookie", str(self.data)),
            ("/run/lock/cookie-production-deploy.lock", str(self.directory / "deploy.lock")),
            ("readonly PUBLISHED_HEALTH_TIMEOUT_SECONDS=30", "readonly PUBLISHED_HEALTH_TIMEOUT_SECONDS=2"),
        ):
            self.assertIn(before, source)
            source = source.replace(before, after)
        self.script.write_text(source)
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        for name in ("docker", "curl", "jq", "stat", "flock"):
            path = self.bin / name
            path.write_text(f"#!{sys.executable}\n" + MOCK_TOOL)
            path.chmod(0o700)

    def seed_release(self, image):
        self.release.parent.mkdir(parents=True, exist_ok=True)
        self.release.write_text(f"IDENTITY_IMAGE={image}\n")
        self.release.chmod(0o600)

    def run_deploy(self, image, mode="success", stage="start"):
        env = os.environ.copy()
        env.update({
            "PATH": str(self.bin) + os.pathsep + env["PATH"],
            "COOKIE_PRODUCTION_ENV_FILE": str(self.config),
            "COOKIE_DEPLOY_HEALTH_TIMEOUT_SECONDS": "2",
            "MOCK_RELEASE": str(self.release),
            "MOCK_LOG": str(self.log),
            "MOCK_PID_FILE": str(self.directory / "deploy.pid"),
            "MOCK_CANDIDATE": image,
            "MOCK_MODE": mode,
            "MOCK_STAGE": stage,
        })
        return subprocess.run(
            [
                "bash", "-c",
                'printf "%s" "$$" > "$MOCK_PID_FILE"; exec bash "$1" "$2"',
                "deploy-test", str(self.script), image,
            ],
            env=env, text=True, capture_output=True, timeout=20,
        )

    def events(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_success_promotes_only_after_both_readiness_checks(self):
        self.seed_release(IMAGES["a"])
        result = self.run_deploy(IMAGES["b"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        events = self.events()
        self.assertEqual([event["stage"] for event in events], ["start", "health", "published"])
        self.assertTrue(all(event["release"] == f'IDENTITY_IMAGE={IMAGES["a"]}\n' for event in events))
        self.assertEqual(self.release.read_text(), f'IDENTITY_IMAGE={IMAGES["b"]}\n')
        self.assertEqual(self.release.stat().st_mode & 0o777, 0o600)

    def test_first_deploy_records_only_a_verified_release(self):
        result = self.run_deploy(IMAGES["a"])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(all(event["release"] is None for event in self.events()))
        self.assertEqual(self.release.read_text(), f'IDENTITY_IMAGE={IMAGES["a"]}\n')

    def test_failed_start_or_readiness_rolls_back_to_verified_release(self):
        for stage in ("start", "health", "published"):
            with self.subTest(stage=stage):
                self.seed_release(IMAGES["a"])
                self.log.write_text("")
                result = self.run_deploy(IMAGES["b"], "failure", stage)
                self.assertNotEqual(result.returncode, 0)
                starts = [event["image"] for event in self.events() if event["stage"] == "start"]
                self.assertEqual(starts, [IMAGES["b"], IMAGES["a"]])
                self.assertEqual(self.release.read_text(), f'IDENTITY_IMAGE={IMAGES["a"]}\n')
                self.assertIn("previous Identity image is healthy again", result.stderr)

    def test_interruption_preserves_the_target_for_the_next_rollback(self):
        for stage in ("start", "health", "published"):
            with self.subTest(stage=stage):
                self.seed_release(IMAGES["a"])
                self.log.write_text("")
                interrupted = self.run_deploy(IMAGES["b"], "interrupt", stage)
                self.assertEqual(interrupted.returncode, -signal.SIGTERM)
                self.assertEqual(self.release.read_text(), f'IDENTITY_IMAGE={IMAGES["a"]}\n')
                self.log.write_text("")
                failed = self.run_deploy(IMAGES["c"], "failure")
                self.assertNotEqual(failed.returncode, 0)
                starts = [event["image"] for event in self.events() if event["stage"] == "start"]
                self.assertEqual(starts, [IMAGES["c"], IMAGES["a"]])
                self.assertEqual(self.release.read_text(), f'IDENTITY_IMAGE={IMAGES["a"]}\n')

    def test_failed_first_deploy_stops_candidate_without_recording_it(self):
        result = self.run_deploy(IMAGES["b"], "failure", "health")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.release.exists())
        self.assertIn("stop", [event["stage"] for event in self.events()])

    def test_interrupted_first_deploy_has_no_rollback_target(self):
        result = self.run_deploy(IMAGES["b"], "interrupt")
        self.assertEqual(result.returncode, -signal.SIGTERM)
        self.assertFalse(self.release.exists())


if __name__ == "__main__":
    unittest.main()
