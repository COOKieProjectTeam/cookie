import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = REPOSITORY_ROOT / "infra/terraform/environments/production/cloud-init.yaml.tftpl"


def bootstrap_script():
    content = TEMPLATE.read_text().split(
        "  - path: /usr/local/sbin/cookie-bootstrap\n", 1
    )[1].split("    content: |\n", 1)[1]
    lines = []
    for line in content.splitlines():
        if line and not line.startswith("      "):
            break
        lines.append(line)
    return textwrap.dedent("\n".join(lines)).replace(
        "${admin_username}", "cookieops"
    ).replace("$${VERSION_CODENAME}", "${VERSION_CODENAME}")


class BootstrapDiskTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="cookie-bootstrap-test-")
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.fstab = self.directory / "fstab"
        self.fstab.write_text("")
        self.calls = self.directory / "calls"
        self.calls.write_text("")

    def run_bootstrap(self, **overrides):
        script = bootstrap_script().split("apt-get update", 1)[0]
        script = script.replace("/etc/fstab", str(self.fstab)).replace(
            "/srv/cookie", str(self.directory / "data")
        )
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
mount() { record "mount $*"; }
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
