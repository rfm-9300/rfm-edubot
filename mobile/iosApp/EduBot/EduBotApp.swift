import SwiftUI
import EduBotShared

@main
struct EduBotApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeRootView()
                .ignoresSafeArea(.keyboard)
        }
    }
}

private struct ComposeRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // The device language, so sign-in is readable before the tenant's own locale is known.
        IosAppKt.MainViewController(deviceLocale: Locale.current.identifier)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
