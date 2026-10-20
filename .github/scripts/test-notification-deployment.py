"""Deployment invariants plus real PostgreSQL/Redis/NATS boundaries in CI.

COOKIE_DEPLOY_CONTAINER_TESTS=1 requires a working Docker API and executes the
provisioning scripts against disposable containers, never production.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
POSTGRES = "postgres:18.6-alpine3.24@sha256:d3e1620b530c944afa6e887d22eb899824da68e19c52024bf98f5220c88a65b2"
REDIS = "redis:8.2-alpine@sha256:30abb90e62f14b737010746def3ba99cc79fe19dcdb3d37b41f21fc62e7da19d"


def docker(*args, input=None, check=True):
    return subprocess.run(["docker", *args], input=input, capture_output=True, text=True, check=check, timeout=120)


class DeploymentContractTest(unittest.TestCase):
    def test_consumer_filters_match_service_descriptor(self):
        descriptor = (ROOT / "backend/services/notification/service.yaml").read_text().split("  consumes:", 1)[1].split("synchronous_dependencies:", 1)[0]
        events = re.findall(r"type: ([a-z.]+)\s+version: (\d+)", descriptor)
        self.assertEqual(len(events), 10)
        config = json.loads((ROOT / "deploy/production/config/notification-consumer.json").read_text())
        self.assertCountEqual(config["filter_subjects"], [f"cookie.events.{name}.v{version}" for name, version in events])
        self.assertEqual(config["durable_name"], "notification-v1")
        self.assertEqual(config["ack_policy"], "explicit")
        self.assertEqual(config["max_deliver"], -1)
        self.assertFalse(config["mem_storage"])

    def test_runtime_broker_credential_cannot_bypass_filters(self):
        permissions = json.loads((ROOT / "deploy/production/config/notification-nats-permissions.json").read_text())
        self.assertEqual(permissions["subscribe"]["allow"], ["_INBOX.cookie.notification.>"])
        subjects = permissions["publish"]["allow"]
        self.assertIn("$JS.API.CONSUMER.MSG.NEXT.COOKIE_EVENTS.notification-v1", subjects)
        self.assertTrue(all("COOKIE_EVENTS.notification-v1" in subject for subject in subjects))
        self.assertFalse(any("STREAM.MSG.GET" in subject or "CREATE" in subject or "DELETE" in subject for subject in subjects))

    def test_production_redis_is_ephemeral_and_private(self):
        conf = (ROOT / "deploy/production/config/redis.conf").read_text()
        self.assertIn('save ""', conf)
        self.assertIn("appendonly no", conf)
        self.assertIn("maxmemory-policy noeviction", conf)
        compose = (ROOT / "deploy/production/compose.yaml").read_text()
        redis = compose.split("\n  redis:\n", 1)[1].split("\n  notification:\n", 1)[0]
        self.assertNotIn("ports:", redis)
        self.assertIn("networks: [backend]", redis)
        self.assertNotIn("/data", redis)

    def test_java_crypto_works_with_non_executable_temporary_storage(self):
        # BC LTS extracts its native probe into java.io.tmpdir by default.
        # Keep the production hardening and select its supported Java path;
        # the real-container rehearsal exercises Argon2 and JWT/JWE with it.
        compose = (ROOT / "deploy/production/compose.yaml").read_text()
        for service in ("identity", "notification"):
            with self.subTest(service=service):
                body = re.search(
                    rf"^  {service}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)",
                    compose, re.MULTILINE | re.DOTALL,
                ).group(1)
                self.assertIn("-Djava.io.tmpdir=/tmp", body)
                self.assertIn("-Dorg.bouncycastle.native.cpu_variant=java", body)
                self.assertIn("/tmp:rw,noexec,nosuid", body)

    def test_shell_entrypoints_parse(self):
        for directory in (ROOT / "deploy/production", ROOT / "deploy/production/scripts"):
            for path in directory.glob("*.sh"):
                shell = "bash" if "bash" in path.read_text().splitlines()[0] else "sh"
                result = subprocess.run([shell, "-n", str(path)], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, f"{path}: {result.stderr}")


@unittest.skipUnless(os.environ.get("COOKIE_DEPLOY_CONTAINER_TESTS") == "1", "set COOKIE_DEPLOY_CONTAINER_TESTS=1 for disposable Docker checks")
class ContainerBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.network = "cookie-notification-deploy-" + uuid.uuid4().hex[:10]
        cls.postgres = cls.network + "-postgres"
        cls.redis = cls.network + "-redis"
        cls.temporary = tempfile.TemporaryDirectory(prefix="cookie-deploy-fixture-")
        cls.fixture = Path(cls.temporary.name)
        cls.addClassCleanup(cls.temporary.cleanup)
        docker("network", "create", cls.network)
        cls.addClassCleanup(lambda: docker("network", "rm", cls.network, check=False))
        for name in (cls.postgres, cls.redis):
            cls.addClassCleanup(lambda name=name: docker("rm", "--force", name, check=False))
        docker("run", "--detach", "--name", cls.postgres, "--network", cls.network, "--network-alias", "postgres",
            "--env", "POSTGRES_PASSWORD=synthetic-admin-password", POSTGRES)
        for _ in range(90):
            if docker("exec", cls.postgres, "pg_isready", "-U", "postgres", check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("disposable PostgreSQL failed startup")
        secrets = cls.fixture / "secrets"
        secrets.mkdir()
        for filename, value in {"postgres-admin-password":"synthetic-admin-password", "postgres-notification-password":"synthetic-app-password", "postgres-notification-migration-password":"synthetic-migration-password"}.items():
            (secrets / filename).write_text(value)
        docker("exec", cls.postgres, "mkdir", "-p", "/run/secrets")
        docker("cp", str(secrets) + "/.", cls.postgres + ":/run/secrets")
        docker("cp", str(ROOT / "deploy/production/scripts/postgres-provision-notification.sh"), cls.postgres + ":/tmp/provision.sh")
        cls.sql("CREATE DATABASE identity;")
        cls.sql("CREATE TABLE identity_marker (value text); INSERT INTO identity_marker VALUES ('preserve-me');", database="identity")
        docker("exec", cls.postgres, "/bin/sh", "/tmp/provision.sh")

        docker("run", "--detach", "--name", cls.redis, "--network", cls.network,
            "--entrypoint", "/bin/sh", REDIS, "-c", "sleep 600")
        docker("exec", cls.redis, "mkdir", "-p", "/run/secrets", "/config", "/etc/redis")
        redis_password = cls.fixture / "redis-password"
        redis_password.write_text("synthetic-redis-password\n")
        docker("cp", str(redis_password), cls.redis + ":/run/secrets/redis-password")
        docker("exec", cls.redis, "chown", "999:999", "/run/secrets/redis-password")
        for source, target in [("scripts/redis-entrypoint.sh", "/config/redis-entrypoint.sh"), ("config/redis.conf", "/etc/redis/redis.conf")]:
            docker("cp", str(ROOT / "deploy/production" / source), cls.redis + ":" + target)
        docker("exec", "--detach", "--user", "999:999", cls.redis, "/bin/sh", "/config/redis-entrypoint.sh")
        for _ in range(30):
            if "PONG" in cls.redis_command("ping"):
                break
            time.sleep(1)
        else:
            raise RuntimeError("disposable Redis failed startup")

    @classmethod
    def sql(cls, statement, *, database="postgres", user="postgres", password="synthetic-admin-password", check=True):
        return docker("exec", "--interactive", "--env", "PGPASSWORD=" + password, cls.postgres,
            "psql", "--no-psqlrc", "--tuples-only", "--no-align", "--host", "127.0.0.1", "--username", user,
            "--dbname", database, "--set=ON_ERROR_STOP=1", input=statement, check=check)

    @classmethod
    def redis_command(cls, *args):
        return docker("exec", "--env", "REDISCLI_AUTH=synthetic-redis-password", cls.redis,
            "redis-cli", "--user", "notification", *args, check=False).stdout

    def test_repeated_provision_preserves_existing_domain_data(self):
        self.sql("CREATE TABLE provision_probe (id integer primary key); INSERT INTO provision_probe VALUES (42);",
            database="notification", user="notification_migrator", password="synthetic-migration-password")
        docker("exec", self.postgres, "/bin/sh", "/tmp/provision.sh")
        self.assertIn("42", self.sql("SELECT id FROM provision_probe", database="notification", user="notification_migrator", password="synthetic-migration-password").stdout)
        self.assertNotEqual(self.sql("SELECT id FROM provision_probe", database="notification",
            user="notification_app", password="synthetic-app-password", check=False).returncode, 0)
        self.assertIn("preserve-me", self.sql("SELECT value FROM identity_marker", database="identity").stdout)

    def test_runtime_grants_after_migration_and_repeated_provision(self):
        for migration in sorted((ROOT / "backend/services/notification/src/main/resources/db/migration").glob("*.sql")):
            self.sql(migration.read_text(), database="notification",
                user="notification_migrator", password="synthetic-migration-password")
        def app(statement, check=True):
            return self.sql(statement, database="notification", user="notification_app",
                password="synthetic-app-password", check=check)
        user = str(uuid.uuid4())
        app(f"INSERT INTO account_tombstones VALUES ('{user}','{uuid.uuid4()}',now())")
        for _ in range(2):
            self.assertNotEqual(app("DELETE FROM account_tombstones", check=False).returncode, 0)
            self.assertNotEqual(app("UPDATE account_tombstones SET deleted_at=now()", check=False).returncode, 0)
            self.assertNotEqual(app("UPDATE message_quarantine SET replayed_at=now()", check=False).returncode, 0)
            self.assertNotEqual(app("SELECT notification_apply_runtime_grants()", check=False).returncode, 0)
            app(f"INSERT INTO settings(user_id) VALUES ('{uuid.uuid4()}')")
            docker("exec", self.postgres, "/bin/sh", "/tmp/provision.sh")
        self.assertIn(user, app("SELECT user_id FROM account_tombstones").stdout)

    def test_application_cannot_create_tables_or_access_identity_data(self):
        ddl = self.sql("CREATE TABLE forbidden (id integer)", database="notification", user="notification_app", password="synthetic-app-password", check=False)
        self.assertNotEqual(ddl.returncode, 0)
        foreign = self.sql("SELECT * FROM identity_marker", database="identity", user="notification_app", password="synthetic-app-password", check=False)
        self.assertNotEqual(foreign.returncode, 0)

    def test_redis_accepts_only_notification_deadline_commands_and_keys(self):
        self.assertIn("1", self.redis_command("zadd", "cookie:notification:test-deadlines", "100", "opaque-reminder"))
        self.assertIn("opaque-reminder", self.redis_command("zrangebyscore", "cookie:notification:test-deadlines", "0", "200"))
        self.assertIn("NOPERM", self.redis_command("zadd", "other-service:deadlines", "100", "opaque-reminder"))
        self.assertIn("NOPERM", self.redis_command("set", "cookie:notification:arbitrary", "value"))
        self.assertIn("NOPERM", self.redis_command("flushall"))
        self.assertIn("NOAUTH", docker("exec", self.redis, "redis-cli", "ping", check=False).stdout)


class NatsProtocol:
    """Small synchronous protocol client to test ACLs without SDK privileges."""
    def __init__(self, port, user, prefix):
        import socket
        self.socket = socket.create_connection(("127.0.0.1", port), timeout=4)
        self.reader = self.socket.makefile("rb")
        self.prefix = prefix
        self.counter = 0
        try:
            self.reader.readline()  # Server INFO.
            self.socket.sendall(b"CONNECT " + json.dumps({"user":user,"pass":"synthetic-nats-password","verbose":False}).encode()+b"\r\nPING\r\n")
            self.flush_read()
        except Exception:
            self.close()
            raise

    def flush_read(self):
        while True:
            line = self.reader.readline()
            if line.startswith(b"-ERR"):
                raise PermissionError(line.decode())
            if line == b"PONG\r\n":
                return
            if line == b"PING\r\n":
                self.socket.sendall(b"PONG\r\n")
            if not line:
                raise RuntimeError("broker disconnected")

    def publish(self, subject, data=b""):
        self.socket.sendall(f"PUB {subject} {len(data)}\r\n".encode()+data+b"\r\nPING\r\n")
        self.flush_read()

    def request(self, subject, payload):
        self.counter += 1
        inbox = f"{self.prefix}.{self.counter}"
        data = json.dumps(payload).encode()
        self.socket.sendall(f"SUB {inbox} {self.counter}\r\nUNSUB {self.counter} 1\r\nPUB {subject} {inbox} {len(data)}\r\n".encode()+data+b"\r\n")
        while True:
            line = self.reader.readline()
            if line.startswith(b"-ERR"):
                raise PermissionError(line.decode())
            if line.startswith(b"MSG "):
                fields = line.decode().split()
                body = self.reader.read(int(fields[-1]))
                self.reader.read(2)
                return body, fields[3] if len(fields) == 5 else None
            if line == b"PING\r\n":
                self.socket.sendall(b"PONG\r\n")
            if not line:
                raise RuntimeError("broker disconnected")

    def json(self, subject, payload):
        value = json.loads(self.request(subject, payload)[0])
        if "error" in value:
            raise AssertionError(value["error"])
        return value

    def close(self):
        self.reader.close()
        self.socket.close()


@unittest.skipUnless(os.environ.get("COOKIE_DEPLOY_CONTAINER_TESTS") == "1", "set COOKIE_DEPLOY_CONTAINER_TESTS=1 for disposable Docker checks")
class BrokerBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.name = "cookie-notification-nats-" + uuid.uuid4().hex[:10]
        cls.box = cls.name + "-cli"
        cls.temporary = tempfile.TemporaryDirectory(prefix="cookie-nats-fixture-")
        cls.addClassCleanup(cls.temporary.cleanup)
        fixture = Path(cls.temporary.name)
        docker("network", "create", cls.name)
        cls.addClassCleanup(lambda: docker("network", "rm", cls.name, check=False))
        for name in (cls.name, cls.box):
            cls.addClassCleanup(lambda name=name: docker("rm", "--force", name, check=False))
        docker("run", "--detach", "--name", cls.name, "--network", cls.name, "--network-alias", "nats",
            "--publish", "127.0.0.1::4222", "--entrypoint", "/bin/sh",
            "nats:2.14.6-alpine3.22@sha256:ad7a43eb7e3337c3c38ce5d784d1461791f95f730f252d2b25eee699752a0ca3",
            "-c", "while [ ! -f /tmp/nats.conf ]; do sleep 1; done; exec nats-server --config /tmp/nats.conf")
        permissions = json.loads((ROOT / "deploy/production/config/notification-nats-permissions.json").read_text())
        config = {"listen":"0.0.0.0:4222", "jetstream":{"store_dir":"/tmp/jetstream"}, "authorization":{"users":[
            {"user":"admin","password":"synthetic-nats-password"},
            {"user":"notification","password":"synthetic-nats-password","permissions":permissions}]}}
        (fixture / "nats.conf").write_text(json.dumps(config))
        docker("cp", str(fixture / "nats.conf"), cls.name + ":/tmp/nats.conf")
        cls.port = int(docker("port", cls.name, "4222/tcp").stdout.strip().split(":")[-1])
        for _ in range(30):
            try:
                cls.admin = NatsProtocol(cls.port, "admin", "_INBOX.test.admin")
                break
            except (OSError, RuntimeError):
                time.sleep(1)
        else:
            raise RuntimeError("disposable NATS failed startup")
        cls.addClassCleanup(cls.admin.close)
        stream = json.loads((ROOT / "deploy/production/config/cookie-events.json").read_text())
        consumer = json.loads((ROOT / "deploy/production/config/notification-consumer.json").read_text())
        cls.admin.json("$JS.API.STREAM.CREATE.COOKIE_EVENTS", stream)
        cls.admin.json("$JS.API.CONSUMER.CREATE.COOKIE_EVENTS.notification-v1", {"stream_name":"COOKIE_EVENTS","config":consumer})
        docker("run", "--detach", "--name", cls.box, "--network", cls.name, "--entrypoint", "/bin/sh",
            "natsio/nats-box:0.19.7-nonroot@sha256:e86b9681f330ab1aa45744dd5cb367d44205b28fac2519a2f74ca0255803161a",
            "-c", "sleep 600")
        docker("cp", str(ROOT / "deploy/production/config/notification-consumer.json"), cls.box + ":/tmp/consumer.json")

    def test_allowed_fetch_ack_and_cli_convergence_preserve_cursor(self):
        self.admin.json("cookie.events.notification.email.requested.v1", {"fixture":"safe synthetic event"})
        client = NatsProtocol(self.port, "notification", "_INBOX.cookie.notification")
        self.addCleanup(client.close)
        body, ack_subject = client.request("$JS.API.CONSUMER.MSG.NEXT.COOKIE_EVENTS.notification-v1", {"batch":1,"expires":1000000000})
        self.assertIn(b"safe synthetic event", body)
        self.assertIsNotNone(ack_subject)
        client.publish(ack_subject, b"+ACK")
        before = client.json("$JS.API.CONSUMER.INFO.COOKIE_EVENTS.notification-v1", {})
        self.assertGreater(before["ack_floor"]["stream_seq"], 0)
        docker("exec", self.box, "nats", "--server=nats://nats:4222", "--user=admin", "--password=synthetic-nats-password",
            "consumer", "edit", "COOKIE_EVENTS", "notification-v1", "--config=/tmp/consumer.json", "--force")
        after = client.json("$JS.API.CONSUMER.INFO.COOKIE_EVENTS.notification-v1", {})
        self.assertEqual(before["ack_floor"], after["ack_floor"])

    def test_runtime_cannot_read_whole_stream_create_or_access_other_consumer(self):
        for subject in ("$JS.API.STREAM.MSG.GET.COOKIE_EVENTS", "$JS.API.CONSUMER.CREATE.COOKIE_EVENTS.other", "$JS.API.CONSUMER.INFO.COOKIE_EVENTS.other", "cookie.events.account.activated.v1"):
            with self.subTest(subject=subject):
                client = NatsProtocol(self.port, "notification", "_INBOX.cookie.notification")
                self.addCleanup(client.close)
                with self.assertRaises(PermissionError):
                    client.request(subject, {})



if __name__ == "__main__":
    unittest.main()
