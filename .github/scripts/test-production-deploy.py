"""Exercise operator deploy/recovery with isolated mock commands, never a cloud host."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def image(service, revision):
    return f"cr.yandex/example/{service}@sha256:{revision * 64}"


MOCK = r'''
import json, os, signal, stat, sys, time
from pathlib import Path
name = Path(sys.argv[0]).name
args = sys.argv[1:]
env = os.environ
release = Path(env["MOCK_RELEASE"])
def stage(operation, service=""):
    selected = env.get(service.upper()+"_IMAGE", "")
    with open(env["MOCK_LOG"], "a") as out:
        out.write(json.dumps({"stage":operation,"service":service,"image":selected,
            "identity":env.get("IDENTITY_IMAGE", ""),"notification":env.get("NOTIFICATION_IMAGE", ""),
            "release":release.read_text() if release.exists() else None,"args":args})+"\n")
    matches = (service == env.get("MOCK_SERVICE") and selected == env.get("MOCK_CANDIDATE")
        and operation == env.get("MOCK_STAGE"))
    if not matches: return False
    mode = env.get("MOCK_MODE")
    if mode in ("interrupt", "kill"):
        marker=Path(env["MOCK_SIGNALLED"])
        if not marker.exists():
            marker.touch()
            os.kill(int(Path(env["MOCK_PID"]).read_text()), signal.SIGTERM if mode=="interrupt" else signal.SIGKILL)
            time.sleep(.05)
    return mode == "failure"
if name=="docker":
    if args[0]=="login": sys.stdin.read()
    elif args[0]=="ps":
        if Path(env["MOCK_MIGRATION_CONTAINER"]).exists(): print("synthetic-migration-id")
    elif args[0] in ("stop", "rm"):
        stage("migration-"+args[0], "notification")
        marker=Path(env["MOCK_MIGRATION_CONTAINER"])
        if args[0]=="stop":
            if env.get("MOCK_MIGRATION_STOP")=="failure": sys.exit(1)
            if env.get("MOCK_MIGRATION_STOP")=="auto-remove": marker.unlink(missing_ok=True)
        if args[0]=="rm":
            if not marker.exists(): sys.exit(1)
            marker.unlink()
    elif args[0]=="inspect": print("exited" if stage("health", args[-1]) else "healthy")
    elif args[0]=="compose" and "--file" in args:
        command=args[max(i for i,arg in enumerate(args) if arg=="--file")+2:]
        if command[0]=="ps": print(command[-1])
        elif command[0]=="up" and command[-1] in ("identity","notification"):
            sys.exit(1 if stage("start",command[-1]) else 0)
        elif command[0]=="stop": sys.exit(1 if stage("stop",command[-1]) else 0)
        elif command[0]=="run" and command[-1]=="notification-migrate":
            marker=Path(env["MOCK_MIGRATION_CONTAINER"]); marker.touch()
            failure=stage("migrate", "notification")
            # A lost CLI can leave its one-off container running on the daemon.
            if env.get("MOCK_MODE") not in ("interrupt", "kill") or env.get("MOCK_STAGE") != "migrate":
                marker.unlink(missing_ok=True)
            sys.exit(1 if failure else 0)
        elif command[0]=="run": stage("provision",command[-1])
        else: stage("compose",command[0])
elif name=="curl":
    url=args[-1]
    if "/readyz" in url:
        service="notification" if ":8082/" in url else "identity"
        print("503" if stage("published",service) else "200")
    else: print('{"access_token":"synthetic-test-token"}')
elif name=="jq": print(json.load(sys.stdin)["access_token"])
elif name=="stat":
    path=Path(args[-1])
    if args[1]=="%u":
        owners={"identity":10001,"notification":10001,"postgres":70,"nats":1000,"redis":999}
        owner=next((uid for part,uid in owners.items() if part in path.parts),0)
        if path.name in ("ca.crt","server.crt"): owner=0
        print(owner)
    else: print(format(stat.S_IMODE(path.stat().st_mode),"o"))
elif name=="sleep": time.sleep(.03)
elif name!="flock": raise AssertionError(name)
'''


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="cookie-deploy-test-")
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.data = self.directory / "data"
        self.release = self.data / "releases/current.env"
        self.pending = self.data / "releases/pending.env"
        self.log = self.directory / "calls.jsonl"
        self.release.parent.mkdir(parents=True, mode=0o700)
        secrets = self.data / "secrets"
        for path in (self.data / "postgres", self.data / "nats", secrets / "identity/retiring", secrets / "notification/retiring"):
            path.mkdir(parents=True, mode=0o700)
        files = {
            "postgres": ["admin-password", "identity-password", "identity-migration-password", "notification-password", "notification-migration-password"],
            "identity": ["spring.datasource.password", "spring.flyway.password", "cookie.identity.rate-limit-hmac-key", "cookie.identity.nats-truststore-password", "identity-jwt-private.jwk", "notification-public.jwk", "nats-identity.creds", "nats-truststore.jks"],
            "notification": ["spring.datasource.password", "spring.flyway.password", "cookie.notification.nats-truststore-password", "spring.data.redis.password", "cookie.notification.delivery.smtp.password", "notification-private.jwk", "nats-notification.creds", "nats-truststore.jks", "fcm-credentials.json"],
            "nats": ["auth.conf", "ca.crt", "server.crt", "server.key", "stream-admin.creds"],
            "redis": ["password"],
        }
        for service, names in files.items():
            folder = secrets / service
            folder.mkdir(exist_ok=True, mode=0o700)
            for name in names:
                path = folder / name
                path.write_text("synthetic-test-material\n")
                path.chmod(0o444 if name in ("ca.crt", "server.crt") else 0o600)
        self.config = self.directory / "production.env"
        self.config.write_text(f"""YC_REGISTRY_ID=example
