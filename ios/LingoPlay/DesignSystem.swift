import SwiftUI

enum LPTheme {
    static let background = Color(red: 0.025, green: 0.035, blue: 0.070)
    static let surface = Color.white.opacity(0.055)
    static let surfaceStrong = Color.white.opacity(0.085)
    static let border = Color.white.opacity(0.10)
    static let secondaryText = Color.white.opacity(0.62)
    static let violet = Color(red: 0.66, green: 0.29, blue: 1.0)
    static let cyan = Color(red: 0.10, green: 0.82, blue: 1.0)
    static let accent = LinearGradient(colors: [violet, Color(red: 0.35, green: 0.44, blue: 1.0), cyan], startPoint: .leading, endPoint: .trailing)
}

struct LPBrandMark: View {
    var compact = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: compact ? 14 : 24, style: .continuous)
                .fill(LPTheme.surfaceStrong)
                .overlay {
                    RoundedRectangle(cornerRadius: compact ? 14 : 24, style: .continuous)
                        .stroke(LPTheme.accent, lineWidth: 1.5)
                }
                .shadow(color: LPTheme.violet.opacity(0.25), radius: 18, x: -4, y: 3)
                .shadow(color: LPTheme.cyan.opacity(0.18), radius: 18, x: 4, y: 3)

            Image(systemName: "message.fill")
                .font(.system(size: compact ? 24 : 42, weight: .semibold))
                .foregroundStyle(LPTheme.accent)

            Image(systemName: "play.fill")
                .font(.system(size: compact ? 10 : 17, weight: .bold))
                .foregroundStyle(.white)
                .offset(y: compact ? -2 : -4)
        }
        .frame(width: compact ? 50 : 86, height: compact ? 50 : 86)
        .accessibilityLabel("LingoPlay")
    }
}

struct LPCardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(LPTheme.surface, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .stroke(LPTheme.border, lineWidth: 1)
            }
    }
}

extension View {
    func lpCard() -> some View {
        modifier(LPCardModifier())
    }
}

struct LPPrimaryButton: View {
    let title: String
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.headline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .background(LPTheme.accent, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .foregroundStyle(.white)
                .shadow(color: LPTheme.violet.opacity(0.24), radius: 18, y: 8)
        }
        .buttonStyle(.plain)
    }
}

struct LPSectionHeader: View {
    let title: String
    var trailing: String?

    var body: some View {
        HStack {
            Text(title)
                .font(.headline)
            Spacer()
            if let trailing {
                Text(trailing)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(LPTheme.secondaryText)
            }
        }
    }
}

struct LPBackdrop: View {
    var body: some View {
        ZStack {
            LPTheme.background
            Circle()
                .fill(LPTheme.violet.opacity(0.13))
                .frame(width: 330, height: 330)
                .blur(radius: 90)
                .offset(x: -180, y: -320)
            Circle()
                .fill(LPTheme.cyan.opacity(0.10))
                .frame(width: 320, height: 320)
                .blur(radius: 100)
                .offset(x: 180, y: 260)
        }
        .ignoresSafeArea()
    }
}
