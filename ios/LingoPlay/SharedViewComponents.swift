import AVFoundation
import AVKit
import CoreTransferable
import PhotosUI
import StoreKit
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ScreenHeader<Trailing: View>: View {
    let title: String
    let backAction: () -> Void
    @ViewBuilder let trailing: () -> Trailing

    init(title: String, backAction: @escaping () -> Void, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.title = title
        self.backAction = backAction
        self.trailing = trailing
    }

    var body: some View {
        HStack {
            Button(action: backAction) {
                Image(systemName: "chevron.left")
                    .font(.headline.bold())
                    .frame(width: 38, height: 38)
                    .background(LPTheme.surface, in: Circle())
            }
            .buttonStyle(.plain)
            Spacer()
            Text(title)
                .font(.headline)
                .lineLimit(1)
            Spacer()
            trailing()
                .frame(minWidth: 38, alignment: .trailing)
        }
    }
}

extension ScreenHeader where Trailing == EmptyView {
    init(title: String, backAction: @escaping () -> Void) {
        self.init(title: title, backAction: backAction) {
            EmptyView()
        }
    }
}

struct VideoPlaceholder: View {
    let width: CGFloat?
    let height: CGFloat

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Color(red: 0.05, green: 0.12, blue: 0.22), Color(red: 0.18, green: 0.08, blue: 0.28), Color(red: 0.04, green: 0.24, blue: 0.30)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Circle()
                .fill(.ultraThinMaterial)
                .frame(width: min(height * 0.34, 62), height: min(height * 0.34, 62))
            Image(systemName: "play.fill")
                .font(.system(size: min(height * 0.13, 24), weight: .bold))
                .foregroundStyle(.white)
                .offset(x: 2)
            VStack {
                Spacer()
                HStack {
                    Label("LOCAL", systemImage: "lock.fill")
                        .font(.system(size: 8, weight: .bold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 5)
                        .background(.black.opacity(0.35), in: Capsule())
                    Spacer()
                }
                .padding(10)
            }
        }
        .frame(maxWidth: width == nil ? .infinity : nil)
        .frame(width: width, height: height)
        .clipped()
    }
}
