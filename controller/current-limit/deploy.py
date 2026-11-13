"""Python 2.7 on the specific QC45 image. Vehicle unplugged + EVCS idle only.
Backs up the known installed v1/v2/v3/v4/v5, updates its persistence helper, and restarts
the single EVACom screen. An unsuccessful start restores the complete previous
installation. --rollback restores the manifest, not the factory defaults.
"""
from __future__ import print_function
import argparse
import hashlib
import imp
import json
import os
import signal
import stat
import subprocess
import tempfile
import time

BASE = '/home/evacom/libETransport.so.1'
BASE_HASH = 'f93941b429cf7c29d1e2e65836de4791de644210ff10164ff640b5e22dfc06fe'
OLD_HASH = 'ac18af50e5039a8ef89ab1a688e01ae288b8cd4ea14f13b218bd8fa5e961e36d'
V2_HASH = '22792afbaca269c08619e38ade35f1c9d0887b99fffae0b9fdcc8966e92a697a'
V5_HASH = '49fe3635591de64b4fe376e010f5667c34b6fc571354673c14e66e90ca62a2f0'
V4_HASH = 'c8ec015057126a7b0bcb31ebf60faedcfd93383210ba4d3754eed5d43763073d'
V3_HASH = '3fd84c4432fc10433e3e70a1d7564cdd8457e497762da13a1c025f9b5f519066'
FIX = '/home/qc45-fixes/power-limit/libqc45_powerlimit_fix.so'
V2_SOURCE = '/home/qc45-fixes/power-limit/libqc45_current_limit_v2.so'
V3_SOURCE = '/home/qc45-fixes/power-limit/libqc45_current_limit_v3.so'
V4_SOURCE = '/home/qc45-fixes/power-limit/libqc45_current_limit_v4.so'
V5_SOURCE = '/home/qc45-fixes/power-limit/libqc45_current_limit_v5.so'
SOURCE = '/home/qc45-fixes/power-limit/libqc45_current_limit_v6.so'
HELPER = '/home/qc45_power_persist.py'
STARTS = ('/home/EVAStart', '/home/evacom/EVAStart')
APP = '/home/evacom/EVACom_Evse_Gui'


def read(path):
    with open(path, 'rb') as f:
        return f.read()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write(path, data, mode):
    if os.path.islink(path):
        raise RuntimeError('Unexpected symlink: '+path)
    fd, temporary = tempfile.mkstemp(prefix='.currentcap-', dir=os.path.dirname(path))
    try:
        with os.fdopen(fd, 'wb') as f:
            f.write(data)
            os.fchmod(f.fileno(), mode)
            f.flush()
            os.fsync(f.fileno())
        os.rename(temporary, path)
        d = os.open(os.path.dirname(path), os.O_RDONLY)
        try:
            os.fsync(d)
        finally:
            os.close(d)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def apps():
    result = []
    for name in os.listdir('/proc'):
        if name.isdigit():
            try:
                if os.readlink('/proc/'+name+'/exe') == APP:
                    result.append(int(name))
            except OSError:
                pass
    return sorted(result)


def screen_parent(pid):
    for unused in range(8):
        exe = os.readlink('/proc/%d/exe' % pid)
        if os.path.basename(exe).startswith('screen'):
            return pid
        fields = read('/proc/%d/stat' % pid).rsplit(b')', 1)[1].split()
        pid = int(fields[1])
        if pid <= 1:
            break
    raise RuntimeError('Application has no known screen supervisor')


def stop():
    running = apps()
    if not running:
        return
    if len(running) != 1:
        raise RuntimeError('Expected exactly one EVACom process: '+str(running))
    parent = screen_parent(running[0])
    subprocess.check_call(['screen', '-S', str(parent), '-X', 'quit'])
    for unused in range(50):
        if not apps():
            return
        time.sleep(.2)
    raise RuntimeError('EVACom did not stop; no files changed')


