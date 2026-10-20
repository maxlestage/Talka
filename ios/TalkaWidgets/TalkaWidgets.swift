// Les widgets de Talka, pour l'iPhone et l'Apple Watch (le même code) :
//
// - « Non lus » : le nombre de messages non lus et le dernier arrivé.
//   iPhone : écran d'accueil (petit) et écran verrouillé ; montre :
//   complications (rond, rectangle, ligne, coin).
// - « Discussions » (iPhone) : les discussions récentes, chacune ouvre la
//   sienne d'un toucher.
//
// Les données viennent de l'application (TalkaKit/WidgetData.swift), par le
// trousseau ; elle recharge les widgets quand elles changent. Si Talka est
// protégé par un code, les widgets ne montrent que le nombre de non-lus.

import SwiftUI
import WidgetKit

@main
struct TalkaWidgets: WidgetBundle {
    var body: some Widget {
        UnreadWidget()
        #if os(iOS)
        RecentChatsWidget()
        #endif
    }
}

// ----- Les données -----

struct Entry: TimelineEntry {
    let date: Date
    /// nil : Talka n'a encore rien écrit (pas ouvert, ou pas de compte).
    let data: WidgetData?
}

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> Entry {
        Entry(date: .now, data: .sample)
    }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        let data = WidgetShare.read()
        completion(Entry(date: .now, data: context.isPreview && data == nil ? .sample : data))
    }

    /// Une seule entrée : c'est l'application qui recharge quand ça change.
    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        completion(Timeline(entries: [Entry(date: .now, data: WidgetShare.read())], policy: .never))
    }
}

extension WidgetData {
    /// Pour la galerie des widgets.
    static let sample = WidgetData(
        unread: 3,
        chats: [
            WidgetChat(id: "WGSEAJNSZJ", title: "Léa", initials: "L", color: 0, group: false, unread: 2,
                       last: "On se retrouve à 19 h ?", at: Date().timeIntervalSince1970 * 1000 - 120_000),
            WidgetChat(id: "g:famille", title: "Famille", initials: "F", color: 3, group: true, unread: 1,
                       last: "Papa : 📷 Photo", at: Date().timeIntervalSince1970 * 1000 - 900_000),
            WidgetChat(id: "K7Q2MX9PRT", title: "Clément", initials: "C", color: 5, group: false, unread: 0,
                       last: "Merci !", at: Date().timeIntervalSince1970 * 1000 - 3_600_000),
        ],
        at: Date().timeIntervalSince1970 * 1000
    )

    /// La discussion à mettre en avant : la plus récente des non lues.
    var latestUnread: WidgetChat? { chats.first { $0.unread > 0 } }
}

extension Color {
    static let talka = Color(red: 0, green: 0.6588, blue: 0.5176)
    static let talkaDeep = Color(red: 0.0549, green: 0.4196, blue: 0.3882)
    static let avatars: [Color] = [
        .init(red: 0.898, green: 0.2588, blue: 0.6392), .init(red: 0.1216, green: 0.4784, blue: 0.9255),
        .init(red: 0.8275, green: 0.4196, blue: 0), .init(red: 0.0078, green: 0.651, blue: 0.5961),
        .init(red: 0.498, green: 0.4, blue: 1), .init(red: 0.7686, green: 0.3255, blue: 0.1765),
        .init(red: 0, green: 0.502, blue: 0.4118), .init(red: 0.651, green: 0.1725, blue: 0.4431),
    ]
}

// ----- « Non lus » -----

struct UnreadWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "talka.unread", provider: Provider()) { entry in
            UnreadView(entry: entry)
        }
        .configurationDisplayName("Non lus")
        .description("Vos messages non lus et le dernier arrivé.")
        .supportedFamilies(Self.families)
    }

    static var families: [WidgetFamily] {
        #if os(watchOS)
        [.accessoryCircular, .accessoryRectangular, .accessoryInline, .accessoryCorner]
        #else
        [.systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline]
        #endif
    }
}

struct UnreadView: View {
    @Environment(\.widgetFamily) private var family
    let entry: Entry

    var data: WidgetData { entry.data ?? WidgetData() }
    var latest: WidgetChat? { data.hidden ? nil : data.latestUnread }

    var body: some View {
        content
            .widgetURL(WidgetData.link(latest?.id))
    }