COOKIE_DATA_ROOT='{self.data}'
COOKIE_SECRET_ROOT='{secrets}'
COOKIE_IDENTITY_ISSUER=https://example.invalid
COOKIE_IDENTITY_JWKS_URL=https://example.invalid/v1/auth/jwks
COOKIE_NOTIFICATION_VERIFICATION_LINK_BASE_URL=https://example.invalid/confirm
COOKIE_IDENTITY_BIND_IP=127.0.0.1
COOKIE_NOTIFICATION_BIND_IP=127.0.0.1
""")
        self.config.chmod(0o600)
        self.script = self.directory / "deploy.sh"
        source = (ROOT / "deploy/production/deploy.sh").read_text()
        source = source.replace('[[ "$EUID" -eq 0 ]]', '[[ 0 -eq 0 ]]')
        source = source.replace("/srv/cookie", str(self.data))
        source = source.replace("/run/lock/cookie-production-deploy.lock", str(self.directory / "lock"))
        source = source.replace("readonly PUBLISHED_HEALTH_TIMEOUT_SECONDS=30", "readonly PUBLISHED_HEALTH_TIMEOUT_SECONDS=1")
        self.script.write_text(source)
        self.bin = self.directory / "bin"
        self.bin.mkdir()
        for name in ("docker", "curl", "jq", "flock", "stat", "sleep"):
            executable = self.bin / name
            executable.write_text(f"#!{sys.executable}\n" + MOCK)
            executable.chmod(0o755)

    def seed(self, identity="a", notification="a"):
        content = f"IDENTITY_IMAGE={image('identity', identity)}\n"
        if notification is not None:
            content += f"NOTIFICATION_IMAGE={image('notification', notification)}\n"
        self.release.write_text(content)
        self.release.chmod(0o600)
        return content

    def run_deploy(self, *args, service="notification", revision="b", mode="success", stage="start", migration_stop="success"):
        env = os.environ.copy()
        env.update(PATH=str(self.bin) + os.pathsep + env["PATH"], COOKIE_PRODUCTION_ENV_FILE=str(self.config),
            COOKIE_DEPLOY_HEALTH_TIMEOUT_SECONDS="1", MOCK_RELEASE=str(self.release), MOCK_LOG=str(self.log),
            MOCK_PID=str(self.directory / "pid"), MOCK_SIGNALLED=str(self.directory / "signalled"),
            MOCK_MIGRATION_CONTAINER=str(self.directory / "migration-container"), MOCK_MIGRATION_STOP=migration_stop,
            MOCK_SERVICE=service, MOCK_CANDIDATE=image(service, revision), MOCK_MODE=mode, MOCK_STAGE=stage)
        return subprocess.run(["bash", "-c", 'printf "%s" "$$" > "$MOCK_PID"; exec bash "$@"', "deploy-test", str(self.script), *args],
            env=env, text=True, capture_output=True, timeout=20)

    def events(self, stage=None):
        entries = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return [entry for entry in entries if stage is None or entry["stage"] == stage]

    def test_fresh_pair_is_recorded_after_both_become_ready(self):
        result = self.run_deploy("--identity", image("identity", "a"), "--notification", image("notification", "b"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([entry["service"] for entry in self.events("start")], ["identity", "notification"])
        self.assertTrue(all(entry["release"] is None for entry in self.events("start")))
        self.assertIn(image("notification", "b"), self.release.read_text())
        self.assertFalse(self.pending.exists())

    def test_legacy_release_expands_without_restarting_identity(self):
        self.seed(notification=None)
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([entry["service"] for entry in self.events("start")], ["notification"])
        self.assertIn(image("identity", "a"), self.release.read_text())

    def test_independent_update_preserves_other_service(self):
        for service in ("identity", "notification"):
            with self.subTest(service=service):
                self.seed(); self.log.unlink(missing_ok=True)
                result = self.run_deploy("--" + service, image(service, "b"), service=service)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual([e["service"] for e in self.events("start")], [service])
                changes = [(e["stage"], e["service"]) for e in self.events()
                           if e["stage"] in ("stop", "migrate", "start")]
                expected = [("start", service)]
                if service == "notification":
                    expected = [("stop", service), ("migrate", service), ("start", service)]
                self.assertEqual(changes, expected)
                other = "notification" if service == "identity" else "identity"
                self.assertIn(image(other, "a"), self.release.read_text())

    def test_failures_restore_only_attempted_service(self):
        for stage in ("start", "health", "published"):
            with self.subTest(stage=stage):
                original = self.seed(); self.log.unlink(missing_ok=True)
                result = self.run_deploy("--notification", image("notification", "b"), mode="failure", stage=stage)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.release.read_text(), original)
                self.assertEqual([e["image"] for e in self.events("start")], [image("notification", "b"), image("notification", "a")])
                self.assertEqual([e["stage"] for e in self.events() if e["stage"] in ("stop", "start")],
                                 ["stop", "start", "stop", "start"])
                self.assertFalse(self.pending.exists())

    def test_failed_migration_never_starts_candidate_or_migrates_rollback(self):
        original = self.seed()
        result = self.run_deploy("--notification", image("notification", "b"), mode="failure", stage="migrate")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.release.read_text(), original)
        self.assertEqual([e["image"][-1] for e in self.events("start")], ["a"])
        self.assertEqual([e["image"][-1] for e in self.events("migrate")], ["b"])
        self.assertEqual([e["stage"] for e in self.events() if e["stage"] in ("stop", "migrate", "start")],
                         ["stop", "migrate", "stop", "start"])
        self.assertFalse(self.pending.exists())

    def test_first_install_migration_failure_keeps_notification_stopped(self):
        original = self.seed(notification=None)
        result = self.run_deploy("--notification", image("notification", "b"), mode="failure", stage="migrate")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.release.read_text(), original)
        self.assertEqual(self.events("start"), [])
        self.assertEqual([e["stage"] for e in self.events() if e["stage"] in ("stop", "migrate")],
                         ["stop", "migrate", "stop"])
        self.assertFalse(self.pending.exists())

    def test_migration_interruption_fences_one_off_before_recovery(self):
        for mode in ("interrupt", "kill"):
            with self.subTest(mode=mode):
                original = self.seed(); self.log.unlink(missing_ok=True)
                (self.directory / "signalled").unlink(missing_ok=True)
                result = self.run_deploy("--notification", image("notification", "b"), mode=mode, stage="migrate")
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.release.read_text(), original)
                if mode == "kill":
                    self.assertTrue(self.pending.exists())
                    result = self.run_deploy("--notification", image("notification", "c"), revision="c")
                    self.assertEqual(result.returncode, 0, result.stderr)
                lifecycle = [(e["stage"], e["image"][-1:] if e["stage"] in ("migrate", "start") else "") for e in self.events()
                             if e["stage"] in ("migrate", "migration-stop", "migration-rm", "start")]
                self.assertEqual(lifecycle[:4], [("migrate", "b"), ("migration-stop", ""),
                                                ("migration-rm", ""), ("start", "a")])
                self.assertNotIn(("start", "b"), lifecycle)
                self.assertNotIn(("migrate", "a"), lifecycle)
                self.assertFalse(self.pending.exists())

    def test_recovery_waits_for_migrator_and_tolerates_automatic_container_removal(self):
        for stop in ("failure", "auto-remove"):
            with self.subTest(stop=stop):
                original = self.seed(); self.log.unlink(missing_ok=True)
                (self.directory / "signalled").unlink(missing_ok=True)
                result = self.run_deploy("--notification", image("notification", "b"),
                                         mode="interrupt", stage="migrate", migration_stop=stop)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.release.read_text(), original)
                if stop == "failure":
                    self.assertEqual(self.events("start"), [])
                    self.assertTrue(self.pending.exists())
                    # Retry recovery with a functioning daemon before the next case.
                    result = self.run_deploy("--notification", image("notification", "a"), revision="a")
                    self.assertEqual(result.returncode, 0, result.stderr)
                else:
                    self.assertEqual([e["image"][-1] for e in self.events("start")], ["a"])
                self.assertFalse(self.pending.exists())

    def test_pair_failure_rolls_back_in_reverse_order(self):
        original = self.seed()
        result = self.run_deploy("--identity", image("identity", "b"), "--notification", image("notification", "b"), mode="failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.release.read_text(), original)
        self.assertEqual([(e["service"], e["image"][-1]) for e in self.events("start")], [("identity", "b"), ("notification", "b"), ("notification", "a"), ("identity", "a")])

    def test_first_notification_failure_stops_it_without_recording_release(self):
        original = self.seed(notification=None)
        result = self.run_deploy("--notification", image("notification", "b"), mode="failure")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.release.read_text(), original)
        self.assertEqual([e["service"] for e in self.events("stop")], ["notification", "notification"])

    def test_termination_recovers_and_preserves_committed_release(self):
        original = self.seed()
        result = self.run_deploy("--notification", image("notification", "b"), mode="interrupt")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.release.read_text(), original)
        self.assertFalse(self.pending.exists())

    def test_hard_kill_journal_is_recovered_before_next_candidate(self):
        original = self.seed()
        killed = self.run_deploy("--notification", image("notification", "b"), mode="kill")
        self.assertNotEqual(killed.returncode, 0)
        self.assertTrue(self.pending.exists())
        self.assertEqual(self.release.read_text(), original)
        result = self.run_deploy("--notification", image("notification", "c"), revision="c")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([e["image"][-1] for e in self.events("start")], ["b", "a", "c"])
        self.assertIn(image("notification", "c"), self.release.read_text())
        self.assertFalse(self.pending.exists())

    def test_wrong_repository_or_tag_is_rejected_before_runtime_changes(self):
        for invalid in ("cr.yandex/example/notification:latest", image("identity", "b"), image("notification", "b").replace("example", "another")):
            with self.subTest(invalid=invalid):
                original = self.seed()
                result = self.run_deploy("--notification", invalid)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.release.read_text(), original)
                self.assertEqual(self.events("start"), [])

    def test_duplicate_manifest_fields_are_rejected(self):
        original = self.seed()
        self.release.write_text(original + f"IDENTITY_IMAGE={image('identity', 'b')}\n")
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate", result.stderr)
        self.assertEqual(self.events("start"), [])

    def test_unreadable_runtime_secret_is_rejected_before_restart(self):
        self.seed()
        path = self.data / "secrets/notification/spring.datasource.password"
        path.chmod(0o000)
        self.addCleanup(lambda: path.chmod(0o600))
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.events("start"), [])

    def test_certificate_must_be_readable_by_container_user(self):
        self.seed()
        (self.data / "secrets/nats/ca.crt").chmod(0o600)
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("certificate must be readable", result.stderr)
        self.assertEqual(self.events("start"), [])

    def test_disabled_push_needs_no_provider_secret_files(self):
        self.seed()
        (self.data / "secrets/notification/fcm-credentials.json").unlink()
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_enabled_push_selects_fcm_secret_overlay(self):
        self.seed()
        with self.config.open("a") as output:
            output.write("COOKIE_FCM_ENABLED=true\nCOOKIE_FCM_PROJECT_ID=synthetic-project\n")
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertEqual(result.returncode, 0, result.stderr)
        config = next(event for event in self.events("compose") if event["service"] == "config")
        self.assertEqual([Path(arg).name for arg in config["args"] if arg.endswith(".yaml")],
                         ["compose.yaml", "compose.fcm.yaml"])

    def test_enabled_push_requires_its_credentials(self):
        self.seed()
        with self.config.open("a") as output:
            output.write("COOKIE_FCM_ENABLED=true\nCOOKIE_FCM_PROJECT_ID=synthetic-project\n")
        (self.data / "secrets/notification/fcm-credentials.json").unlink()
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("enabled FCM requires its credentials file", result.stderr)
        self.assertEqual(self.events("start"), [])

    def test_password_copy_mismatch_is_rejected(self):
        self.seed()
        (self.data / "secrets/notification/spring.datasource.password").write_text("different-password")
        result = self.run_deploy("--notification", image("notification", "b"))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("runtime-password copies do not match", result.stderr)
        self.assertEqual(self.events("start"), [])


if __name__ == "__main__":
    unittest.main()
