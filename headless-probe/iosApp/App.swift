import SwiftUI
import GlobusHeadlessProbe
@main struct ProbeApp:App {
 @State private var text="Running"
 var body:some Scene {WindowGroup {Text(text).task {
  precondition(NSClassFromString("GLMapView")==nil)
  HeadlessProbe().run {result in
   text=result
   let url=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("result.txt")
   try! result.write(to:url,atomically:true,encoding:.utf8)
  }
 }}}
}
