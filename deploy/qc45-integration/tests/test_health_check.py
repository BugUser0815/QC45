import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


CHECK = Path(__file__).resolve().parents[1] / 'qc45-integration-health-check'


class DeploymentHealthTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.jar = self.root / 'integration.jar'
        self.jar.write_bytes(b'expected binary')
        self.sha = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        self.status = self.root / 'health.properties'
        self.proc = self.root / 'proc'
        boot = self.proc / 'sys/kernel/random/boot_id'
        boot.parent.mkdir(parents=True)
        boot.write_text('current-boot\n')
        self.values = dict(schema='1', state='ready', jarSha256=self.sha,
                           pid=str(os.getpid()), bootId='current-boot',
                           updatedAtMillis=str(int(time.time() * 1000)))

    def check(self, **changes):
        values = {**self.values, **changes}
        self.status.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
        return self.invoke()

    def invoke(self, mode='ready'):
        env = dict(os.environ, QC45_HEALTH_PROC_ROOT=str(self.proc))
        return subprocess.run(['sh', str(CHECK), str(self.jar), self.sha,
                               str(self.status), mode], env=env,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode == 0

    def test_ready_requires_current_boot_process_and_binary(self):
        self.assertTrue(self.check())

    def test_starting_degraded_and_stopped_are_not_success(self):
        for state in ('starting', 'degraded', 'stopped'):
            with self.subTest(state=state):
                self.assertFalse(self.check(state=state))

    def test_ready_log_line_cannot_override_degraded_state(self):
        (self.root / 'integration.log').write_text('[QC45] native integration started\n')
        self.assertFalse(self.check(state='degraded'))

    def test_log_rotation_and_truncation_do_not_change_health(self):
        self.assertTrue(self.check())
        log = self.root / 'integration.log'
        log.write_text('[QC45] native integration started\n' + 'data\n' * 100)
        log.rename(self.root / 'integration.log.1')
        log.write_text('new active log\n')
        self.assertTrue(self.invoke())

    def test_stale_or_future_status_is_rejected(self):
        self.assertFalse(self.check(updatedAtMillis=str(int((time.time() - 30) * 1000))))
        self.assertFalse(self.check(updatedAtMillis=str(int((time.time() + 30) * 1000))))

    def test_previous_boot_and_wrong_loaded_jar_are_rejected(self):
        self.assertFalse(self.check(bootId='old-boot'))
        self.assertFalse(self.check(jarSha256='0' * 64))

    def test_replaced_file_and_dead_process_are_rejected(self):
        self.assertFalse(self.check(pid='99999999'))
        self.jar.write_bytes(b'unexpected replacement')
        self.assertFalse(self.check())

    def test_missing_malformed_or_duplicate_metadata_is_rejected(self):
        self.assertFalse(self.invoke())
        self.assertFalse(self.check(pid='0'))
        self.assertFalse(self.check(pid='not-a-process'))
        self.assertFalse(self.check(updatedAtMillis='bad'))
        self.assertTrue(self.check())
        with self.status.open('a') as out:
            out.write('state=ready\n')
        self.assertFalse(self.invoke())

    def test_legacy_rollback_is_explicit_and_still_checks_binary(self):
        self.assertFalse(self.invoke())
        self.assertTrue(self.invoke('legacy-rollback'))
        self.assertFalse(self.invoke('invalid-mode'))
        self.jar.write_bytes(b'wrong rollback file')
        self.assertFalse(self.invoke('legacy-rollback'))


if __name__ == '__main__':
    unittest.main()
