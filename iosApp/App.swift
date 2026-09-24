import SwiftUI
import GLMapCore
import GLMapCoreSwift
import GLMapKmpDemo

@main
struct GLMapKmpLabApp: App {
    init() { GLMapManager.activate(apiKey: "") }
    var body: some Scene { WindowGroup { ComposeScreen().ignoresSafeArea() } }
}
/// Demo key embedded at build time from ignored local configuration; Stage A keeps running without one.
private let demoKey = Bundle.main.url(forResource: "glmap-key", withExtension: "txt")
    .flatMap { try? String(contentsOf: $0, encoding: .utf8) }?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
private struct ComposeScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController(apiKey: demoKey) }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
