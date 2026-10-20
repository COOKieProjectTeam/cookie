#!/usr/bin/env python3
"""Real production-profile deployment rehearsal on a disposable Linux Docker host.

Requires root, keytool, Docker Compose and two already-built service images.
Refuses to run when /srv/cookie already exists. No cloud access is performed:
test-owned command adapters replace ONLY Yandex metadata/login/image-reference
transport. All Compose operations, permissions, locks, signals, health probes,
PostgreSQL migrations, TLS/JWT NATS and application processes are real.
"""
import argparse
import base64
from email import policy
from email.parser import BytesParser
import http.server
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import socketserver
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
DATA = Path("/srv/cookie")
spec = importlib.util.spec_from_file_location("production_fixture", Path(__file__).with_name("production-fixture.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


def run(*args, check=True, env=None, input=None, timeout=240):
    result = subprocess.run(args, input=input, env=env, text=True, capture_output=True, timeout=timeout)
    if check and result.returncode:
        # Commands never contain generated passwords/private key material.
        raise RuntimeError(f"{' '.join(args)} failed ({result.returncode}):\n{result.stdout}\n{result.stderr}")
    return result


def eventually(predicate, description, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = predicate()
            if result:
                return result
        except (OSError, urllib.error.URLError, TimeoutError, RuntimeError, AssertionError):
            pass
        time.sleep(1)
    raise AssertionError("Timed out: " + description)


def request(port, path, *, body=None, token=None, expected=200):
    headers = {}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}", headers=headers,
        data=json.dumps(body).encode() if body is not None else None,
    )
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    content = response.read()
    if response.status != expected:
        # Only the bounded public error code is useful diagnostic data. Never
        # include response/request bodies which can contain session secrets.
        try:
            code = json.loads(content).get("code", "")
        except (ValueError, AttributeError):
            code = ""
        safe_code = code if isinstance(code, str) and re.fullmatch(r"[A-Z_]{1,80}", code) else "UNAVAILABLE"
        raise AssertionError(f"{path}: expected {expected}, got {response.status} ({safe_code})")
    return json.loads(content) if content else None


class JwksProxy(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/v1/auth/jwks":
            self.send_error(404)
            return
        try:
            with urllib.request.urlopen("http://127.0.0.1:8080/v1/auth/jwks", timeout=3) as response:
                body = response.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            self.send_error(503)

    def log_message(self, *args):
        pass


class SmtpFixture(socketserver.BaseRequestHandler):
    """STARTTLS + authenticated in-memory sink; never logs addresses/tokens."""
    def handle(self):
        connection = self.request
        connection.settimeout(15)
        reader = connection.makefile("rb")
        secure = authenticated = False

        def send(line):
            connection.sendall((line + "\r\n").encode())

        try:
            send("220 fixture.cookie.test ESMTP rehearsal")
            while True:
                line = reader.readline().decode().strip()
                if not line:
                    break
                command, _, value = line.partition(" ")
                command = command.upper()
                if command in ("EHLO", "HELO"):
                    send("250-fixture.cookie.test")
                    if not secure:
                        send("250-STARTTLS")
                    send("250 AUTH PLAIN LOGIN")
                elif command == "STARTTLS":
                    send("220 Ready for TLS")
                    reader.close()
                    connection = self.server.context.wrap_socket(connection, server_side=True)
                    reader = connection.makefile("rb")
                    secure = True
                elif command == "AUTH" and secure:
                    mechanism, _, data = value.partition(" ")
                    if mechanism.upper() == "PLAIN":
                        if not data:
                            send("334 ")
                            data = reader.readline().decode().strip()
                        _, username, password = base64.b64decode(data).decode().split("\0")
                    elif mechanism.upper() == "LOGIN":
                        if data:
                            username = base64.b64decode(data).decode()
                        else:
                            send("334 VXNlcm5hbWU6")
                            username = base64.b64decode(reader.readline()).decode()
                        send("334 UGFzc3dvcmQ6")
                        password = base64.b64decode(reader.readline()).decode()
                    else:
                        send("504 Unsupported authentication")
                        continue
                    authenticated = username == "fixture" and password == self.server.password
                    send("235 Authenticated" if authenticated else "535 Authentication failed")
                elif command in ("MAIL", "RCPT") and authenticated:
                    send("250 OK")
                elif command == "DATA" and authenticated:
                    send("354 End with dot")
                    chunks = []
                    while True:
                        chunk = reader.readline()
                        if chunk == b".\r\n":
                            break
                        if not chunk:
                            raise ConnectionError("Incomplete DATA")
                        chunks.append(chunk[1:] if chunk.startswith(b"..") else chunk)
                    self.server.messages.append(b"".join(chunks))
                    send("250 Accepted")
                elif command == "QUIT":
                    send("221 Bye")
                    break
                elif command in ("RSET", "NOOP"):
                    send("250 OK")
                else:
                    send("530 Authentication and STARTTLS required")
        except (OSError, ValueError):
            pass
        finally:
            reader.close()
            connection.close()


class ThreadingSmtp(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class Rehearsal:
    def __init__(self, args, work):
        self.work = work
        self.docker = shutil.which("docker")
        self.bundle = work / "production"
        shutil.copytree(ROOT / "deploy/production", self.bundle)
        self.envfile = work / "production.env"
        self.images = {}
        self.identity = self.image_reference("identity", args.identity_image)
        self.notification = self.image_reference("notification", args.notification_image)
        for name, instruction in (
            ("next", 'LABEL cookie.rehearsal="next"\n'),
            ("broken", 'ENTRYPOINT ["/bin/sh", "-c", "sleep 600"]\n'),
        ):
            dockerfile = work / f"{name}.Dockerfile"
            dockerfile.write_text(f"FROM {args.notification_image}\n{instruction}")
            tag = f"cookie/notification-rehearsal-{name}:{os.getpid()}"
            # setup-buildx may select a separate docker-container builder whose
            # store cannot see the already-loaded local service image.
            run(self.docker, "build", "--builder", "default", "--file", str(dockerfile), "--tag", tag, str(work))
            setattr(self, name, self.image_reference("notification", tag))
        self.overlay = work / "fixture-compose.json"
        # The sole runtime overlay installs the generated CA for HTTPS/SMTP and
        # resolves the host-only fixture endpoints. Production TLS stays enabled.
        self.overlay.write_text(json.dumps({"services": {"notification": {
            "extra_hosts": ["fixture.cookie.test:host-gateway"],
        }}}))
        mapping = work / "images.json"
        mapping.write_text(json.dumps(self.images))
        bindir = work / "bin"
        bindir.mkdir()
        # Keep the production script byte-for-byte intact. No fake stat/flock,
        # Compose, health responses, container state or permission decisions.
        docker_adapter = f"""#!{sys.executable}
import json, os, subprocess, sys
mapping = json.load(open({str(mapping)!r}))
args = sys.argv[1:]
if args[:1] == ["login"] and args[-1:] == ["cr.yandex"]:
    sys.stdin.read()
    sys.exit(0)
if args[:1] == ["pull"] and args[-1] in mapping:
    # Already-built immutable local image; no private registry is contacted.
    sys.exit(subprocess.run([{self.docker!r}, "image", "inspect", mapping[args[-1]]],
        stdout=subprocess.DEVNULL).returncode)
if args[:2] == ["image", "inspect"] and args[-1] in mapping:
    args[-1] = mapping[args[-1]]
if args[:1] == ["compose"] and "--file" in args:
    for key in ("IDENTITY_IMAGE", "NOTIFICATION_IMAGE"):
        if os.environ.get(key) in mapping:
            os.environ[key] = mapping[os.environ[key]]
    # Insert after the production file so only the fixture CA/DNS is overlaid.
    position = args.index("--file") + 2
    args[position:position] = ["--file", {str(self.overlay)!r}]
os.execv({self.docker!r}, [{self.docker!r}, *args])
"""
        curl_adapter = f"""#!{sys.executable}
import os, sys
if sys.argv[-1:] == ["http://169.254.169.254/computeMetadata/v1/instance/service-accounts/default/token"]:
    print('{{"access_token":"disposable-rehearsal"}}')
else:
    os.execv({shutil.which("curl")!r}, [{shutil.which("curl")!r}, *sys.argv[1:]])
"""
        for name, code in (("docker", docker_adapter), ("curl", curl_adapter)):
            (bindir / name).write_text(code)
            (bindir / name).chmod(0o700)
        self.environment = {**os.environ,
            "PATH": str(bindir) + os.pathsep + os.environ["PATH"],
            "COOKIE_PRODUCTION_ENV_FILE": str(self.envfile),
            "COOKIE_DEPLOY_HEALTH_TIMEOUT_SECONDS": "120",
            "IDENTITY_IMAGE": self.identity, "NOTIFICATION_IMAGE": self.notification,
        }
        self.servers = []

    def image_reference(self, service, image):
        immutable = run(self.docker, "image", "inspect", "--format", "{{.Id}}", image).stdout.strip()
        reference = f"cr.yandex/fixture001/{service}@{immutable}"
        self.images[reference] = immutable
        return reference

    def compose(self, *args, check=True):
        return run("docker", "compose", "--project-directory", str(self.bundle),
                   "--env-file", str(self.envfile), "--file", str(self.bundle / "compose.yaml"),
                   *args, env=self.environment, check=check)

    def container(self, service):
        return self.compose("ps", "--quiet", service).stdout.strip()

    def image(self, service):
        return run(self.docker, "inspect", "--format", "{{.Image}}", self.container(service)).stdout.strip()

    def sql(self, database, statement):
        return run(self.docker, "exec", "--interactive", self.container("postgres"),
                   "psql", "--no-psqlrc", "--username=postgres", f"--dbname={database}",
                   "--tuples-only", "--no-align", "--set=ON_ERROR_STOP=1",
                   input=statement).stdout.strip()

    def setup(self):
        passwords = fixture.generate(DATA, ROOT)
        self.envfile.write_text("\n".join([
            "YC_REGISTRY_ID=fixture001", "COOKIE_DATA_ROOT=/srv/cookie",
            "COOKIE_SECRET_ROOT=/srv/cookie/secrets",
            "COOKIE_IDENTITY_BIND_IP=127.0.0.1", "COOKIE_NOTIFICATION_BIND_IP=127.0.0.1",
            "COOKIE_IDENTITY_ISSUER=https://fixture.cookie.test:8443",
            "COOKIE_IDENTITY_JWKS_URL=https://fixture.cookie.test:8443/v1/auth/jwks",
            "COOKIE_NOTIFICATION_VERIFICATION_LINK_BASE_URL=https://fixture.cookie.test/verify",
            "COOKIE_SMTP_HOST=fixture.cookie.test", "COOKIE_SMTP_PORT=2525",
            "COOKIE_SMTP_USERNAME=fixture", "COOKIE_SMTP_FROM=noreply@fixture.cookie.test",
            "COOKIE_SMTP_MESSAGE_ID_DOMAIN=fixture.cookie.test", "COOKIE_APNS_ENABLED=false", "COOKIE_FCM_ENABLED=false",
        ]) + "\n")
        self.envfile.chmod(0o600)
        # Read the original Compose model with the real CLI. Retain every
        # production JVM option instead of maintaining a fixture copy.
        baseline = json.loads(run(self.docker, "compose", "--project-directory", str(self.bundle),
            "--env-file", str(self.envfile), "--file", str(self.bundle / "compose.yaml"),
            "config", "--format", "json", env=self.environment).stdout)
        options = baseline["services"]["notification"]["environment"]["JAVA_TOOL_OPTIONS"]
        self.overlay.write_text(json.dumps({"services": {"notification": {
            "extra_hosts": ["fixture.cookie.test:host-gateway"],
            "environment": {"JAVA_TOOL_OPTIONS": options + (
                " -Djavax.net.ssl.trustStore=/run/secrets/nats-truststore.jks"
                " -Djavax.net.ssl.trustStorePassword=fixture-truststore"
            )},
        }}}))
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(DATA / "fixture/server.crt", DATA / "fixture/server.key")
        https = http.server.ThreadingHTTPServer(("0.0.0.0", 8443), JwksProxy)
        https.socket = context.wrap_socket(https.socket, server_side=True)
        smtp = ThreadingSmtp(("0.0.0.0", 2525), SmtpFixture)
        smtp.context, smtp.password, smtp.messages = context, passwords["smtp"], []
        self.smtp = smtp
        self.servers = [https, smtp]
        for server in self.servers:
            threading.Thread(target=server.serve_forever, daemon=True).start()

    def deploy(self, *args, check=True, timeout=420):
        result = run("bash", str(self.bundle / "deploy.sh"), *args, env=self.environment, check=check, timeout=timeout)
        # Success-only output, to keep logs useful and secrets out of CI artifacts.
        if result.returncode == 0:
            print("Deployment succeeded:", " ".join(args), flush=True)
        return result

    def assert_release(self, identity, notification):
        assert (DATA / "releases/current.env").read_text() == f"IDENTITY_IMAGE={identity}\nNOTIFICATION_IMAGE={notification}\n"
        assert not (DATA / "releases/pending.env").exists()
        assert self.image("identity") == self.images[identity]
        assert self.image("notification") == self.images[notification]
        request(8080, "/readyz")
        request(8082, "/readyz")

    def registration(self):
        registration = {
            # Identity admits only .ru/.рф. SMTP always points to our local sink;
            # no DNS/MX lookup or external delivery occurs for this address.
            "registrationAttemptId": str(uuid.uuid4()), "email": f"fixture-{uuid.uuid4().hex}@example.ru",
            "password": "Synthetic-Password-438!", "registrationProof": fixture.b64(os.urandom(32)), "locale": "en",
        }
        request(8080, "/v1/auth/email/register", body=registration, expected=202)
        return registration

    def finish_registration(self, registration):
        def delivered_token():
            for raw in self.smtp.messages:
                message = BytesParser(policy=policy.default).parsebytes(raw)
                if registration["email"] not in message["To"]:
                    continue
                body = message.get_body(preferencelist=("plain",)).get_content()
                match = re.search(r"#(?:token=)?([A-Za-z0-9_.-]{121})", body)
                if match:
                    return match.group(1)
            return None
        token = eventually(delivered_token, "JWE email event reaches authenticated STARTTLS SMTP", timeout=90)
        request(8080, "/v1/auth/email/verification/confirm",
                body={"token": token, "registrationProof": registration["registrationProof"]}, expected=204)
        login = request(8080, "/v1/auth/email/login", body={
            "email": registration["email"], "password": registration["password"]})
        request(8082, "/v1/notifications/settings", token=login["accessToken"])
        # Exercise trigger/function permissions through the restricted app role,
        # including the public token limit that a raw B-tree index cannot store.
        android_token = base64.b64encode(os.urandom(3072)).decode("ascii")
        android = request(8082, "/v1/notifications/devices", token=login["accessToken"],
                          body={"platform": "ANDROID", "deviceId": str(uuid.uuid4()), "token": android_token},
                          expected=201)
        assert android["token"] == android_token
        ios_token = "ABCD" * 16
        ios = request(8082, "/v1/notifications/devices", token=login["accessToken"],
                      body={"platform": "IOS", "deviceId": str(uuid.uuid4()), "token": ios_token},
                      expected=201)
        assert ios["token"] == ios_token.lower()
        eventually(lambda: self.sql("notification", "SELECT count(*) FROM delivery_jobs WHERE status='ACCEPTED'") != "0",
                   "provider acceptance recorded")
        return login

    def fresh_install(self):
        print("Rehearsal: fresh two-service install and secret-permission rejection", flush=True)
        self.setup()
        secret = DATA / "secrets/notification/notification-private.jwk"
        secret.chmod(0o444)
        failed = self.deploy("--identity", self.identity, "--notification", self.notification, check=False)
        assert failed.returncode != 0 and "inaccessible by group/other" in failed.stderr
        assert not (DATA / "releases/current.env").exists()
        secret.chmod(0o400)
        self.deploy("--identity", self.identity, "--notification", self.notification)
        self.assert_release(self.identity, self.notification)
        self.finish_registration(self.registration())
        request(8082, "/v1/notifications/settings", expected=401)
        for service in ("postgres", "redis", "nats"):
            ports = json.loads(run(self.docker, "inspect", "--format", "{{json .HostConfig.PortBindings}}",
                                   self.container(service)).stdout)
            assert not ports, f"{service} unexpectedly publishes infrastructure ports"
        print("Fresh install: production profiles, TLS/JWT NATS, HTTPS JWKS and STARTTLS email passed", flush=True)

    def identity_upgrade(self):
        print("Rehearsal: actual Identity-only volume upgrade and independent rollout", flush=True)
        self.setup()
        self.compose("up", "--detach", "postgres", "nats")
        eventually(lambda: self.sql("identity", "SELECT 1") == "1", "Identity PostgreSQL starts")
        eventually(lambda: run(self.docker, "inspect", "--format", "{{.State.Health.Status}}",
                   self.container("nats")).stdout.strip() == "healthy", "TLS NATS starts")
        self.compose("run", "--rm", "--no-deps", "nats-init")
        self.compose("up", "--detach", "--no-deps", "identity")
        eventually(lambda: request(8080, "/readyz") is not False, "Identity ready")
        registration = self.registration()
        identity_container = self.container("identity")
        before = self.sql("identity", f"SELECT request_fingerprint FROM registration_attempts WHERE id='{registration['registrationAttemptId']}'")
        assert len(before) == 64
        assert self.sql("postgres", "SELECT count(*) FROM pg_database WHERE datname='notification'") == "0"
        fixture.write(DATA / "releases/current.env", f"IDENTITY_IMAGE={self.identity}\n", mode=0o600)
        self.deploy("--notification", self.notification)
        assert self.container("identity") == identity_container, "Notification installation restarted Identity"
        assert self.sql("identity", f"SELECT request_fingerprint FROM registration_attempts WHERE id='{registration['registrationAttemptId']}'") == before
        self.assert_release(self.identity, self.notification)
        login = self.finish_registration(registration)
        self.deploy("--notification", self.next)
        assert self.container("identity") == identity_container
        self.assert_release(self.identity, self.next)

        print("Rehearsal: failed real-container update rolls back only Notification", flush=True)
        failed = self.deploy("--notification", self.broken, check=False)
        assert failed.returncode != 0, "Intentionally broken image was accepted"
        self.assert_release(self.identity, self.next)
        assert self.container("identity") == identity_container
        request(8082, "/v1/notifications/settings", token=login["accessToken"])

        print("Rehearsal: SIGKILL leaves journal; next deploy recovers before applying update", flush=True)
        log = self.work / "interrupted-deploy.log"
        with log.open("w") as output:
            process = subprocess.Popen(["bash", str(self.bundle / "deploy.sh"), "--notification", self.broken],
                env=self.environment, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                eventually(lambda: (
                    (DATA / "releases/pending.env").exists()
                    and "ATTEMPTED=notification\n" in (DATA / "releases/pending.env").read_text()
                    and self.image("notification") == self.images[self.broken]
                ), "broken image running with durable deployment journal")
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=15)
            finally:
                if process.poll() is None:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=15)
        assert (DATA / "releases/pending.env").exists()
        recovered = self.deploy("--notification", self.notification)
        assert "previous committed release restored" in recovered.stderr
        self.assert_release(self.identity, self.notification)
        assert self.container("identity") == identity_container
        request(8082, "/v1/notifications/settings", token=login["accessToken"])
        assert self.sql("identity", "SELECT count(*) FROM accounts") == "1", "Rollback lost Identity data"
        assert self.sql("notification", "SELECT count(*) FROM flyway_schema_history WHERE success") != "0"
        print("Upgrade, independent image selection, failed rollout and interrupted recovery passed", flush=True)

    def cleanup(self):
        # Called only after this harness successfully reserved /srv/cookie.
        if self.envfile.exists():
            result = self.compose("down", "--volumes", "--remove-orphans", check=False)
            if result.returncode:
                raise RuntimeError("Could not clean up disposable Compose project: " + result.stderr)
        for server in self.servers:
            server.shutdown()
            server.server_close()
        self.servers = []
        shutil.rmtree(DATA)
        lock = Path("/run/lock/cookie-production-deploy.lock")
        lock.unlink(missing_ok=True)

    def diagnostics(self):
        result = self.compose("logs", "--no-color", "--tail", "80", check=False)
        # Application logs must already be secret-safe. Never print config/secrets,
        # SMTP messages, JWTs, or the synthetic API request/response bodies.
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
        try:
            print("Delivery state counts (no payloads):", self.sql("notification",
                "SELECT channel,status,last_code,count(*) FROM delivery_jobs "
                "GROUP BY channel,status,last_code ORDER BY channel,status,last_code;"), file=sys.stderr)
            print("Messages accepted by synthetic SMTP:", len(self.smtp.messages), file=sys.stderr)
        except (RuntimeError, AttributeError):
            pass  # A failed first installation may not have created this DB yet.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity-image", required=True)
    parser.add_argument("--notification-image", required=True)
    args = parser.parse_args()
    if sys.platform != "linux" or os.geteuid() != 0 or os.environ.get("GITHUB_ACTIONS") != "true":
        parser.error("run as root on a disposable GitHub Actions Linux runner only")
    for command in ("docker", "keytool", "curl", "jq", "flock"):
        if not shutil.which(command):
            parser.error(f"missing {command}")
    if DATA.exists() or Path("/run/lock/cookie-production-deploy.lock").exists():
        parser.error("refusing to touch an existing /srv/cookie or deployment lock")
    for port in (8080, 8082, 8443, 2525):
        with socket.socket() as probe:
            probe.bind(("0.0.0.0", port))
    with tempfile.TemporaryDirectory(prefix="cookie-production-rehearsal-") as temporary:
        rehearsal = Rehearsal(args, Path(temporary))
        for scenario in (rehearsal.fresh_install, rehearsal.identity_upgrade):
            DATA.mkdir(mode=0o755)  # Fail closed if another process creates it.
            failed = False
            try:
                scenario()
            except BaseException:
                failed = True
                try:
                    rehearsal.diagnostics()
                except Exception as diagnostic_error:
                    print("Diagnostics unavailable:", diagnostic_error, file=sys.stderr)
                raise
            finally:
                try:
                    rehearsal.cleanup()
                except Exception as cleanup_error:
                    if not failed:
                        raise
                    print("Additional cleanup failure:", cleanup_error, file=sys.stderr)
    print("All real production-container rehearsals passed.", flush=True)


if __name__ == "__main__":
    main()
