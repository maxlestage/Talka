// Les écrans de la montre : la liste des discussions, une discussion.

import SwiftUI

struct ChatsView: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        NavigationStack {
            Group {
                if let s = model.snapshot, !s.chats.isEmpty {
                    List(s.chats) { c in
                        NavigationLink(value: c.id) { ChatRow(chat: c) }
                    }
                } else {
                    EmptyState(waiting: model.snapshot == nil)
                }
            }
            .navigationTitle("Talka")
            .navigationDestination(for: String.self) { id in ConversationView(chatId: id) }
            .toolbar {
                if model.snapshot != nil, !model.reachable {
                    ToolbarItem(placement: .topBarTrailing) {
                        Image(systemName: "iphone.slash")
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("iPhone hors de portée : les réponses partiront à son retour")
                    }
                }
            }
        }
        .onAppear { model.refresh() }
    }
}

struct EmptyState: View {
    let waiting: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                Image(systemName: waiting ? "iphone.radiowaves.left.and.right" : "bubble.left.and.bubble.right")
                    .font(.largeTitle)
                    .foregroundStyle(Color.talkaGreen)
                Text(waiting ? "Ouvrez Talka sur votre iPhone" : "Aucune discussion")
                    .font(.headline)
                    .multilineTextAlignment(.center)
                Text(waiting
                     ? "La montre affiche les discussions de l'iPhone et répond par lui."
                     : "Vos discussions récentes apparaîtront ici.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.top, 8)
        }
    }
}

struct AvatarView: View {
    let chat: WatchChat
    var size: CGFloat = 32

    var body: some View {
        Circle()
            .fill(chat.group ? Color.gray : Color.avatars[chat.color % Color.avatars.count])
            .frame(width: size, height: size)
            .overlay {
                if chat.group {
                    Image(systemName: "person.2.fill").font(.system(size: size * 0.4))
                } else {
                    Text(chat.initials).font(.system(size: size * 0.4, weight: .semibold))
                }
            }
            .foregroundStyle(.white)
            .accessibilityHidden(true)
    }
}

struct ChatRow: View {
    let chat: WatchChat

    var body: some View {
        HStack(spacing: 8) {
            AvatarView(chat: chat)
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 4) {
                    Text(chat.title).font(.headline).lineLimit(1)
                    if chat.pinned { Image(systemName: "pin.fill").font(.caption2).foregroundStyle(.secondary) }
                    if chat.muted { Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.secondary) }
                }
                Text(chat.last).font(.footnote).foregroundStyle(.secondary).lineLimit(2)
            }
            Spacer(minLength: 0)
            if chat.unread > 0 {
                Text("\(chat.unread)")
                    .font(.caption2.bold())
                    .padding(.horizontal, 5)
                    .frame(minWidth: 18, minHeight: 18)
                    .background(Color.talkaGreen, in: .capsule)
                    .foregroundStyle(.white)
                    .accessibilityLabel("\(chat.unread) non lus")
            }
        }
        .padding(.vertical, 2)
    }
}

struct ConversationView: View {
    @Environment(WatchModel.self) private var model
    let chatId: String
    @State private var reacting: WatchMessage?

    var body: some View {
        if let chat = model.chat(chatId) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(chat.messages) { m in
                            Bubble(message: m)
                                .id(m.id)
                                .onTapGesture { if !m.system && !m.id.hasPrefix("montre-") { reacting = m } }
                        }
                        if chat.readOnly {
                            Text(chat.group ? "Vous ne faites plus partie de ce groupe." : "Contact bloqué.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .padding(.top, 6)
                        } else {
                            Composer(chatId: chatId).id("composer")
                        }
                    }
                }
                .onAppear { proxy.scrollTo("composer", anchor: .bottom) }
                .onChange(of: chat.messages.last?.id) { _, _ in
                    withAnimation { proxy.scrollTo("composer", anchor: .bottom) }
                    model.markRead(chatId)
                }
            }
            .navigationTitle(chat.title)
            .onAppear { model.markRead(chatId) }
            .sheet(item: $reacting) { m in
                ReactionPicker(current: m.myReaction) { emoji in
                    model.react(chatId, m.id, emoji)
                    reacting = nil
                }
            }
        } else {
            Text("Discussion introuvable").foregroundStyle(.secondary)
        }
    }
}

struct Bubble: View {
    let message: WatchMessage

    var body: some View {
        let m = message
        if m.system {
            Text(m.text)
                .font(.caption2)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
        } else {
            VStack(alignment: m.mine ? .trailing : .leading, spacing: 2) {
                VStack(alignment: .leading, spacing: 2) {
                    if !m.author.isEmpty {
                        Text(m.author).font(.caption2.bold()).foregroundStyle(Color.talkaGreen)
                    }
                    Text(m.text).font(.footnote)
                    HStack(spacing: 3) {
                        Text(Date(timeIntervalSince1970: m.at / 1000), format: .dateTime.hour().minute())
                        if m.mine { TickMark(tick: m.tick) }
                    }
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(m.mine ? Color.bubbleOut : Color.bubbleIn, in: .rect(cornerRadius: 12))
                if !m.reactions.isEmpty {
                    Text(m.reactions)
                        .font(.caption2)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(Color.bubbleIn, in: .capsule)
                        .accessibilityLabel("Réactions : \(m.reactions)")
                }
            }
            .frame(maxWidth: .infinity, alignment: m.mine ? .trailing : .leading)
            .padding(m.mine ? Edge.Set.leading : Edge.Set.trailing, 14)
        }
    }
}

struct TickMark: View {
    let tick: String

    var body: some View {
        switch tick {
        case "pending": Image(systemName: "clock").accessibilityLabel("En attente")
        case "failed": Image(systemName: "exclamationmark.circle").foregroundStyle(.red).accessibilityLabel("Non envoyé")
        case "delivered": Image(systemName: "checkmark.circle").accessibilityLabel("Distribué")
        case "read": Image(systemName: "checkmark.circle.fill").foregroundStyle(Color.tickBlue).accessibilityLabel("Lu")
        default: Image(systemName: "checkmark").accessibilityLabel("Envoyé")
        }
    }
}

/// Répondre : dictée ou clavier, une réponse rapide, ou un emoji.
struct Composer: View {
    @Environment(WatchModel.self) private var model
    let chatId: String

    var body: some View {
        VStack(spacing: 6) {
            TextFieldLink(prompt: Text("Message")) {
                Label("Répondre", systemImage: "mic.fill")
                    .frame(maxWidth: .infinity)
            } onSubmit: { text in
                model.send(chatId, text)
            }
            .buttonStyle(.borderedProminent)
            .tint(.talkaGreen)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(watchReactions, id: \.self) { e in
                        Button(e) { model.send(chatId, e) }
                            .buttonStyle(.bordered)
                            .frame(width: 40)
                    }
                }
            }
            ForEach(watchQuickReplies, id: \.self) { r in
                Button(r) { model.send(chatId, r) }
                    .buttonStyle(.bordered)
            }
        }
        .padding(.top, 6)
    }
}

struct ReactionPicker: View {
    let current: String
    let pick: (String) -> Void

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text("Réagir").font(.headline)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 8) {
                    ForEach(watchReactions, id: \.self) { e in
                        Button { pick(e) } label: {
                            Text(e).font(.title2)
                        }
                        .buttonStyle(.bordered)
                        .tint(e == current ? Color.talkaGreen : nil)
                        .accessibilityLabel(e == current ? "Retirer \(e)" : e)
                    }
                }
                if !current.isEmpty {
                    Text("Touchez \(current) pour la retirer").font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }
}
