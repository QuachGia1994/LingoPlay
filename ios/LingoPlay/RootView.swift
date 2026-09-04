import Foundation
import SwiftUI

struct RootView: View {
    @Bindable var model: AppModel

    var body: some View {
        ZStack {
            LPBackdrop(highContrast: model.highContrast)

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
        .environment(\.locale, Locale(identifier: model.uiLanguageCode))
        .sheet(isPresented: $model.plusPresented) {
            PlusView(model: model)
        }
        .sheet(isPresented: $model.aboutPresented) {
            AboutView(model: model)
        }
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
                LibraryView(model: model)
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
                model.beginImport()
            } label: {
                VStack(spacing: 4) {
                    Image(systemName: "plus")
                        .font(.system(size: 18, weight: .bold))
                        .frame(width: 40, height: 40)
                        .foregroundStyle(.white)
                        .background(LPTheme.accent, in: Circle())
                    Text(model.uiText("Import", "Chọn video"))
                        .font(.system(size: 10, weight: .medium))
                        .foregroundStyle(LPTheme.cyan)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(model.uiText("Import Video", "Chọn video"))

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
                Text(tabLabel(tab))
                    .font(.system(size: 10, weight: .medium))
            }
            .frame(maxWidth: .infinity)
            .foregroundStyle(active ? LPTheme.cyan : LPTheme.secondaryText)
        }
        .buttonStyle(.plain)
    }

    private func tabLabel(_ tab: AppModel.Tab) -> String {
        switch tab {
        case .home: model.uiText("Home", "Trang chủ")
        case .library: model.uiText("Library", "Thư viện")
        case .settings: model.uiText("Settings", "Cài đặt")
        }
    }
}
