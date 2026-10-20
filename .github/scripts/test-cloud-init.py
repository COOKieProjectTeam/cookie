import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = REPOSITORY_ROOT / "infra/terraform/environments/production/cloud-init.yaml.tftpl"


def cloud_init_file(path):
    content = TEMPLATE.read_text().split(
        f"  - path: {path}\n", 1
    )[1].split("    content: |\n", 1)[1]
    lines = []
    for line in content.splitlines():
        if line and not line.startswith("      "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines))


def bootstrap_script():
    return cloud_init_file("/usr/local/sbin/cookie-bootstrap").replace(
        "${admin_username}", "cookieops"
    ).replace("$${VERSION_CODENAME}", "${VERSION_CODENAME}")


class BootstrapDiskTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="cookie-bootstrap-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.etc = self.directory / "etc"
        (self.etc / "apt/keyrings").mkdir(parents=True)
        (self.etc / "apt/sources.list.d").mkdir()
        (self.etc / "os-release").write_text("VERSION_CODENAME=test\n")
        self.fstab = self.etc / "fstab"
        self.fstab.write_text("")
        self.calls = self.directory / "calls"
        self.calls.write_text("")

    def run_bootstrap(self, **overrides):
        script = bootstrap_script().replace("/etc/", f"{self.etc}/").replace(
            "/srv/cookie", str(self.directory / "data")
        ).replace("/opt/cookie", str(self.directory / "opt"))
        mocks = r'''
record() { printf '%s\n' "$*" >> "$MOCK_CALLS"; }
function [() {
  if [[ "${1:-}" == -b || ( "${1:-}" == '!' && "${2:-}" == -b ) ]]; then
    if [[ "${1:-}" == '!' ]]; then
      [[ "$MOCK_DEVICE_PRESENT" != true ]]
    else
      [[ "$MOCK_DEVICE_PRESENT" == true ]]
    fi
  else
    builtin [ "$@"
  fi
}
seq() { printf '1\n'; }
sleep() { :; }
blkid() {
  record "blkid $*"
  case " $* " in
    *' TYPE '*)
      printf '%s\n' "$MOCK_FILESYSTEM"
      return "$MOCK_PROBE_STATUS"
      ;;
    *' UUID '*)
      printf '%s\n' "$MOCK_UUID"
      return "$MOCK_UUID_STATUS"
      ;;
    *) return "$MOCK_PROBE_STATUS" ;;
  esac
}
install() { record "install $*"; }
mountpoint() { [[ "$MOCK_MOUNTED" == true ]]; }
mount() { record "mount $*"; return "$MOCK_MOUNT_STATUS"; }
systemctl() {
  record "systemctl $*"
  if [[ "$1" == daemon-reload ]]; then
    return "$MOCK_RELOAD_STATUS"
  fi
}
apt-get() { record "apt-get $*"; }
curl() { record "curl $*"; }
chmod() { record "chmod $*"; }
dpkg() { printf 'amd64\n'; }
usermod() { record "usermod $*"; }
mkfs.ext4() { record "FORMAT $*"; }
mkfs() { record "FORMAT $*"; }
wipefs() { record "WIPE $*"; }
'''
        environment = os.environ.copy()
        environment.update(
            MOCK_CALLS=str(self.calls),
            MOCK_DEVICE_PRESENT="true",
            MOCK_FILESYSTEM="ext4",
            MOCK_PROBE_STATUS="0",
            MOCK_UUID="e3774bcd-48d6-418e-af51-30c135a70d9b",
            MOCK_UUID_STATUS="0",
            MOCK_MOUNTED="false",
            MOCK_MOUNT_STATUS="0",
            MOCK_RELOAD_STATUS="0",
        )
        environment.update(overrides)
        result = subprocess.run(
            ["bash"], input=mocks + script, env=environment,
            text=True, capture_output=True, timeout=5,
        )
        self.assertNotIn("FORMAT", self.calls.read_text())
        self.assertNotIn("WIPE", self.calls.read_text())
        return result

    def assert_disk_rejected(self, **overrides):
        result = self.run_bootstrap(**overrides)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("mount ", self.calls.read_text())
        self.assertNotIn("apt-get ", self.calls.read_text())
        self.assertNotIn("systemctl ", self.calls.read_text())
        self.assertEqual(self.fstab.read_text(), "")
        return result

    def test_bootstrap_shell_syntax(self):
        result = subprocess.run(
            ["bash", "-n"], input=bootstrap_script(),
            text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_existing_ext4_is_mounted_by_uuid(self):
        result = self.run_bootstrap()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("blkid -p -s TYPE -o value", self.calls.read_text())
        self.assertIn("mount ", self.calls.read_text())
        self.assertEqual(
            self.fstab.read_text(),
            "UUID=e3774bcd-48d6-418e-af51-30c135a70d9b "
            f"{self.directory}/data ext4 defaults,nofail 0 2\n",
        )

    def test_docker_requires_persistent_mount(self):
        unit = cloud_init_file(
            "/etc/systemd/system/docker.service.d/10-cookie-data.conf"
        )
        self.assertEqual(
            unit.strip().splitlines(),
            ["[Unit]", "RequiresMountsFor=/srv/cookie"],
        )

    def test_mount_and_reload_precede_docker_installation(self):
        result = self.run_bootstrap()
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls.read_text().splitlines()
        mounted = calls.index(f"mount {self.directory}/data")
        reloaded = calls.index("systemctl daemon-reload")
        installed = calls.index(
            "apt-get install -y --no-install-recommends docker-ce docker-ce-cli "
            "containerd.io docker-buildx-plugin docker-compose-plugin"
        )
        enabled = calls.index("systemctl enable --now docker")
        self.assertLess(mounted, reloaded)
        self.assertLess(reloaded, installed)
        self.assertLess(installed, enabled)

    def test_mount_failure_prevents_docker_installation(self):
        result = self.run_bootstrap(MOCK_MOUNT_STATUS="32")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("systemctl ", self.calls.read_text())
        self.assertNotIn("apt-get ", self.calls.read_text())

    def test_reload_failure_prevents_docker_installation(self):
        result = self.run_bootstrap(MOCK_RELOAD_STATUS="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("systemctl daemon-reload", self.calls.read_text())
        self.assertNotIn("apt-get ", self.calls.read_text())
        self.assertNotIn("systemctl enable", self.calls.read_text())

    def test_repeated_bootstrap_preserves_fstab_and_mount(self):
        result = self.run_bootstrap()
        self.assertEqual(result.returncode, 0, result.stderr)
        previous_fstab = self.fstab.read_text()
        self.calls.write_text("")
        result = self.run_bootstrap(MOCK_MOUNTED="true")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.fstab.read_text(), previous_fstab)
        self.assertNotIn("mount ", self.calls.read_text())

    def test_blank_corrupt_and_failed_probes_are_rejected(self):
        for status in ("2", "4", "8"):
            with self.subTest(probe_status=status):
                result = self.assert_disk_rejected(MOCK_PROBE_STATUS=status)
                self.assertIn("Cannot identify cookie-data", result.stderr)

    def test_unknown_filesystem_is_rejected(self):
        for filesystem in ("", "xfs", "crypto_LUKS"):
            with self.subTest(filesystem=filesystem):
                self.assert_disk_rejected(MOCK_FILESYSTEM=filesystem)

    def test_missing_or_unreadable_uuid_is_rejected(self):
        for overrides in ({"MOCK_UUID": ""}, {"MOCK_UUID_STATUS": "4"}):
            with self.subTest(overrides=overrides):
                result = self.assert_disk_rejected(**overrides)
                self.assertIn("Cannot read cookie-data filesystem UUID", result.stderr)

    def test_missing_device_is_rejected(self):
        result = self.assert_disk_rejected(MOCK_DEVICE_PRESENT="false")
        self.assertIn("Attached cookie-data disk was not found", result.stderr)
        self.assertNotIn("blkid ", self.calls.read_text())


if __name__ == "__main__":
    unittest.main()
