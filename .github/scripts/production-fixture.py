"""Ephemeral cryptographic material for the production-container rehearsal.

No seeds, private keys or credentials are checked in. NKEY/JWT wire encodings
follow nats-io/nkeys and nats-io/jwt/v2; the real NATS server validates them.
"""
import base64
import binascii
import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, ed25519, rsa
from cryptography.x509.oid import NameOID


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def integer(value):
    return b64(value.to_bytes((value.bit_length() + 7) // 8, "big"))


def write(path, value, uid=0, mode=0o400):
    path = Path(path)
    path.write_bytes(value if isinstance(value, bytes) else value.encode())
    os.chown(path, uid, uid)
    path.chmod(mode)


def nkey(raw):
    return base64.b32encode(raw + binascii.crc_hqx(raw, 0).to_bytes(2, "little")).decode().rstrip("=")


class Nkey:
    def __init__(self, prefix):
        self.key = ed25519.Ed25519PrivateKey.generate()
        self.public = nkey(bytes([prefix]) + self.key.public_key().public_bytes_raw())
        self.seed = nkey(bytes([144 | (prefix >> 5), (prefix & 31) << 3]) + self.key.private_bytes_raw())

    def jwt(self, subject, kind, **fields):
        claims = {
            "iat": int(time.time()) - 10, "iss": self.public, "sub": subject,
            "name": "disposable-rehearsal", "nats": {"type": kind, "version": 2, **fields},
        }
        claims["jti"] = base64.b32encode(hashlib.sha256(json.dumps(claims).encode()).digest()).decode().rstrip("=")
        header = b64(b'{"typ":"JWT","alg":"ed25519-nkey"}')
        payload = b64(json.dumps(claims, separators=(",", ":")).encode())
        signed = f"{header}.{payload}"
        return signed + "." + b64(self.key.sign(signed.encode()))


def generate(root, repository):
    root = Path(root)
    secret = root / "secrets"
    for path, uid, mode in [
        (root / "postgres", 70, 0o750), (root / "nats", 1000, 0o750),
        (root / "releases", 0, 0o700), (secret, 0, 0o711),
        (secret / "postgres", 70, 0o700), (secret / "identity", 10001, 0o700),
        (secret / "identity/retiring", 10001, 0o700),
        (secret / "notification", 10001, 0o700),
        (secret / "notification/retiring", 10001, 0o700),
        (secret / "operators", 10001, 0o700),
        (secret / "nats", 1000, 0o700), (secret / "redis", 999, 0o700),
        (root / "fixture", 0, 0o755),
    ]:
        path.mkdir(parents=True, exist_ok=True)
        os.chown(path, uid, uid)
        path.chmod(mode)
    passwords = {name: b64(os.urandom(32)) for name in (
        "admin", "identity", "identity-migration", "notification", "notification-migration", "notification-operator", "redis", "smtp")}
    for name in ("admin", "identity", "identity-migration", "notification", "notification-migration"):
        write(secret / f"postgres/{name}-password", passwords[name], 70)
    for service in ("identity", "notification"):
        write(secret / service / "spring.datasource.password", passwords[service], 10001)
        write(secret / service / "spring.flyway.password", passwords[service + "-migration"], 10001)
        write(secret / service / f"cookie.{service}.nats-truststore-password", "fixture-truststore", 10001)
    write(secret / "operators/notification-db-password", passwords["notification-operator"], 10001)
    write(secret / "identity/cookie.identity.rate-limit-hmac-key", b64(os.urandom(32)), 10001)
    write(secret / "redis/password", passwords["redis"], 999)
    write(secret / "notification/spring.data.redis.password", passwords["redis"], 10001)
    write(secret / "notification/cookie.notification.delivery.smtp.password", passwords["smtp"], 10001)

    signing = ec.generate_private_key(ec.SECP256R1()).private_numbers()
    jwk = {"kty": "EC", "crv": "P-256", "use": "sig", "alg": "ES256", "kid": "fixture-signing",
           "x": b64(signing.public_numbers.x.to_bytes(32, "big")),
           "y": b64(signing.public_numbers.y.to_bytes(32, "big")),
           "d": b64(signing.private_value.to_bytes(32, "big"))}
    write(secret / "identity/identity-jwt-private.jwk", json.dumps(jwk), 10001)
    encryption = rsa.generate_private_key(public_exponent=65537, key_size=2048).private_numbers()
    public = {"kty": "RSA", "use": "enc", "alg": "RSA-OAEP-256", "kid": "fixture-encryption",
              "n": integer(encryption.public_numbers.n), "e": integer(encryption.public_numbers.e)}
    private = {**public, **{field: integer(getattr(encryption, attribute)) for field, attribute in (
        ("d", "d"), ("p", "p"), ("q", "q"), ("dp", "dmp1"), ("dq", "dmq1"), ("qi", "iqmp"))}}
    write(secret / "identity/notification-public.jwk", json.dumps(public), 10001)
    write(secret / "notification/notification-private.jwk", json.dumps(private), 10001)

    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Disposable rehearsal CA")])
    now = datetime.datetime.now(datetime.timezone.utc)
    ca = (x509.CertificateBuilder().subject_name(ca_name).issuer_name(ca_name)
          .public_key(ca_key.public_key()).serial_number(x509.random_serial_number())
          .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(now + datetime.timedelta(days=1))
          .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
          .add_extension(x509.KeyUsage(
              digital_signature=True, content_commitment=False, key_encipherment=False,
              data_encipherment=False, key_agreement=False, key_cert_sign=True,
              crl_sign=True, encipher_only=False, decipher_only=False), critical=True)
          .add_extension(x509.SubjectKeyIdentifier.from_public_key(ca_key.public_key()), critical=False)
          .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
          .sign(ca_key, hashes.SHA256()))
    write(secret / "nats/ca.crt", ca.public_bytes(serialization.Encoding.PEM), mode=0o444)
    for name, uid, directory in (("nats", 1000, secret / "nats"), ("fixture.cookie.test", 0, root / "fixture")):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        certificate = (x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)]))
            .issuer_name(ca_name).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName(name)]), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
            .sign(ca_key, hashes.SHA256()))
        write(directory / "server.crt", certificate.public_bytes(serialization.Encoding.PEM), mode=0o444)
        write(directory / "server.key", key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()), uid)
    for service in ("identity", "notification"):
        destination = secret / service / "nats-truststore.jks"
        subprocess.run(["keytool", "-importcert", "-noprompt", "-alias", "fixture",
                        "-file", str(secret / "nats/ca.crt"), "-keystore", str(destination),
                        "-storetype", "JKS", "-storepass", "fixture-truststore"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        os.chown(destination, 10001, 10001)
        destination.chmod(0o400)

    operator, system, account = Nkey(112), Nkey(0), Nkey(0)
    limits = {key: -1 for key in ("subs", "data", "payload", "imports", "exports", "conn", "leaf")}
    limits["wildcards"] = True
    system_jwt = operator.jwt(system.public, "account", limits=limits)
    account_jwt = operator.jwt(account.public, "account", limits={
        **limits, "mem_storage": 64 * 1024 * 1024, "disk_storage": 2 * 1024**3,
        "streams": 4, "consumer": 4,
    })
    write(secret / "nats/auth.conf", json.dumps({
        "operator": operator.jwt(operator.public, "operator", system_account=system.public),
        "system_account": system.public, "resolver": "MEMORY",
        "resolver_preload": {system.public: system_jwt, account.public: account_jwt},
    }), 1000)
    notification = json.loads((repository / "deploy/production/config/notification-nats-permissions.json").read_text())
    permissions = {
        "identity": {
            "pub": {"allow": [f"cookie.events.{name}.v1" for name in (
                "notification.email.requested", "account.activated", "account.deletion.requested")]},
            "sub": {"allow": ["_INBOX.cookie.identity.>"]}},
        "notification": {"pub": notification["publish"], "sub": notification["subscribe"]},
        "notification-operator": {
            "pub": {"allow": ["$JS.API.STREAM.MSG.GET.COOKIE_EVENTS"]},
            "sub": {"allow": ["_INBOX.cookie.notification_operator.>"]}},
        "stream-admin": {
            "pub": {"allow": [
                "$JS.API.STREAM.INFO.COOKIE_EVENTS", "$JS.API.STREAM.CREATE.COOKIE_EVENTS",
                "$JS.API.STREAM.UPDATE.COOKIE_EVENTS",
                "$JS.API.CONSUMER.INFO.COOKIE_EVENTS.notification-v1",
                "$JS.API.CONSUMER.CREATE.COOKIE_EVENTS.notification-v1",
                "$JS.API.CONSUMER.DURABLE.CREATE.COOKIE_EVENTS.notification-v1",
            ]},
            # The pinned nats CLI uses its random default reply inbox.
            "sub": {"allow": ["_INBOX.>"]}},
    }
    for name, permission in permissions.items():
        user = Nkey(160)
        jwt = account.jwt(user.public, "user", subs=-1, data=-1, payload=-1, **permission)
        creds = (f"-----BEGIN NATS USER JWT-----\n{jwt}\n------END NATS USER JWT------\n\n"
                 f"-----BEGIN USER NKEY SEED-----\n{user.seed}\n------END USER NKEY SEED------\n")
        directory, uid = ("nats", 1000) if name == "stream-admin" else (name, 10001)
        filename = "stream-admin.creds" if name == "stream-admin" else f"nats-{name}.creds"
        if name == "notification-operator":
            directory, filename = "operators", "notification-replay.creds"
        write(secret / directory / filename, creds, uid)
    return passwords
