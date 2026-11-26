import SwiftUI

/// Localized string with optional printf-style arguments (keys live in Resources/*.lproj/Localizable.strings).
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, comment: "")
    return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
}

/// Keeps phone numbers and codes left-to-right inside right-to-left (Persian) text.
func ltr(_ s: String) -> String { "\u{2066}\(s)\u{2069}" }

enum Fmt {
    static func relative(_ d: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(d) { return d.formatted(date: .omitted, time: .shortened) }
        if cal.isDateInYesterday(d) { return L("time.yesterday") }
        if let days = cal.dateComponents([.day], from: d, to: .now).day, days < 7 { return d.formatted(.dateTime.weekday(.wide)) }
        return d.formatted(date: .numeric, time: .omitted)
    }

    static func duration(_ seconds: Int64) -> String {
        let s = max(0, seconds)
        let f = DateComponentsFormatter()
        f.allowedUnits = s >= 3600 ? [.hour, .minute, .second] : [.minute, .second]
        f.zeroFormattingBehavior = .pad
        return f.string(from: TimeInterval(s)) ?? "\(s)"
    }

    static func number(_ n: Int64) -> String { n.formatted(.number) }

    static func initials(_ name: String) -> String {
        let parts = name.split(separator: " ").prefix(2).compactMap(\.first)
        return parts.isEmpty ? "#" : String(parts).uppercased()
    }
}

/// Monogram avatar with a calm tint chosen from the name.
struct Avatar: View {
    let name: String
    var size: CGFloat = 40
    private static let tints: [Color] = [.blue, .teal, .indigo, .orange, .pink, .green, .purple]

    var body: some View {
        let tint = Self.tints[abs(name.hashValue) % Self.tints.count]
        Text(Fmt.initials(name))
            .font(.system(size: size * 0.4, weight: .semibold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(Circle().fill(tint.gradient))
            .accessibilityHidden(true)
    }
}

struct SignalBars: View {
    let level: Int?
    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4, id: \.self) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < (level ?? -1) ? Color.primary : Color.secondary.opacity(0.3))
                    .frame(width: 3, height: CGFloat(4 + i * 3))
            }
        }
        .accessibilityElement()
        .accessibilityLabel(L("a11y.signal %d", level ?? 0))
    }
}

/// The six-digit pairing code, large and monospaced, read digit by digit by VoiceOver.
struct SasCodeView: View {
    let sas: String
    var body: some View {
        HStack(spacing: 16) {
            Text(verbatim: String(sas.prefix(3)))
            Text(verbatim: String(sas.suffix(3)))
        }
        .font(.system(size: 44, weight: .semibold, design: .monospaced))
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityElement()
        .accessibilityLabel(sas.map(String.init).joined(separator: " "))
    }
}

/// Line selector, shown only when the gateway really has more than one SIM to choose from.
struct SimMenu: View {
    @Environment(AppModel.self) private var model
    @Binding var subId: Int?

    var body: some View {
        if let s = model.status, s.sims.count > 1, s.caps.simChoice {
            Menu {
                Picker(L("sim.choose"), selection: $subId) {
                    ForEach(s.sims, id: \.subId) { sim in Text(verbatim: sim.displayLabel).tag(Optional(sim.subId)) }
                }
            } label: {
                Label(s.sims.first { $0.subId == subId }?.displayLabel ?? L("sim.choose"), systemImage: "simcard")
                    .font(.subheadline)
            }
            .accessibilityLabel(L("sim.choose"))
        }
    }
}

/// Banner for anything other than a healthy connection.
struct LinkBanner: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        if let text = text {
            Label(text, systemImage: icon)
                .font(.footnote.weight(.medium))
                .padding(.horizontal, 14).padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.thinMaterial)
                .accessibilityAddTraits(.isStaticText)
        }
    }
    private var text: String? {
        if model.demo { return L("banner.demo") }
        switch model.link {
        case .connected, .notPaired: return nil
        case .bluetoothOff: return L("banner.btOff")
        case .bluetoothDenied: return L("banner.btDenied")
        case .connecting: return L("banner.connecting")
        case .reconnecting: return L("banner.reconnecting")
        case .searching: return L("banner.searching")
        }
    }
    private var icon: String { model.demo ? "theatermasks" : (model.link == .bluetoothOff || model.link == .bluetoothDenied ? "antenna.radiowaves.left.and.right.slash" : "antenna.radiowaves.left.and.right") }
}

func resultMessage(_ r: ActionResult) -> String? {
    switch r {
    case .ok: return nil
    case .notConnected: return L("err.notConnected")
    case .invalid: return L("err.invalidNumber")
    case .unsupported: return L("err.unsupported")
    }
}

func describeError(_ code: String) -> String {
    if code.hasPrefix("audio:") {
        switch code.dropFirst(6) {
        case "microphone": return L("audio.err.microphone")
        case "android_version": return L("audio.err.android")
        case "not_interceptable": return L("audio.err.notSupported")
        case "not_set_up": return L("audio.err.setup")
        default: return L("audio.err.failed")
        }
    }
    switch code.split(separator: ":").last.map(String.init) ?? code {
    case "permission": return L("err.gatewayPermission")
    case "unsupported": return L("err.unsupported")
    case "invalid": return L("err.invalidNumber")
    case "no_service": return L("err.noService")
    case "busy": return L("err.busy")
    default: return code.hasPrefix("sms:") ? L("err.smsFailed") : L("err.failed")
    }
}
