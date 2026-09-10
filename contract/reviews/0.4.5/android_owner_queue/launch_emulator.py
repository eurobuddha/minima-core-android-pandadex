from pathlib import Path
import os,tempfile,socket,subprocess,argparse
parser=argparse.ArgumentParser();parser.add_argument('--audit',required=True,type=int);args=parser.parse_args()
assert args.audit>8
sdk=Path('/Users/eurobuddha/Library/Android/sdk');name='PandaDexAudit'+str(args.audit)
for port in [5049,5680,5681]:
 with socket.socket() as sock:sock.bind(('127.0.0.1',port))
root=Path(tempfile.mkdtemp(prefix='pandadex-review-emulator-',dir='/private/tmp'));avd=root/('avd/'+name+'.avd');avd.mkdir(parents=True);settings=root/'emulator';settings.mkdir()
(root/('avd/'+name+'.ini')).write_text('avd.ini.encoding=UTF-8\npath='+str(avd)+'\ntarget=android-36.1\n')
(avd/'config.ini').write_text('AvdId='+name+'\navd.ini.displayname='+name+'''\nabi.type=arm64-v8a
hw.cpu.arch=arm64
hw.cpu.ncore=4
hw.ramSize=2048
hw.lcd.width=1080
hw.lcd.height=1920
hw.lcd.density=420
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.sdCard=no
disk.dataPartition.size=2G
image.sysdir.1=system-images/android-36.1/google_apis_playstore/arm64-v8a/
tag.id=google_apis_playstore
tag.display=Google Play
PlayStore.enabled=true
''')
env=os.environ.copy();env.update(ANDROID_ADB_SERVER_PORT='5049',ADB_VENDOR_KEYS=str(settings/'adbkey'),ANDROID_AVD_HOME=str(root/'avd'),ANDROID_USER_HOME=str(settings),ANDROID_EMULATOR_HOME=str(settings),ANDROID_SDK_ROOT=str(sdk))
subprocess.run([str(sdk/'platform-tools/adb'),'keygen',str(settings/'adbkey')],env=env,check=True,stdout=subprocess.DEVNULL)
subprocess.run([str(sdk/'platform-tools/adb'),'-P','5049','start-server'],env=env,check=True)
Path('/private/tmp/pandadex-review-emulator-'+str(args.audit)+'-location').write_text(str(root))
print('Disposable audit emulator:',root,flush=True)
log=os.open(str(root/'emulator.log'),os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600);os.dup2(log,1);os.dup2(log,2)
command=[str(sdk/'emulator/emulator'),'-avd',name,'-port','5680','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-gpu','swiftshader_indirect','-datadir',str(avd)]
os.execve(command[0],command,env)
