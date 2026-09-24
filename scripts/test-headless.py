#!/usr/bin/env python3
"""Build/run independently linked Core/Search/Route probes. No map views, Compose or API key."""
from pathlib import Path
import argparse,json,os,subprocess,time,zipfile
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--simulator',required=True);p.add_argument('--adb',default='adb');args=p.parse_args()
sdk=Path(os.environ['GLMAP_SDK_DIR']).resolve();out=root/'build/headless';out.mkdir(parents=True,exist_ok=True)
results={}
def run(cmd,cwd=root,log=None):
 if log:
  with log.open('w') as f:subprocess.run(cmd,cwd=cwd,check=True,stdout=f,stderr=subprocess.STDOUT)
 else:subprocess.run(cmd,cwd=cwd,check=True,stdout=subprocess.DEVNULL)
for name in ('core','search','route'):
 directory=out/name;directory.mkdir(exist_ok=True)
 run(['./gradlew',f'-Pprobe={name}',':headless-app:assembleRelease',':headless-probe:linkReleaseFrameworkIosSimulatorArm64'],log=directory/'gradle.log')
 apk=root/'headless-app/build/outputs/apk/release/headless-app-release.apk'
 with zipfile.ZipFile(apk) as z:
  libs=sorted(n for n in z.namelist() if n.startswith('lib/arm64-v8a/libgl') and n.endswith('.so'))
  assert 'lib/arm64-v8a/libglmap.so' not in libs
  assert ('lib/arm64-v8a/libglsearch.so' in libs)==(name=='search')
  assert ('lib/arm64-v8a/libglroute.so' in libs)==(name=='route')
 app='software.globus.modules.kmp'+name
 run([args.adb,'install','-r',str(apk)])
 run([args.adb,'shell','am','force-stop',app])
 resultfile=f'/sdcard/Android/data/{app}/files/result.txt'
 run([args.adb,'shell','rm','-f',resultfile])
 run([args.adb,'shell','am','start','-n',app+'/globus.tests.headless.app.MainActivity'])
 value=''
 for _ in range(20):
  time.sleep(2)
  proc=subprocess.run([args.adb,'shell','cat',resultfile],capture_output=True,text=True)
  if proc.returncode==0:value=proc.stdout.strip();break
 assert value=='PASS '+name,(name,value)
 print('Android',value,libs,flush=True)
 products=['GLMapCore']+({'search':['GLSearch'],'route':['GLRoute']}.get(name,[]))
 spec=f'''name: HeadlessProbe
options:
  deploymentTarget:
    iOS: '16.4'
packages:
  GLMap:
    path: {sdk}/ios
targets:
  HeadlessProbe:
    type: application
    platform: iOS
    sources:
      - {root}/headless-probe/iosApp/App.swift
'''+('      - '+str(root/'example/assets/Montenegro.vm')+'\n' if name=='search' else '')+'''    dependencies:
      - framework: '''+str(root/'headless-probe/build/bin/iosSimulatorArm64/releaseFramework/GlobusHeadlessProbe.framework')+'''
        embed: false
'''+''.join(f'      - package: GLMap\n        product: {product}\n' for product in products)+f'''    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: {app}
        GENERATE_INFOPLIST_FILE: YES
        INFOPLIST_KEY_UILaunchScreen_Generation: YES
        INFOPLIST_KEY_UIApplicationSceneManifest_Generation: YES
        SWIFT_VERSION: '5.0'
        ARCHS: arm64
schemes:
  HeadlessProbe:
    build:
      targets:
        HeadlessProbe: all
'''
 (directory/'project.yml').write_text(spec)
 run(['xcodegen','generate','--spec',str(directory/'project.yml')])
 run(['xcodebuild','-project',str(directory/'HeadlessProbe.xcodeproj'),'-scheme','HeadlessProbe','-configuration','Release','-destination',f'platform=iOS Simulator,id={args.simulator}','-derivedDataPath',str(directory/'derived'),'CODE_SIGNING_ALLOWED=NO','build'],log=directory/'xcode.log')
 bundle=directory/'derived/Build/Products/Release-iphonesimulator/HeadlessProbe.app'
 frameworks=sorted(p.name for p in (bundle/'Frameworks').glob('GL*.framework'))
 assert 'GLMap.framework' not in frameworks
 assert ('GLSearch.framework' in frameworks)==(name=='search')
 assert ('GLRoute.framework' in frameworks)==(name=='route')
 run(['xcrun','simctl','install',args.simulator,str(bundle)])
 container=Path(subprocess.check_output(['xcrun','simctl','get_app_container',args.simulator,app,'data'],text=True).strip())
 resultpath=container/'Documents/result.txt';resultpath.unlink(missing_ok=True)
 run(['xcrun','simctl','launch',args.simulator,app])
 for _ in range(20):
  time.sleep(2)
  if resultpath.exists():break
 assert resultpath.read_text().strip()=='PASS '+name,resultpath
 results[name]={'android':'PASS','ios':'PASS','libraries':libs,'frameworks':frameworks}
 print('iOS PASS',name,frameworks,flush=True)
(out/'results.json').write_text(json.dumps(results,indent=2)+'\n')
