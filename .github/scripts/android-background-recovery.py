"""Emulator-only process death + real system media-key recovery; no activity launched on recovery."""
from pathlib import Path
import json,os,re,subprocess,time

package='com.longzhousz.ami'
out=Path('device-screenshots/recovery');out.mkdir(parents=True,exist_ok=True)
adb=['adb']+(['-s',os.environ['ANDROID_SERIAL']] if os.environ.get('ANDROID_SERIAL') else [])
def shell(*args,check=True):
    p=subprocess.run(adb+['shell',*args],text=True,capture_output=True,timeout=60)
    if check and p.returncode:
        raise RuntimeError(p.stderr or p.stdout)
    return p.stdout

def session(text):
    blocks=re.split(r'(?m)^\s*package=',text)
    for b in blocks[1:]:
        if b.splitlines()[0].strip()!=package: continue
        state=re.search(r'state=PlaybackState\s*\{state=(?:[A-Z_]+\()?([0-9]+)',b)
        if state and 'AMI recovery fixture' in b:
            return int(state.group(1))
    return None

def wait_state(expected):
    deadline=time.monotonic()+40
    latest=''
    while time.monotonic()<deadline:
        latest=shell('dumpsys','media_session')
        if session(latest)==expected:
            return latest
        time.sleep(.5)
    (out/'failed-sessions.txt').write_text(latest)
    raise AssertionError(f'Recovery media state did not reach {expected}')

try:
    assert shell('getprop','ro.kernel.qemu').strip()=='1','This fixture only runs on an emulator'
    seed=shell('am','instrument','-w','-r','-e','phase','seed',package+'.test/com.longzhousz.ami.RecoverySeedInstrumentation')
    (out/'seed.txt').write_text(seed)
    assert 'AMI_RECOVERY_OK phase=seed' in seed,seed
    pids=shell('pidof',package,check=False).strip().split()
    for pid in pids:
        assert pid.isdigit()
        # SIGKILL simulates reclamation without setting Android's force-stopped package flag.
        shell('run-as',package,'kill','-9',pid,check=False)
    deadline=time.monotonic()+10
    while shell('pidof',package,check=False).strip() and time.monotonic()<deadline: time.sleep(.2)
    assert not shell('pidof',package,check=False).strip(),'Previous process is still alive'
    shell('cmd','media_session','dispatch','play')
    playing=wait_state(3);(out/'playing-sessions.txt').write_text(playing)
    services=shell('dumpsys','activity','services',package);(out/'services.txt').write_text(services)
    assert 'isForeground=true' in services,'Playback service is not foreground'
    activities=shell('dumpsys','activity','activities');(out/'activities.txt').write_text(activities)
    assert not any(package+'/com.longzhousz.ami.MainActivity' in l for l in activities.splitlines() if 'ResumedActivity' in l),'Main screen was launched'
    time.sleep(2)
    shell('cmd','media_session','dispatch','pause')
    (out/'paused-sessions.txt').write_text(wait_state(2))
    (out/'result.json').write_text(json.dumps({'processReclaimed':True,'systemPlayResumed':True,'systemPauseWorked':True,'foregroundService':True,'mainScreenLaunched':False},indent=2))
finally:
    (out/'logcat.txt').write_text(shell('logcat','-d',check=False))
    cleanup=shell('am','instrument','-w','-r','-e','phase','cleanup',package+'.test/com.longzhousz.ami.RecoverySeedInstrumentation',check=False)
    (out/'cleanup.txt').write_text(cleanup)
