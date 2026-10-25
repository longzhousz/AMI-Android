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
    # AOSP userdebug emulator only: run-as cannot SIGKILL untrusted_app under SELinux.
    subprocess.run(adb+['root'],check=True,text=True,capture_output=True,timeout=30)
    subprocess.run(adb+['wait-for-device'],check=True,timeout=30)
    assert shell('id','-u').strip()=='0','Emulator root is required for the reclamation fixture'
    shell('logcat','-c')
    seed=shell('am','instrument','-w','-r','-e','phase','seed',package+'.test/com.longzhousz.ami.RecoverySeedInstrumentation')
    (out/'seed.txt').write_text(seed)
    assert 'AMI_RECOVERY_OK phase=seed' in seed,seed
    # Finishing instrumentation may itself end the target process. Establish a live,
    # uninstrumented background session before simulating system reclamation.
    deadline=time.monotonic()+10
    while shell('pidof',package,check=False).strip() and time.monotonic()<deadline: time.sleep(.2)
    assert not shell('pidof',package,check=False).strip(),'Seed process did not finish'
    # Inject hardware media keys through the system input path. Android 11's `cmd
    # media_session dispatch` supplies an empty caller package to legacy sessions.
    shell('input','keyevent','KEYCODE_MEDIA_PLAY')
    (out/'before-reclaim-playing.txt').write_text(wait_state(3))
    shell('input','keyevent','KEYCODE_MEDIA_PAUSE')
    (out/'before-reclaim-paused.txt').write_text(wait_state(2))
    pids=shell('pidof',package,check=False).strip().split()
    assert pids,'No live background process to reclaim'
    for pid in pids:
        assert pid.isdigit()
        # SIGKILL simulates reclamation without setting Android's force-stopped package flag.
        shell('kill','-9',pid)
    deadline=time.monotonic()+10
    remaining=set(pids)
    while remaining and time.monotonic()<deadline:
        remaining=set(pids)&set(shell('pidof',package,check=False).strip().split())
        if remaining:time.sleep(.2)
    assert not remaining,'Previous process is still alive'
    automatically_restarted=bool(shell('pidof',package,check=False).strip())
    shell('input','keyevent','KEYCODE_MEDIA_PLAY')
    playing=wait_state(3);(out/'playing-sessions.txt').write_text(playing)
    services=shell('dumpsys','activity','services',package);(out/'services.txt').write_text(services)
    assert 'isForeground=true' in services,'Playback service is not foreground'
    activities=shell('dumpsys','activity','activities');(out/'activities.txt').write_text(activities)
    assert not any(package+'/com.longzhousz.ami.MainActivity' in l for l in activities.splitlines() if 'ResumedActivity' in l),'Main screen was launched'
    time.sleep(2)
    shell('input','keyevent','KEYCODE_MEDIA_PAUSE')
    (out/'paused-sessions.txt').write_text(wait_state(2))
    # A focused paused-background sample, with no Activity or instrumentation process.
    time.sleep(1)
    idle_pid=shell('pidof',package).strip()
    assert idle_pid.isdigit()
    def cpu_ticks():
        fields=shell('cat',f'/proc/{idle_pid}/stat').rsplit(')',1)[1].split()
        return int(fields[11])+int(fields[12])
    before=cpu_ticks();idle_started=time.monotonic()
    (out/'paused-memory-before.txt').write_text(shell('dumpsys','meminfo',package))
    time.sleep(8)
    after=cpu_ticks()
    (out/'paused-memory-after.txt').write_text(shell('dumpsys','meminfo',package))
    (out/'paused-idle.json').write_text(json.dumps({'pid':idle_pid,'elapsedSeconds':time.monotonic()-idle_started,'cpuTicksDelta':after-before,'caveat':'One emulator diagnostic; not a phone battery or leak benchmark'},indent=2))
    assert session(shell('dumpsys','media_session'))==2,'Background pause did not remain stable'
    crashes=shell('logcat','-b','crash','-d');(out/'crash.txt').write_text(crashes)
    assert f'Process: {package},' not in crashes,'App crashed during system media-key recovery'
    (out/'result.json').write_text(json.dumps({'processReclaimed':True,'coldSystemPlayResumed':True,'systemPlayResumed':True,'systemPauseWorked':True,'foregroundService':True,'mainScreenLaunched':False,'automaticServiceRestartBeforePlay':automatically_restarted,'previousPids':pids,'recoveredPids':shell('pidof',package,check=False).strip().split()},indent=2))
finally:
    (out/'logcat.txt').write_text(shell('logcat','-d',check=False))
    cleanup=shell('am','instrument','-w','-r','-e','phase','cleanup',package+'.test/com.longzhousz.ami.RecoverySeedInstrumentation',check=False)
    (out/'cleanup.txt').write_text(cleanup)
