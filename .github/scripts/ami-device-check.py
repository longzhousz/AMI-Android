#!/usr/bin/env python3
"""Collect a focused Android/vivo playback diagnostic without changing app settings or files."""
import argparse,datetime,json,re,subprocess,time
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True,help='adb target serial')
parser.add_argument('--package',default='com.longzhousz.ami.preview')
parser.add_argument('--duration',type=int,default=0,help='Observation seconds; 1800 for a 30-minute screen-off run')
parser.add_argument('--interval',type=int,default=60)
parser.add_argument('--scenario',choices=['snapshot','screen-off','bluetooth','call','recovery'],default='snapshot')
parser.add_argument('--output',type=Path,default=Path('artifacts/device-check'))
args=parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_.]+',args.package) or args.duration<0 or args.interval<1:
    parser.error('Invalid package, duration or interval')
args.output.mkdir(parents=True,exist_ok=True)
adb=['adb','-s',args.serial]
def read(*command):
    try:
        p=subprocess.run(adb+['shell',*command],capture_output=True,text=True,timeout=30)
        text=p.stdout+p.stderr
    except subprocess.TimeoutExpired:
        text='Command timed out: '+command[0]
    return re.sub(r'(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b','[device-address]',text)

def property(name):return read('getprop',name).strip()
props={name:property(name) for name in ['ro.product.manufacturer','ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.display.id','ro.vivo.os.name','ro.vivo.os.version','ro.kernel.qemu']}
if 'package:' not in read('pm','path',args.package):
    raise SystemExit('Package is not installed: '+args.package)
manifest={'package':args.package,'scenario':args.scenario,'properties':props,'isVivo':'vivo' in props['ro.product.manufacturer'].lower(),'isEmulator':props['ro.kernel.qemu']=='1','requestedDurationSeconds':args.duration,'startedUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'verdict':'evidence_only; physical actions and feature results require observation'}
(args.output/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2))
start=time.monotonic();snapshots=[]
while True:
    elapsed=time.monotonic()-start
    folder=args.output/f'{len(snapshots):03d}-{int(elapsed):05d}s';folder.mkdir()
    commands={'sessions':['dumpsys','media_session'],'audio':['dumpsys','audio'],'services':['dumpsys','activity','services',args.package],
        'memory':['dumpsys','meminfo',args.package],'power':['dumpsys','power'],'battery':['dumpsys','batterystats','--charged',args.package],
        'windows':['dumpsys','window','windows'],'package':['dumpsys','package',args.package],'frames':['dumpsys','gfxinfo',args.package,'framestats']}
    evidence={}
    for name,command in commands.items():
        evidence[name]=read(*command);(folder/(name+'.txt')).write_text(evidence[name])
    pid=read('pidof',args.package).strip()
    if re.fullmatch(r'\d+',pid): (folder/'logcat.txt').write_text(read('logcat','-d','-t','1000','--pid='+pid))
    snapshots.append({'elapsedSeconds':round(elapsed,1),'pid':pid,'appMusicEntryDeclared':'android.intent.category.APP_MUSIC' in evidence['package'],
        'browserServiceDeclared':'android.media.browse.MediaBrowserService' in evidence['package'],
        'frameSampleUsable':'Application Not Responding' not in evidence['windows'] and args.package in evidence['windows']})
    (args.output/'snapshots.json').write_text(json.dumps(snapshots,ensure_ascii=False,indent=2))
    print(f'Collected {args.scenario} at {int(elapsed)}s: {folder}',flush=True)
    if time.monotonic()-start>=args.duration:break
    time.sleep(min(args.interval,max(0,args.duration-(time.monotonic()-start))))
