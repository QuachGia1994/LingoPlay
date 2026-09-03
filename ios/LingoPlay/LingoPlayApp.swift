import SwiftUI

@main
struct LingoPlayApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                .preferredColorScheme(.dark)
        }
    }
}
