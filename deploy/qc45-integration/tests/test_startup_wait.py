import os
from pathlib import Path
import subprocess
import tempfile
import unittest


HELPER = Path(__file__).resolve().parents[1] / 'qc45-integration-wait'


class StartupWaitTest(unittest.TestCase):
    def run_wait(self, probe):
        # Only the clock, sleep and SSH boundary are replaced. The real wait
        # function and the complete health-check command are exercised.
        script = r'''
set -eu
source "$HELPER"
QC45_REMOTE_JAR=/test/integration.jar
QC45_REMOTE_HEALTH=/test/health.properties
QC45_SSH_TARGET=root@test
ssh_probe_args=()
health_check=/dev/null
SECONDS=0
sleep() { SECONDS=$((SECONDS + $1)); }
ssh() {
    if [[ "$*" != *status_evcsd.sh* || "$*" != *expected-sha* || "$*" != *ready* ]]; then
        exit 90
    fi
    PROBE
}
if wait_for_integration expected-sha; then
    printf 'READY_AT=%s\n' "$SECONDS"
else
    printf 'TIMED_OUT_AT=%s\n' "$SECONDS"
    exit 1
fi
'''.replace('PROBE', probe)
        return subprocess.run(['bash', '-c', script], env=dict(os.environ, HELPER=str(HELPER)),
                              capture_output=True, text=True, timeout=5)

    def test_start_beyond_old_three_minute_limit_succeeds(self):
        result = self.run_wait('((SECONDS >= 210))')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('READY_AT=216', result.stdout)

    def test_success_requires_three_consecutive_samples(self):
        result = self.run_wait('((SECONDS == 0 || SECONDS >= 6))')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('READY_AT=12', result.stdout)

    def test_unhealthy_application_still_times_out(self):
        result = self.run_wait('return 1')
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn('TIMED_OUT_AT=600', result.stdout)

    def test_failed_ssh_is_kept_as_diagnostic_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            script = r'''
set -eu
source "$HELPER"
QC45_STATE_DIR="$DIAGNOSTIC_DIR"
QC45_SSH_TARGET=root@test
ssh_probe_args=()
ssh() { echo 'SSH unreachable' >&2; return 255; }
capture_startup_diagnostics
'''
            result = subprocess.run(['bash', '-c', script], capture_output=True, text=True,
                                    env=dict(os.environ, HELPER=str(HELPER), DIAGNOSTIC_DIR=directory))
            self.assertEqual(result.returncode, 0, result.stderr)
            files = list(Path(directory).glob('failed-startup-*.log'))
            self.assertEqual(len(files), 1)
            self.assertIn('SSH unreachable', files[0].read_text())

    def test_diagnostic_storage_failure_does_not_block_rollback(self):
        with tempfile.TemporaryDirectory() as directory:
            script = r'''
set -eu
source "$HELPER"
QC45_STATE_DIR="$DIAGNOSTIC_DIR/missing"
QC45_SSH_TARGET=root@test
ssh_probe_args=()
ssh() { return 255; }
capture_startup_diagnostics
echo 'ROLLBACK_CONTINUES'
'''
            result = subprocess.run(['bash', '-c', script], capture_output=True, text=True,
                                    env=dict(os.environ, HELPER=str(HELPER), DIAGNOSTIC_DIR=directory))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('ROLLBACK_CONTINUES', result.stdout)
            self.assertIn('Could not save startup diagnostics', result.stderr)


if __name__ == '__main__':
    unittest.main()
