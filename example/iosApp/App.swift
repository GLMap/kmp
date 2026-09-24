import SwiftUI
import GLMapCore
import GLMapCoreSwift
import GLMapDemoShared

@main
struct GLMapDemoApp: App {
    init() { GLMapManager.activate(apiKey: "") }
    var body: some Scene { WindowGroup { ComposeScreen().ignoresSafeArea() } }
}

/// Optional build-time key; the bundled-data checks also run without a key.
private let demoKey = Bundle.main.url(forResource: "glmap-key", withExtension: "txt")
    .flatMap { try? String(contentsOf: $0, encoding: .utf8) }?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

private struct ComposeScreen: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(apiKey: demoKey)
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
