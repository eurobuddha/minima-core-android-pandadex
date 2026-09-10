#!/usr/bin/env python3
"""Build a separate Android database harness; never builds the production package.

Use a new output directory and increment --build-number for every new APK.
Installing/running is deliberately separate and requires an explicitly chosen disposable emulator.
"""
from pathlib import Path
import argparse, hashlib, json, shutil, subprocess

parser=argparse.ArgumentParser()
parser.add_argument('--output',required=True,type=Path)
parser.add_argument('--build-number',required=True,type=int)
args=parser.parse_args()
assert 0 < args.build_number < 2100000000
here=Path(__file__).resolve().parent
repo=here.parents[3]
assert (repo/'app/src/main/java/com/eurobuddha/pandadex/DexDb.java').is_file()
out=args.output.resolve()
assert not out.is_relative_to(repo), 'Keep audit build output outside the production repository'
out.mkdir(parents=True,exist_ok=False)
project=out/'harness';project.mkdir()
for name in ['settings.gradle','build.gradle','gradle.properties','local.properties','gradlew']:
    shutil.copy2(repo/name,project/name)
shutil.copytree(repo/'gradle',project/'gradle')
runner=project/'app/src/main/java/com/eurobuddha/pandadex';runner.mkdir(parents=True)
shutil.copy2(here/'DatabaseAudit.java',runner/'DatabaseAudit.java')
shutil.copy2(here/'ExportAndroidAudit.java',runner/'ExportAndroidAudit.java')
shutil.copytree(repo/'app/libs',project/'app/libs')
source=(repo/'app/build.gradle').read_text()
import re
source=re.sub(r'applicationId "[^"]+"','applicationId "com.eurobuddha.pandadex.audit"',source,count=1)
source=re.sub(r'versionCode \d+',f'versionCode {args.build_number}',source,count=1)
source=re.sub(r'versionName "[^"]+"',f'versionName "export-audit-{args.build_number}"',source,count=1)
start=source.index('    signingConfigs {');end=source.index('    compileOptions {',start)
source=source[:start]+'''    sourceSets {
        main {
            java.srcDirs += ['''+repr(str(repo/'app/src/main/java'))+''']
            res.srcDirs = ['''+repr(str(repo/'app/src/main/res'))+''']
        }
    }
'''+source[end:]
(project/'app/build.gradle').write_text(source)
(project/'app/src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="PandaDEX Export Audit" android:allowBackup="false">
<provider android:name="com.eurobuddha.pandadex.ExportAndroidAudit$SaveProvider" android:authorities="com.eurobuddha.pandadex.audit.exports" android:exported="false" />
</application>
<instrumentation android:name="com.eurobuddha.pandadex.ExportAndroidAudit" android:targetPackage="com.eurobuddha.pandadex.audit" android:functionalTest="true" />
<instrumentation android:name="com.eurobuddha.pandadex.DatabaseAudit" android:targetPackage="com.eurobuddha.pandadex.audit" android:functionalTest="true" />
</manifest>''')
def hashes():
    return {str(p.relative_to(repo)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((repo/'app/src/main/java').rglob('*.java'))}
before=hashes()
with (out/'build.log').open('w') as log:
    subprocess.run(['./gradlew','--offline','--no-daemon',':app:assembleDebug'],cwd=project,stdout=log,stderr=subprocess.STDOUT,check=True)
assert before==hashes(), 'Production source changed during compilation; do not use this evidence'
manifest=(project/'app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml').read_text()
assert 'android.permission.INTERNET' not in manifest
assert 'com.eurobuddha.pandadex.MainActivity' not in manifest
assert 'NodeTransportService' not in manifest
apk=out/f'pandadex-export-audit-{args.build_number}.apk'
shutil.copy2(project/'app/build/outputs/apk/debug/app-debug.apk',apk)
(out/'build-evidence.json').write_text(json.dumps({
    'purpose':'Isolated SQLite/WAL snapshot and document export tests; no node or stock-device approval',
    'application_id':'com.eurobuddha.pandadex.audit','audit_build':args.build_number,
    'apk_sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),
    'runner_sha256':hashlib.sha256((runner/'DatabaseAudit.java').read_bytes()).hexdigest(),
    'export_runner_sha256':hashlib.sha256((runner/'ExportAndroidAudit.java').read_bytes()).hexdigest(),
    'production_java_sha256':before,'network_permission':False
},indent=2)+'\n')
print(apk)