    @ViewBuilder var content: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                VStack(spacing: 0) {
                    Image(systemName: data.unread > 0 ? "bubble.left.and.text.bubble.right.fill" : "bubble.left.and.bubble.right")
                        .font(.system(size: 13, weight: .semibold))
                    Text(data.unread > 99 ? "99+" : "\(data.unread)")
                        .font(.system(size: 17, weight: .bold, design: .rounded))
                        .minimumScaleFactor(0.6)
                }
            }
            .accessibilityLabel(data.summary)
            .containerBackground(for: .widget) { Color.clear }
        case .accessoryInline:
            Label(entry.data == nil ? "Talka" : inlineText, systemImage: "bubble.left.fill")
                .containerBackground(for: .widget) { Color.clear }
        case .accessoryRectangular:
            VStack(alignment: .leading, spacing: 1) {
                Label("Talka", systemImage: "bubble.left.fill")
                    .font(.headline)
                    .widgetAccentable()
                if let latest {
                    Text(latest.title).font(.caption.bold()).lineLimit(1)
                    Text(latest.last).font(.caption).lineLimit(1).foregroundStyle(.secondary)
                } else {
                    Text(entry.data == nil ? "Ouvrez Talka" : data.summary).font(.caption).lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .containerBackground(for: .widget) { Color.clear }
        default:
            other
        }
    }

    var small: some View {
        SmallView(entry: entry)
            .containerBackground(for: .widget) {
                LinearGradient(colors: [.talka, .talkaDeep], startPoint: .topLeading, endPoint: .bottomTrailing)
            }
    }

    #if os(watchOS)
    /// Le coin du cadran (montre seulement).
    @ViewBuilder var other: some View {
        if family == .accessoryCorner {
            Image(systemName: "bubble.left.fill")
                .font(.title3)
                .widgetLabel(data.unread > 0 ? "\(data.unread) non lus" : "Talka")
                .containerBackground(for: .widget) { Color.clear }
        } else {
            small
        }
    }
    #else
    var other: some View { small }
    #endif

    var inlineText: String {
        switch data.unread {
        case 0: "Talka : rien de nouveau"
        case 1: "1 non lu"
        default: "\(data.unread) non lus"
        }
    }
}

struct SmallView: View {
    let entry: Entry

    var body: some View {
        let data = entry.data ?? WidgetData()
        let latest = data.hidden ? nil : data.latestUnread
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 5) {
                Image(systemName: "bubble.left.fill")
                Text("Talka").font(.headline)
                Spacer(minLength: 0)
            }
            Spacer(minLength: 0)
            Text("\(data.unread)")
                .font(.system(size: 44, weight: .bold, design: .rounded))
                .minimumScaleFactor(0.5)
                .lineLimit(1)
            Text(entry.data == nil ? "Ouvrez Talka" : data.unread == 1 ? "message non lu" : "messages non lus")
                .font(.caption)
                .opacity(0.85)
            if let latest {
                Text("\(latest.title) : \(latest.last)")
                    .font(.caption2)
                    .lineLimit(2)
                    .opacity(0.9)
            }
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
    }
}

// ----- « Discussions » (iPhone) -----

#if os(iOS)
struct RecentChatsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "talka.recent", provider: Provider()) { entry in
            RecentView(entry: entry)
                .containerBackground(for: .widget) { Color(.systemBackground) }
        }
        .configurationDisplayName("Discussions")
        .description("Vos discussions récentes : touchez-en une pour l'ouvrir.")
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

struct RecentView: View {
    @Environment(\.widgetFamily) private var family
    let entry: Entry

    var body: some View {
        let data = entry.data ?? WidgetData()
        let rows = Array(data.chats.prefix(family == .systemLarge ? 6 : 3))
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label("Talka", systemImage: "bubble.left.fill")
                    .font(.headline)
                    .foregroundStyle(Color.talka)
                Spacer()
                Text(entry.data == nil ? "" : data.summary)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if entry.data == nil {
                Spacer()
                Text("Ouvrez Talka pour voir vos discussions ici.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
            } else if data.hidden {
                Spacer()
                Label("Talka est protégé par un code : les discussions restent dans l'application.", systemImage: "lock.fill")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
            } else if rows.isEmpty {
                Spacer()
                Text("Aucune discussion pour l'instant.").font(.subheadline).foregroundStyle(.secondary)
                Spacer()
            } else {
                ForEach(rows) { c in
                    Link(destination: WidgetData.link(c.id)) { ChatLine(chat: c) }
                }
                Spacer(minLength: 0)
            }
        }
        .widgetURL(WidgetData.link())
    }
}

struct ChatLine: View {
    let chat: WidgetChat

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(chat.group ? Color.gray : Color.avatars[chat.color % Color.avatars.count])
                .frame(width: 34, height: 34)
                .overlay {
                    if chat.group {
                        Image(systemName: "person.2.fill").font(.system(size: 13))
                    } else {
                        Text(chat.initials).font(.system(size: 14, weight: .semibold))
                    }
                }
                .foregroundStyle(.white)
            VStack(alignment: .leading, spacing: 1) {
                Text(chat.title).font(.subheadline.weight(chat.unread > 0 ? .bold : .semibold)).lineLimit(1)
                Text(chat.last).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 3) {
                Text(Date(timeIntervalSince1970: chat.at / 1000), style: .time)
                    .font(.caption2)
                    .foregroundStyle(chat.unread > 0 ? Color.talka : .secondary)
                if chat.unread > 0 {
                    Text("\(chat.unread)")
                        .font(.caption2.bold())
                        .padding(.horizontal, 5)
                        .frame(minWidth: 18, minHeight: 18)
                        .background(Color.talka, in: .capsule)
                        .foregroundStyle(.white)
                }
            }
        }
    }
}
#endif