def start(expected_hash):
    if apps():
        raise RuntimeError('Refusing duplicate application start')
    subprocess.check_call(['screen', '-dmS', 'qc45-currentcap', '/home/EVAStart'])
    previous = None
    stable = 0
    for unused in range(30):
        time.sleep(1)
        running = apps()
        if len(running) == 1:
            pid = running[0]
            maps = read('/proc/%d/maps' % pid)
            if FIX.encode('ascii') in maps and sha(read(FIX)) == expected_hash:
                stable = stable+1 if previous == pid else 1
                previous = pid
                if stable >= 5:
                    return pid
        else:
            stable = 0
    raise RuntimeError('No stable application with expected preload')


def manifest_read(directory):
    value = json.loads(read(os.path.join(directory, 'manifest.json')).decode('ascii'))
    assert [entry['path'] for entry in value['files']] == [FIX, HELPER]+list(STARTS)+[SOURCE]
    for entry in value['files']:
        if entry['existed']:
            data = read(os.path.join(directory, entry['backup']))
            assert sha(data) == entry['sha256'], 'Corrupt backup'
    return value


def restore_files(directory):
    manifest = manifest_read(directory)
    for entry in manifest['files']:
        if entry['existed']:
            write(entry['path'], read(os.path.join(directory, entry['backup'])), entry['mode'])
        elif os.path.exists(entry['path']):
            os.unlink(entry['path'])


def install(args):
    assert sha(read(BASE)) == BASE_HASH, 'Unknown base firmware'
    assert sha(read(FIX)) == OLD_HASH, 'Expected the known previous shim'
    data = read(args.candidate)
    assert sha(data) == args.sha256, 'Candidate checksum mismatch'
    stage = os.path.dirname(os.path.abspath(args.candidate))
    env = dict(os.environ, LD_LIBRARY_PATH='/home/evacom', LD_PRELOAD=os.path.abspath(args.candidate))
    subprocess.check_call(['python', os.path.join(stage, 'probe_abi.py'), args.candidate], env=env)
    old = imp.load_source('old_persist', HELPER)
    old.validate_scripts()
    assert old.FIX_HASH == OLD_HASH
    if len(apps()) != 1:
        raise RuntimeError('Unexpected application count')
    text = read(HELPER).decode('ascii')
    replacements = [("FIX_HASH = '"+OLD_HASH+"'", "FIX_HASH = '"+args.sha256+"'")]
    if args.from_v2 or args.from_v3 or args.from_v4 or args.from_v5:
        previous_version = '5' if args.from_v5 else ('4' if args.from_v4 else ('3' if args.from_v3 else '2'))
        previous_source = V5_SOURCE if args.from_v5 else (V4_SOURCE if args.from_v4 else (V3_SOURCE if args.from_v3 else V2_SOURCE))
        replacements += [
            ("SOURCE = '"+previous_source+"'", "SOURCE = '"+SOURCE+"'"),
            ('QC45 power/current limit v'+previous_version+';', 'QC45 power/current limit v6;'),
            ('Persist the QC45 power/current limit v'+previous_version+'.', 'Persist the QC45 power/current limit v6.')]
    else:
        replacements += [
        ("SOURCE = '/home/qc45-power-test/libqc45_powerlimit_test.so'", "SOURCE = '"+SOURCE+"'"),
        ('QC45 power-limit fallback v1;', 'QC45 power/current limit v6;'),
        ('QC45: power fallback inactive; software or shim checksum mismatch.',
         'QC45: controller held stopped; power/current fix checksum mismatch.'),
        ('env -u LD_PRELOAD ./EVACom_Evse_Gui -qws', 'sleep 5'),
        ('Persist the hardware-tested QC45 power fallback.', 'Persist the QC45 power/current limit v6.')]
    for before, after in replacements:
        assert text.count(before) == 1, 'Unexpected persistence helper'
        text = text.replace(before, after)
    helper_candidate = os.path.join(stage, 'persist-v6.py')
    write(helper_candidate, text.encode('ascii'), 0o700)
    new = imp.load_source('new_persist', helper_candidate)
    wrapper = new.wrapper()
    wrapper_candidate = os.path.join(stage, 'EVAStart-v6')
    write(wrapper_candidate, wrapper, 0o700)
    subprocess.check_call(['/bin/bash', '-n', wrapper_candidate])
    os.mkdir(args.backup, 0o700)
    manifest = {'previous_shim_sha256': OLD_HASH, 'new_shim_sha256': args.sha256, 'files': []}
    for i, path in enumerate([FIX, HELPER]+list(STARTS)+[SOURCE]):
        assert not os.path.islink(path), 'Unexpected symlink'
        entry = {'path': path, 'existed': os.path.exists(path), 'backup': str(i)+'.bin'}
        if entry['existed']:
            original = read(path)
            entry.update(mode=stat.S_IMODE(os.stat(path).st_mode), sha256=sha(original))
            write(os.path.join(args.backup, entry['backup']), original, 0o600)
        manifest['files'].append(entry)
    write(os.path.join(args.backup, 'manifest.json'), json.dumps(manifest, indent=2).encode('ascii'), 0o600)
    manifest_read(args.backup)
    stop()
    try:
        write(SOURCE, data, 0o644)
        write(FIX, data, 0o644)
        write(HELPER, text.encode('ascii'), 0o755)
        for path in STARTS:
            write(path, wrapper, 0o755)
        pid = start(args.sha256)
        assert new.status(), 'Persistence validation failed'
    except BaseException:
        # A failed startup may leave a retrying screen even with no app PID.
        # Remove that supervisor before restoring files and starting the old one.
        subprocess.call(['screen', '-S', 'qc45-currentcap', '-X', 'quit'])
        for unused in range(50):
            if not apps():
                break
            time.sleep(.2)
        if apps():
            stop()
        restore_files(args.backup)
        restored = start(OLD_HASH)
        print(json.dumps({'installation': 'rolled_back', 'restored_pid': restored, 'backup': args.backup}))
        raise
    result = {'installation': 'active', 'pid': pid, 'sha256': args.sha256, 'backup': args.backup,
              'single_application': True, 'loaded': True, 'persistent': True}
    write(os.path.join(args.backup, 'installed.json'), json.dumps(result, indent=2).encode('ascii'), 0o600)
    print(json.dumps(result, sort_keys=True))


