from pathlib import Path
import subprocess,os,signal,time,shutil,json
root=Path(Path('/private/tmp/pandadex-review-emulator-36-location').read_text().strip())
assert str(root).startswith('/private/tmp/pandadex-review-emulator-') and root.exists()
def pids():
 lines=subprocess.check_output(['ps','-axo','pid=,command='],text=True).splitlines()
 return [int(x.strip().split(None,1)[0]) for x in lines if 'qemu' in x and '-avd PandaDexAudit36 ' in x and str(root/'avd') in x]
owned=pids();assert len(owned)==1,owned
os.kill(owned[0],signal.SIGTERM)
for i in range(50):
 if not pids():break
 time.sleep(.2)
assert not pids(),'Audit emulator still running; keep userdata'
env=os.environ.copy();env.update(ANDROID_ADB_SERVER_PORT='5049',ADB_VENDOR_KEYS=str(root/'emulator/adbkey'),ANDROID_USER_HOME=str(root/'emulator'))
subprocess.run(['/Users/eurobuddha/Library/Android/sdk/platform-tools/adb','-P','5049','kill-server'],env=env,check=True)
for n in ['avd','emulator']:shutil.rmtree(root/n)
Path('/private/tmp/pandadex-android-audit-build-36/cleanup.json').write_text(json.dumps({'emulator_stopped':True,'isolated_adb_server_stopped':True,'temporary_userdata_and_keys_removed':True,'audit_apks_retained':True,'production_apks_unchanged':True},indent=2)+'\n')
print('Stopped only PandaDexAudit36 and isolated ADB5049; removed its userdata/keys; retained APK and logs')
