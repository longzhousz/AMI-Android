from pathlib import Path
import xml.etree.ElementTree as E
reports=list(Path('app/build/outputs/androidTest-results/connected/debug').glob('TEST-*.xml'))
assert len(reports)==1,reports
r=E.parse(reports[0]).getroot()
assert int(r.get('tests',0))==1 and all(int(r.get(k,0))==0 for k in ('failures','errors','skipped')),r.attrib
assert [(t.get('classname'),t.get('name')) for t in r.iter('testcase')]==[('com.longzhousz.ami.InterfaceTest','titleOnlySettingsKeepBackupInformationAndSubmenuBackNavigation')]
print('Verified the focused native Settings navigation and information-retention case.')