def main():
    global OLD_HASH, SOURCE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate')
    parser.add_argument('--sha256')
    parser.add_argument('--backup', required=True)
    parser.add_argument('--rollback', action='store_true')
    parser.add_argument('--from-v2', action='store_true', help='Upgrade the known current-limit v2 shim')
    parser.add_argument('--from-v3', action='store_true', help='Upgrade the known current-limit v3 shim')
    parser.add_argument('--from-v4', action='store_true', help='Upgrade the known current-limit v4 shim')
    parser.add_argument('--from-v5', action='store_true', help='Upgrade the known current-limit v5 shim')
    parser.add_argument('--vehicle-unplugged', action='store_true', required=True)
    args = parser.parse_args()
    assert sum((args.from_v2, args.from_v3, args.from_v4, args.from_v5)) <= 1, 'Specify only one previous version'
    if args.from_v2 or args.from_v3 or args.from_v4 or args.from_v5:
        OLD_HASH = V5_HASH if args.from_v5 else (V4_HASH if args.from_v4 else (V3_HASH if args.from_v3 else V2_HASH))
    assert os.geteuid() == 0 and os.uname()[4] in ('armv5tejl', 'armv5tel')
    if args.rollback:
        # Accept either known source-copy path in earlier validated manifests.
        raw = json.loads(read(os.path.join(args.backup, 'manifest.json')).decode('ascii'))
        previous_source = raw['files'][-1]['path']
        assert previous_source in (SOURCE, V5_SOURCE, V4_SOURCE, V3_SOURCE, V2_SOURCE), 'Unknown backup source path'
        SOURCE = previous_source
        manifest = manifest_read(args.backup)
        stop()
        restore_files(args.backup)
        print(json.dumps({'rollback_pid': start(manifest['previous_shim_sha256']), 'backup': args.backup}))
    else:
        assert args.candidate and args.sha256
        install(args)


if __name__ == '__main__':
    main()
