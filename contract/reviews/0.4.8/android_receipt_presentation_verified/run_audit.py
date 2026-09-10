from pathlib import Path
import os,subprocess,json,hashlib,time,re
repo=Path('/Users/eurobuddha/Projects/minima/apks/pandadex');out=Path('/private/tmp/pandadex-android-audit-build-34')
root=Path(Path('/private/tmp/pandadex-review-emulator-34-location').read_text().strip());assert str(root).startswith('/private/tmp/pandadex-review-emulator-')
ps=subprocess.check_output(['ps','-axo','pid=,command='],text=True);assert any('qemu' in line and '-avd PandaDexAudit34 ' in line and str(root/'avd') in line for line in ps.splitlines())
env=os.environ.copy();env.update(ANDROID_ADB_SERVER_PORT='5049',ADB_VENDOR_KEYS=str(root/'emulator/adbkey'),ANDROID_USER_HOME=str(root/'emulator'))
adb=['/Users/eurobuddha/Library/Android/sdk/platform-tools/adb','-P','5049','-s','emulator-5680']
def run(*args,timeout=55,check=True):
 r=subprocess.run(adb+list(args),env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout)
 if check and r.returncode:raise RuntimeError(r.stdout)
 return r.stdout.strip()
assert run('shell','getprop','sys.boot_completed')=='1';assert run('shell','getprop','ro.boot.qemu')=='1'
build=json.loads((out/'build-evidence.json').read_text())
def matches():
 for p,h in build['production_java_sha256'].items():assert hashlib.sha256((repo/p).read_bytes()).hexdigest()==h,p
matches();system={k:run('shell','getprop',k) for k in ['ro.build.version.release','ro.build.version.sdk','ro.product.cpu.abi','ro.build.fingerprint','ro.boot.qemu']}
system.update(adb_server_port=5049,emulator_name='PandaDexAudit34',user_device_accessed=False,user_node_accessed=False)
(out/'android-system.json').write_text(json.dumps(system,indent=2)+'\n')
print(run('install',str(out/'pandadex-export-audit-34.apk')),flush=True)
component='com.eurobuddha.pandadex.audit/com.eurobuddha.pandadex.OwnerReceiptAndroidAudit'
counts={}
for phase in ['normal','crash','recover']:
 result=run('shell','am','instrument','-w','-e','phase',phase,component)
 (out/(phase+'.txt')).write_text(result+'\n');print(result,flush=True)
 if phase=='crash':
  assert 'Process crashed' in result,result
  assert not run('shell','pidof','com.eurobuddha.pandadex.audit',check=False),'Crashed audit process still running'
  raw=run('shell','run-as','com.eurobuddha.pandadex.audit','cat','shared_prefs/owner_audit_checkpoint.xml')
  (out/'crash-checkpoint.xml').write_text(raw+'\n')
  import xml.etree.ElementTree as ET
  e=ET.fromstring(raw);values={x.get('name'):x.get('value') for x in e}
  assert values.get('ready')=='true' and values.get('build')=='34',values
  counts['before_crash']=int(values['checks'])+1
 else:
  assert 'FAIL' not in result and 'PASS ' in result,result
  counts[phase]=int(re.search(r'PASS (\d+) assertions',result).group(1))
matches();(out/'assertions.json').write_text(json.dumps(counts,indent=2)+'\n');print('Android assertions:',counts,flush=True)

for name in ['owner-history-dark.png','owner-history-light.png','owner-history-empty.png','owner-history-error.png','owner-reconciliation.zip']:
 data=subprocess.check_output(adb+['exec-out','run-as','com.eurobuddha.pandadex.audit','cat','files/'+name],env=env,timeout=55)
 assert data.startswith(b'\x89PNG' if name.endswith('.png') else b'PK'),name
 (out/name).write_bytes(data)
print('Saved actual Android view screenshots and owner reconciliation ZIP',flush=True)

# Existing evidence is pulled before resetting this disposable audit package for cold launch.
assert not run('shell','pm','path','org.minimarex.minimacore',check=False),'MinimaCore must be absent'
assert run('shell','pm','clear','com.eurobuddha.pandadex.audit')=='Success'
result=run('shell','am','instrument','-w','-e','phase','app',component)
(out/'app.txt').write_text(result+'\n');print(result,flush=True)
assert 'FAIL' not in result and 'PASS ' in result,result
counts['app']=int(re.search(r'PASS (\d+) assertions',result).group(1))
matches();(out/'assertions.json').write_text(json.dumps(counts,indent=2)+'\n')
for label in ['trade','chart','trades','orders','assets','maker','recreated']:
 name='app-offline-'+label+'.png'
 data=subprocess.check_output(adb+['exec-out','run-as','com.eurobuddha.pandadex.audit','cat','files/'+name],env=env,timeout=55)
 assert data.startswith(b'\x89PNG'),name
 (out/name).write_bytes(data)
print('Full offline Activity evidence saved:',counts,flush=True)

for name in ['app-balance-zero.png','app-saved-orders.png','app-failed-cancel.png','app-missing-receipts.png','app-trade-receipts.png','app-unresolved-action.png','app-receipt-error.png']:
 data=subprocess.check_output(adb+['exec-out','run-as','com.eurobuddha.pandadex.audit','cat','files/'+name],env=env,timeout=55)
 assert data.startswith(b'\x89PNG'),name
 (out/name).write_bytes(data)
