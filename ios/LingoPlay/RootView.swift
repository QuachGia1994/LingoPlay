import SwiftUI

struct RootView: View {
    @Bindable var model: AppModel

    var body: some View {
        ZStack {
            LPBackdrop()

            VStack(spacing: 0) {
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                if model.stage == .home {
                    BottomNavigation(model: model)
                }
            }
        }
        .foregroundStyle(.white)
        .tint(LPTheme.cyan)
        .task {
            guard model.stage == .splash else { return }
            try? await Task.sleep(for: .milliseconds(900))
            model.finishSplash()
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.stage {
        case .splash:
            SplashView()
        case .prepare:
            PrepareView(model: model)
        case .processing:
            ProcessingView(model: model)
        case .player:
            PlayerView(model: model)
        case .home:
            switch model.selectedTab {
            case .home:
                HomeView(model: model)
            case .library:
                LibraryView(model: model, offlineOnly: false)
            case .offline:
                LibraryView(model: model, offlineOnly: true)
            case .settings:
                SettingsView(model: model)
            }
        }
    }
}

private struct BottomNavigation: View {
    @Bindable var model: AppModel

    var body: some View {
        HStack(spacing: 2) {
            tab(.home)
            tab(.library)

            Button {
                model.returnHome()
            } label: {
                LPBrandMark(compact: true)
                    .padding(.horizontal, 6)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("LingoPlay Home")

            tab(.offline)
            tab(.settings)
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 8)
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(LPTheme.border)
                .frame(height: 1)
        }
    }

    private func tab(_ tab: AppModel.Tab) -> some View {
        let active = model.selectedTab == tab
        return Button {
            model.selectTab(tab)
        } label: {
            VStack(spacing: 5) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: 17, weight: .semibold))
                Text(tab.rawValue)
                    .font(.system(size: 10, weight: .medium))
            }
            .frame(maxWidth: .infinity)
            .foregroundStyle(active ? LPTheme.cyan : LPTheme.secondaryText)
        }
        .buttonStyle(.plain)
    }
}
