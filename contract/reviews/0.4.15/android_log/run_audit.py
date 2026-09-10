from pathlib import Path
import os,subprocess,json,hashlib,time,re
repo=Path('/Users/eurobuddha/Projects/minima/apks/pandadex');out=Path('/private/tmp/pandadex-android-audit-build-50')
root=Path(Path('/private/tmp/pandadex-review-emulator-48-location').read_text().strip());assert str(root).startswith('/private/tmp/pandadex-review-emulator-')
ps=subprocess.check_output(['ps','-axo','pid=,command='],text=True);assert any('qemu' in line and '-avd PandaDexAudit48 ' in line and str(root/'avd') in line for line in ps.splitlines())
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
system.update(adb_server_port=5049,emulator_name='PandaDexAudit48',user_device_accessed=False,user_node_accessed=False)
(out/'android-system.json').write_text(json.dumps(system,indent=2)+'\n')
print(run('shell','settings','put','secure','show_ime_with_hard_keyboard','1'),flush=True)
if run('shell','pm','path','com.eurobuddha.pandadex.audit',check=False):
 assert run('shell','pm','clear','com.eurobuddha.pandadex.audit')=='Success'
print(run('install','-r',str(out/'pandadex-export-audit-50.apk')),flush=True)
component='com.eurobuddha.pandadex.audit/com.eurobuddha.pandadex.OwnerReceiptAndroidAudit'
result=run('shell','am','instrument','-w','-e','phase','app',component)
(out/'app.txt').write_text(result+'\n');print(result,flush=True)
assert 'FAIL' not in result and 'PASS ' in result,result
matches()
(out/'assertions.json').write_text(json.dumps({'app':int(re.search(r'PASS (\d+) assertions',result).group(1))},indent=2)+'\n')
for name in ['app-keyboard-portrait.png','app-keyboard-landscape.png','app-progress-log.png']:
 data=subprocess.check_output(adb+['exec-out','run-as','com.eurobuddha.pandadex.audit','cat','files/'+name],env=env,timeout=55)
 assert data.startswith(b'\x89PNG'),name
 (out/name).write_bytes(data)
