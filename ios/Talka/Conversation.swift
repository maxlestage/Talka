// Une conversation : les bulles (texte, photo, vocal, fichier, contact), les
// réponses, réactions et ✓✓, le menu d'un message, et la zone de saisie.

import PhotosUI
import SwiftUI
import TalkaKit
import UniformTypeIdentifiers

struct ConversationView: View {
    @Environment(AppModel.self) private var model
    let chatId: String
    @State private var text = ""
    @State private var reply: Message?
    @State private var editing: Message?
    @State private var photos: [PhotosPickerItem] = []
    @State private var pickingPhotos = false
    @State private var takingPhoto = false
    @State private var importingFile = false
    @State private var sharingContact = false
    @State private var composingCapsule = false
    @State private var forwarding: Message?
    @State private var info: Message?
    @State private var deleting: Message?
    @State private var viewing: Message?
    @State private var showInfo = false
    @State private var recorder = VoiceRecorder()
    @State private var loaded = false
    /// La recherche dans la discussion (nil : fermée).
    @State private var search: String?
    /// Le message vers lequel faire défiler (bandeau de l'épingle).
    @State private var jumpTo: String?
    /// Le choix des clés Brocolis 🥦 de la discussion.
    @State private var choosingBrocolis = false
    /// La pluie de brocolis en cours (l'œuf de Pâques 🥦).
    @State private var brocolis: UUID?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var focused: Bool
    @FocusState private var searchFocused: Bool

    var body: some View {
        if let e = model.engine, let chat = e.chat(chatId) {
            let _ = e.revisions[chatId]
            // Les messages éphémères expirés disparaissent tout de suite, comme dans le navigateur.
            let now = nowMs()
            let query = (search ?? "").trimmingCharacters(in: .whitespaces)
            let messages = e.messages(chatId).filter { (m: Message) -> Bool in
                if let exp = m.exp, exp <= now { return false }
                return query.isEmpty || Self.matches(m, query, me: e.me.code)
            }
            VStack(spacing: 0) {
                if search != nil {
                    searchBar(found: messages.count, active: !query.isEmpty)
                }
                if let pinned = e.pinnedMessage(chatId) {
                    pinBar(e, pinned)
                }
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 2) {
                            Label("Les messages sont chiffrés de bout en bout. Personne d'autre, pas même le serveur, ne peut les lire.",
                                  systemImage: "lock.fill")
                                .font(.caption)
                                .multilineTextAlignment(.center)
                                .padding(10)
                                // Translucide : le fond de la discussion reste visible.
                                .frosted(in: .rect(cornerRadius: 8))
                                .padding(.horizontal, 30)
                                .padding(.vertical, 8)
                            ForEach(Array(messages.enumerated()), id: \.element.id) { i, m in
                                if i == 0 || !Dates.sameDay(messages[i - 1].at, m.at) {
                                    Text(Dates.separator(m.at))
                                        .font(.caption.weight(.medium))
                                        .padding(.horizontal, 10)
                                        .padding(.vertical, 4)
                                        .background(Color(.systemBackground).opacity(0.9), in: .capsule)
                                        .padding(.vertical, 8)
                                }
                                let first = i == 0 || messages[i - 1].from != m.from || messages[i - 1].kind == "system"
                                MessageRow(engine: e, chat: chat, message: m, first: first, onReply: { reply = m; focused = true },
                                           onOpen: { viewing = m }, onScrollTo: { id in withAnimation { proxy.scrollTo(id, anchor: .center) } })
                                    .id(m.id)
                                    .contextMenu { menu(e, chat, m) }
                            }
                        }
                        .padding(.horizontal, 8)
                        .padding(.bottom, 8)
                    }
                    .defaultScrollAnchor(.bottom)
                    .scrollDismissesKeyboard(.interactively)
                    .onChange(of: messages.last?.id) { _, id in
                        guard let id else { return }
                        withAnimation(loaded ? .default : nil) { proxy.scrollTo(id, anchor: .bottom) }
                        e.markRead(chatId)
                        // Seulement pour un message qui arrive ou part maintenant.
                        if let m = messages.last, nowMs() - m.at < 10_000, m.summonsBrocoli(me: e.me.code) {
                            rainBrocolis()
                        }
                    }
                    .onAppear {
                        if let id = messages.last?.id { proxy.scrollTo(id, anchor: .bottom) }
                        loaded = true
                    }
                    .onChange(of: jumpTo) { _, id in
                        guard let id else { return }
                        if messages.contains(where: { $0.id == id }) {
                            withAnimation { proxy.scrollTo(id, anchor: .center) }
                        } else {
                            model.show("Message introuvable")
                        }
                        jumpTo = nil
                    }
                }
                composer(e, chat)
            }
            // Le fond couvre tout l'écran, sous la barre du haut et la zone de
            // saisie : pas de bande vide quand la photo n'a pas la bonne forme.
            .background { wallpaper(e, chat).ignoresSafeArea() }
            .overlay {
                if let id = brocolis {
                    BrocoliRain()
                        .id(id)
                        .task(id: id) {
                            try? await Task.sleep(for: .seconds(5))
                            if brocolis == id { brocolis = nil }
                        }
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            // Une barre opaque : le nom reste lisible sur une photo de fond.
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarBackground(Color(.systemBackground), for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Button { showInfo = true } label: {
                        HStack(spacing: 8) {
                            e.presenceAvatar(chatId, size: 34)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(e.title(chat)).font(.headline).lineLimit(1)
                                Text(subtitle(e, chat)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                            }
                            .fixedSize(horizontal: false, vertical: true)
                        }
                        // Toute la place entre le bouton retour et « ⋯ » (le titre
                        // est centré : 75 points de chaque côté pour eux), sans
                        // passer dessous.
                        .frame(width: max(160, UIScreen.main.bounds.width - 150), alignment: .leading)
                    }
                    .foregroundStyle(.primary)
                    .accessibilityLabel("Infos de \(e.title(chat))")
                }
                // Un seul bouton « ⋯ » : le nom garde toute la place.
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        if !chat.group, !e.blocked(chatId) {
                            Button { Task { await model.startCall(chatId, video: true) } } label: {
                                Label("Appel vidéo", systemImage: "video")
                            }
                            Button { Task { await model.startCall(chatId, video: false) } } label: {
                                Label("Appel audio", systemImage: "phone")
                            }
                        }
                        Button {
                            withAnimation { search = search == nil ? "" : nil }
                            searchFocused = search != nil
                        } label: {
                            Label(search == nil ? "Rechercher" : "Fermer la recherche", systemImage: "magnifyingglass")
                        }
                        Button { showInfo = true } label: {
                            Label(chat.group ? "Infos du groupe" : "Infos du contact", systemImage: "info.circle")
                        }
                        Button { choosingBrocolis = true } label: {
                            Label(chat.brocolis.isEmpty ? "Chiffrer avec Brocolis 🥦" : "Brocolis 🥦 : \(e.chatBrocolis(chat).map(\.name).joined(separator: ", "))",
                                  systemImage: "lock.shield")
                        }
                    } label: {
                        Image(systemName: "ellipsis")
                    }
                    .accessibilityLabel("Plus d'options")
                }
            }
            .toolbar(.hidden, for: .tabBar)
            .navigationDestination(isPresented: $showInfo) {
                if chat.group { GroupInfoView(chatId: chatId) } else { ContactInfoView(code: chatId) }
            }
            .onAppear {
                e.activeChat = chatId
                e.markRead(chatId)
                if text.isEmpty { text = chat.draft }
                // Arrivé par « Répondre en privé » : la réponse est prête.
                if let m = model.privateReply, m.from == chatId {
                    model.privateReply = nil
                    reply = m
                    focused = true
                }
            }
            .onDisappear {
                if e.activeChat == chatId { e.activeChat = nil }
                let draft = editing == nil ? text : ""
                if draft != e.chat(chatId)?.draft { e.updateChat(chatId) { $0.draft = draft } }
                e.setTyping(chatId, false)
            }
            .photosPicker(isPresented: $pickingPhotos, selection: $photos, maxSelectionCount: 10, matching: .images)
            .fullScreenCover(isPresented: $takingPhoto) {
                CameraPicker { image in
                    takingPhoto = false
                    if let image, let (url, w, h) = image.compressed() {
                        send(e, Content(kind: "image", data: url, mime: "image/jpeg", w: w, h: h))
                    }
                }
                .ignoresSafeArea()
            }
            .onChange(of: photos) { _, items in
                guard !items.isEmpty else { return }
                photos = []
                Task { await sendPhotos(e, items) }
            }
            .fileImporter(isPresented: $importingFile, allowedContentTypes: [.item]) { result in
                if let url = try? result.get() { sendFile(e, url) }
            }
            .sheet(isPresented: $composingCapsule) {
                CapsuleComposer { body, openAt in
                    composingCapsule = false
                    send(e, Content(text: body, openAt: openAt))
                    model.show("Capsule scellée ⏳")
                }
            }
            .sheet(isPresented: $sharingContact) {
                ContactPicker(title: "Partager un contact") { code in
                    sharingContact = false
                    let c = e.contact(code)
                    send(e, Content(kind: "contact", name: c?.name.isEmpty == false ? c!.name : c?.profileName ?? "", code: code))
                }
            }
            .sheet(item: $forwarding) { m in
                ContactPicker(title: "Transférer à") {
                    let groups = e.chats.values.filter { $0.group && !$0.left }
                    if !groups.isEmpty {
                        Section("Groupes") {
                            ForEach(groups) { g in
                                Button { forward(e, m, to: g.id) } label: {
                                    HStack(spacing: 12) {
                                        e.avatar(g.id, size: 40)
                                        Text(e.title(g)).foregroundStyle(.primary)
                                    }
                                }
                            }
                        }
                    }
                } pick: { code in forward(e, m, to: code) }
            }
            .sheet(item: $info) { m in MessageInfoSheet(engine: e, chat: chat, message: m) }
            .sheet(isPresented: $choosingBrocolis) { BrocolisChatSheet(chatId: chatId) }
            .fullScreenCover(item: $viewing) { m in ImageViewer(engine: e, message: m) }
            .confirmationDialog("Supprimer le message ?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible, presenting: deleting) { m in
                if m.from == e.me.code, !m.deleted, nowMs() - m.at < 2 * dayMs {
                    Button("Supprimer pour tout le monde", role: .destructive) { e.delete(chatId, m.id, forEveryone: true) }
                }
                Button("Supprimer pour moi", role: .destructive) { e.delete(chatId, m.id, forEveryone: false) }
            }
        } else {
            ContentUnavailableView("Discussion introuvable", systemImage: "bubble.left")
        }
    }

    // MARK: Recherche

    /// Comme le navigateur : le texte et le nom (fichier, contact), sans
    /// tenir compte des majuscules ni des accents.
    static func matches(_ m: Message, _ query: String, me: String) -> Bool {
        // La recherche ne trouve pas ce qu'une capsule scellée cache.
        guard m.kind != "system", !m.deleted, !m.sealed(for: me) else { return false }
        return "\(m.text) \(m.name)".range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) != nil
    }

    func searchBar(found: Int, active: Bool) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Rechercher dans la discussion…", text: Binding(get: { search ?? "" }, set: { search = $0 }))
                .focused($searchFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .accessibilityLabel("Rechercher dans la discussion")
            if active {
                Text(found == 0 ? "Aucun résultat" : found == 1 ? "1 message" : "\(found) messages")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize()
            }
            Button {
                withAnimation { search = nil }
            } label: {
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
            .accessibilityLabel("Fermer la recherche")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
        .transition(.move(edge: .top).combined(with: .opacity))
    }

    // MARK: Épingle

    /// Le message épinglé, sous l'en-tête : le toucher y ramène.
    func pinBar(_ e: Engine, _ m: Message) -> some View {
        HStack(spacing: 10) {
            Button { jumpTo = m.id } label: {
                HStack(spacing: 10) {
                    Image(systemName: "pin.fill").foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(e.displayName(m.from)).font(.caption.bold())
                        // Une capsule encore scellée ne se lit pas non plus dans l'épingle.
                        Text(e.readable(m))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Aller au message épinglé")
            Button { e.setPin(chatId, "") } label: {
                Image(systemName: "xmark").font(.caption.bold()).foregroundStyle(.secondary)
            }
            .accessibilityLabel("Désépingler")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.bar)
    }

    // MARK: En-tête

    func subtitle(_ e: Engine, _ chat: Chat) -> String {
        let typing = e.typing[chat.id] ?? []
        if !typing.isEmpty {
            return chat.group ? "\(typing.map(e.displayName).joined(separator: ", ")) écrit…" : "écrit…"
        }
        if chat.group {
            let names = chat.members.filter { $0 != e.me.code }.map(e.displayName).sorted()
            return (names + ["Vous"]).joined(separator: ", ")
        }
        if let p = e.presence[chat.id] {
            if p.online { return "en ligne" }
            if let seen = p.seen { return Dates.lastSeen(seen) }
        }
        return "touchez pour les infos"
    }

    /// Le fond : une photo de la galerie (couvre la zone, avec un léger
    /// voile pour garder les bulles lisibles), sinon une couleur.
    @ViewBuilder func wallpaper(_ e: Engine, _ chat: Chat) -> some View {
        let w = chat.wallpaper.isEmpty ? e.settings.wallpaper : chat.wallpaper
        if w.hasPrefix("data:"), let image = WallpaperCache.image(w) {
            // Exactement la taille de l'écran, la photo recadrée au centre.
            GeometryReader { g in
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: g.size.width, height: g.size.height)
                    .clipped()
                    .overlay(Color.black.opacity(0.08))
            }
            .background(Color.chatBackground)
        } else {
            wallpaperColor(w)
        }
    }

    func wallpaperColor(_ w: String) -> Color {
        switch w {
        case "plain": Color(.systemGroupedBackground)
        case "mint": Color(light: .init(red: 0.85, green: 0.95, blue: 0.9, alpha: 1), dark: .init(red: 0.05, green: 0.16, blue: 0.13, alpha: 1))
        case "sky": Color(light: .init(red: 0.86, green: 0.92, blue: 0.98, alpha: 1), dark: .init(red: 0.06, green: 0.11, blue: 0.18, alpha: 1))
        case "rose": Color(light: .init(red: 0.98, green: 0.89, blue: 0.91, alpha: 1), dark: .init(red: 0.18, green: 0.07, blue: 0.1, alpha: 1))
        case "night": Color(red: 0.07, green: 0.09, blue: 0.14)
        case "sand": Color(light: .init(red: 0.96, green: 0.92, blue: 0.84, alpha: 1), dark: .init(red: 0.16, green: 0.13, blue: 0.09, alpha: 1))
        default: .chatBackground
        }
    }

    // MARK: Le menu d'un message

    @ViewBuilder func menu(_ e: Engine, _ chat: Chat, _ m: Message) -> some View {
        if m.kind != "system", !m.deleted {
            let mine = m.from == e.me.code
            // Une capsule scellée ne se copie ni ne se transfère.
            let sealed = m.sealed(for: e.me.code)
            ControlGroup {
                ForEach(["👍", "❤️", "😂", "😮", "😢", "🙏"], id: \.self) { emoji in
                    Button(emoji) { e.react(chatId, m.id, emoji) }
                }
            }
            Button { reply = m; focused = true } label: { Label("Répondre", systemImage: "arrowshape.turn.up.left") }
            // Dans un groupe, sur le message d'un autre membre : lui répondre
            // dans votre discussion à deux, sa phrase citée (comme le navigateur).
            if chat.group, !mine, m.from != "system", !e.blocked(m.from) {
                Button {
                    model.privateReply = m
                    model.openChat(m.from)
                } label: {
                    Label("Répondre en privé", systemImage: "person.crop.circle")
                }
            }
            if !m.text.isEmpty, !sealed {
                Button { UIPasteboard.general.string = m.text } label: { Label("Copier", systemImage: "doc.on.doc") }
            }
            if !sealed {
                Button { forwarding = m } label: { Label("Transférer", systemImage: "arrowshape.turn.up.right") }
            }
            Button { e.setPin(chatId, chat.pinMsg == m.id ? "" : m.id) } label: {
                Label(chat.pinMsg == m.id ? "Désépingler" : "Épingler", systemImage: chat.pinMsg == m.id ? "pin.slash" : "pin")
            }
            Button { e.toggleStar(chatId, m.id) } label: {
                Label(m.starred ? "Retirer des importants" : "Important", systemImage: m.starred ? "star.slash" : "star")
            }
            if mine, m.kind == "text", !m.isCapsule, nowMs() - m.at < 15 * 60_000 {
                Button { editing = m; text = m.text; reply = nil; focused = true } label: { Label("Modifier", systemImage: "pencil") }
            }
            if mine {
                Button { info = m } label: { Label("Infos", systemImage: "info.circle") }
            }
            if m.tick == .failed, mine {
                Button { Task { await e.flush() } } label: { Label("Réessayer", systemImage: "arrow.clockwise") }
            }
        }
        Button(role: .destructive) { deleting = m } label: { Label("Supprimer", systemImage: "trash") }
    }

    // MARK: La saisie

    @ViewBuilder func composer(_ e: Engine, _ chat: Chat) -> some View {
        if chat.group && (chat.left || !chat.members.contains(e.me.code)) {
            Text("Vous ne faites plus partie de ce groupe.")
                .font(.footnote).foregroundStyle(.secondary).padding().frame(maxWidth: .infinity)
                .background(.bar)
        } else if !chat.group && e.blocked(chatId) {
            Button("Vous avez bloqué ce contact. Toucher pour débloquer.") { e.setBlocked(chatId, false) }
                .font(.footnote).padding().frame(maxWidth: .infinity)
                .background(.bar)
        } else {
            VStack(spacing: 0) {
                if let m = editing ?? reply {
                    HStack {
                        RoundedRectangle(cornerRadius: 2).fill(Color.talkaGreen).frame(width: 4)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(editing != nil ? "Modifier le message" : e.displayName(m.from))
                                .font(.caption.bold()).foregroundStyle(Color.talkaGreen)
                            Text(e.readable(m)).font(.caption).lineLimit(2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Button {
                            if editing != nil { text = "" }
                            editing = nil
                            reply = nil
                        } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary) }
                            .accessibilityLabel("Annuler")
                    }
                    .frame(height: 40)
                    .padding(.horizontal, 10)
                    .frosted(in: .rect(cornerRadius: 12))
                    .padding(.horizontal, 8)
                    .padding(.top, 8)
                }
                HStack(alignment: .bottom, spacing: 8) {
                    if recorder.recording {
                        Button { recorder.cancel() } label: { Image(systemName: "trash").font(.title3).foregroundStyle(.red) }
                            .frame(width: 38, height: 38)
                            .frosted(in: .circle)
                            .accessibilityLabel("Annuler l'enregistrement")
                        TimelineView(.periodic(from: .now, by: 0.5)) { ctx in
                            HStack {
                                Circle().fill(.red).frame(width: 10, height: 10)
                                Text(formatDuration(ctx.date.timeIntervalSince(recorder.started))).monospacedDigit()
                                Spacer()
                            }
                        }
                        .frame(height: 38)
                        // Lisible sur le fond, comme le champ de saisie qu'il remplace.
                        .padding(.horizontal, 12)
                        .frosted(in: .rect(cornerRadius: 20))
                    } else {
                        Menu {
                            Button { pickingPhotos = true } label: { Label("Galerie photos", systemImage: "photo.on.rectangle") }
                            if CameraPicker.available {
                                Button { takingPhoto = true } label: { Label("Appareil photo", systemImage: "camera") }
                            }
                            Button { importingFile = true } label: { Label("Document", systemImage: "doc") }
                            Button { sharingContact = true } label: { Label("Contact", systemImage: "person.crop.circle") }
                            Button { composingCapsule = true } label: { Label("Capsule temporelle", systemImage: "hourglass") }
                        } label: {
                            Image(systemName: "plus").font(.title3.weight(.semibold)).frame(width: 38, height: 38)
                                .frosted(in: .circle)
                        }
                        .accessibilityLabel("Joindre")
                        TextField(chat.brocolis.isEmpty ? "Message" : "Message chiffré par Brocolis 🥦", text: $text, axis: .vertical)
                            .lineLimit(1...6)
                            .focused($focused)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .frosted(in: .rect(cornerRadius: 20))
                            .onChange(of: text) { _, t in
                                if editing == nil { e.setTyping(chatId, !t.isEmpty) }
                            }
                        // La galerie à portée de pouce, comme WhatsApp.
                        if text.isEmpty && editing == nil {
                            Button { pickingPhotos = true } label: {
                                Image(systemName: "photo.on.rectangle").font(.body).frame(width: 38, height: 38)
                                    .frosted(in: .circle)
                            }
                            .accessibilityLabel("Envoyer une photo de la galerie")
                        }
                    }
                    if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && editing == nil {
                        Button {
                            if recorder.recording {
                                let dur = Date().timeIntervalSince(recorder.started)
                                if let data = recorder.stop() {
                                    send(e, Content(kind: "voice", data: data.dataURL("audio/mp4"), mime: "audio/mp4", size: Double(data.count), dur: dur.rounded()))
                                }
                            } else {
                                Task {
                                    if !(await recorder.start()) { model.show("Le micro n'est pas autorisé.") }
                                }
                            }
                        } label: {
                            Image(systemName: recorder.recording ? "arrow.up" : "mic.fill")
                                .font(.headline)
                                .foregroundStyle(.white)
                                .frame(width: 38, height: 38)
                                .background(Color.talkaGreen, in: .circle)
                        }
                        .accessibilityLabel(recorder.recording ? "Envoyer le message vocal" : "Message vocal")
                    } else {
                        Button { submit(e) } label: {
                            Image(systemName: editing != nil ? "checkmark" : "arrow.up")
                                .font(.headline)
                                .foregroundStyle(.white)
                                .frame(width: 38, height: 38)
                                .background(Color.talkaGreen, in: .circle)
                        }
                        .accessibilityLabel("Envoyer")
                    }
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
            }
            // Transparente : la saisie flotte sur le fond de la discussion,
            // seuls le champ et les boutons ont leur verre dépoli.
        }
    }

    func submit(_ e: Engine) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        if let m = editing {
            if t != m.text { e.edit(chatId, m.id, text: t) }
            editing = nil
        } else {
            send(e, Content(text: String(t.prefix(8000))))
        }
        text = ""
        e.updateChat(chatId) { $0.draft = "" }
    }

    func rainBrocolis() {
        guard !reduceMotion else { return }
        brocolis = UUID()
        model.show("🥦 Chiffré au brocoli (et en AES-256-GCM, quand même)")
    }

    func send(_ e: Engine, _ content: Content) {
        var c = content
        if let r = reply {
            let plain = r.kind == "text" && !r.sealed(for: e.me.code)
            c.reply = Reply(id: r.id, from: r.from, text: String((plain ? r.text : Engine.preview(r)).prefix(160)))
            reply = nil
        }
        e.setTyping(chatId, false)
        if e.send(chatId, c) != nil, e.settings.sound { Sounds.sent() }
    }

    func sendPhotos(_ e: Engine, _ items: [PhotosPickerItem]) async {
        for item in items {
            guard let data = try? await item.loadTransferable(type: Data.self), let ui = UIImage(data: data),
                  let (url, w, h) = ui.compressed()
            else { continue }
            send(e, Content(kind: "image", data: url, mime: "image/jpeg", w: w, h: h))
        }
    }

    func sendFile(_ e: Engine, _ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return }
        // 3 Mo : encodé deux fois en base64 (message, puis chiffré), il tient dans
        // la limite de 6 Mo du relais, comme dans le navigateur.
        guard data.count <= 3 * 1024 * 1024 else { return model.show("Fichier trop lourd (3 Mo au plus).") }
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
        send(e, Content(kind: "file", data: data.dataURL(mime), name: url.lastPathComponent, mime: mime, size: Double(data.count)))
    }

    func forward(_ e: Engine, _ m: Message, to id: String) {
        forwarding = nil
        e.ensureDirectChatIfNeeded(id)
        e.forward(m, to: [id])
        model.show("Message transféré")
    }
}

// MARK: Une bulle

struct MessageRow: View {
    let engine: Engine
    let chat: Chat
    let message: Message
    let first: Bool
    let onReply: () -> Void
    let onOpen: () -> Void
    let onScrollTo: (String) -> Void
    @State private var drag: CGFloat = 0
    @State private var showReactions = false

    var body: some View {
        let e = engine
        let m = message
        let mine = m.from == e.me.code
        if m.kind == "system" {
            Text(m.text)
                .font(.caption)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Color(.systemBackground).opacity(0.85), in: .rect(cornerRadius: 8))
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity)
        } else {
            HStack(alignment: .bottom, spacing: 6) {
                if mine { Spacer(minLength: 50) }
                if chat.group, !mine {
                    if first { e.avatar(m.from, size: 28) } else { Color.clear.frame(width: 28, height: 1) }
                }
                VStack(alignment: mine ? .trailing : .leading, spacing: -6) {
                    bubble(e, m, mine)
                    if !m.reactions.isEmpty {
                        let counts = Dictionary(grouping: m.reactions.values, by: { $0 }).map { ($0.key, $0.value.count) }.sorted { $0.1 > $1.1 }
                        HStack(spacing: 2) {
                            ForEach(counts, id: \.0) { emoji, n in
                                Text(n > 1 ? "\(emoji)\(n)" : emoji).font(.caption)
                            }
                        }
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Color(.systemBackground), in: .capsule)
                        .overlay(Capsule().stroke(Color(.separator), lineWidth: 0.5))
                        .padding(.horizontal, 8)
                        .accessibilityLabel("Réactions : \(m.reactions.values.joined(separator: " "))")
                        .accessibilityHint("Voir qui a réagi")
                        .onTapGesture { showReactions = true }
                        .sheet(isPresented: $showReactions) {
                            ReactionsSheet(engine: e, message: m)
                                .presentationDetents([.medium, .large])
                        }
                    }
                }
                .padding(.bottom, m.reactions.isEmpty ? 0 : 6)
                if !mine { Spacer(minLength: 50) }
            }
            .padding(.top, first ? 6 : 0)
            .offset(x: drag)
            .simultaneousGesture(
                DragGesture(minimumDistance: 20)
                    .onChanged { v in if v.translation.width > 0, abs(v.translation.height) < 30 { drag = min(v.translation.width, 70) } }
                    .onEnded { _ in
                        if drag > 55, !m.deleted { onReply() }
                        withAnimation(.spring) { drag = 0 }
                    }
            )
        }
    }

    @ViewBuilder func bubble(_ e: Engine, _ m: Message, _ mine: Bool) -> some View {
        let size = CGFloat(e.settings.fontSize)
        let big = m.kind == "text" && !m.deleted && m.reply == nil && !m.isCapsule && onlyEmoji(m.text)
        VStack(alignment: .leading, spacing: 4) {
            if chat.group, !mine, first {
                Text(e.displayName(m.from)).font(.caption.bold()).foregroundStyle(Avatar.color(m.from))
            }
            if m.fwd { Label("Transféré", systemImage: "arrowshape.turn.up.right").font(.caption.italic()).foregroundStyle(.secondary) }
            if let r = m.reply, !m.deleted {
                Button { onScrollTo(r.id) } label: {
                    HStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 2).fill(Avatar.color(r.from)).frame(width: 4)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(e.displayName(r.from)).font(.caption.bold()).foregroundStyle(Avatar.color(r.from))
                            Text(Brocolis.masked(r.text)).font(.caption).lineLimit(2).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(6)
                    .background(Color.primary.opacity(0.06), in: .rect(cornerRadius: 6))
                }
                .buttonStyle(.plain)
            }
            if m.deleted {
                Label(mine ? "Vous avez supprimé ce message" : "Ce message a été supprimé", systemImage: "nosign")
                    .font(.system(size: size).italic())
                    .foregroundStyle(.secondary)
            } else if m.sealed(for: e.me.code) {
                // La capsule scellée : l'enveloppe et le compte à rebours,
                // puis le message, à l'heure dite.
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    let now = ctx.date.timeIntervalSince1970 * 1000
                    if m.sealed(for: e.me.code, now: now) {
                        SealedCapsule(openAt: m.openAt ?? 0, now: now)
                    } else {
                        CapsuleBand(m: m, now: now)
                        content(e, m, size: size, big: false)
                    }
                }
            } else {
                if m.isCapsule { CapsuleBand(m: m, now: nowMs()) }
                content(e, m, size: size, big: big)
            }
            HStack(spacing: 3) {
                Spacer(minLength: 0)
                if m.starred { Image(systemName: "star.fill").font(.caption2) }
                if m.bro, !m.deleted { Text("🥦").font(.caption2).accessibilityLabel("Chiffré aussi par Brocolis") }
                if m.exp != nil { Image(systemName: "timer").font(.caption2) }
                if m.edited, !m.deleted { Text("modifié").font(.caption2) }
                Text(Dates.clock(m.at)).font(.caption2)
                if mine, !m.deleted { TickView(tick: m.tick) }
            }
            .foregroundStyle(.secondary)
        }
        .padding(.horizontal, big ? 4 : 9)
        .padding(.vertical, 6)
        .frame(maxWidth: m.kind == "image" ? 280 : 520, alignment: .leading)
        .fixedSize(horizontal: false, vertical: true)
        .background(big ? Color.clear : (mine ? Color.bubbleOut : Color.bubbleIn), in: .rect(cornerRadius: 12))
        .shadow(color: .black.opacity(big ? 0 : 0.08), radius: 0.5, y: 1)
    }

    @ViewBuilder func content(_ e: Engine, _ m: Message, size: CGFloat, big: Bool) -> some View {
        switch m.kind {
        case "image":
            VStack(alignment: .leading, spacing: 4) {
                if let ui = UIImage.fromDataURL(m.data) {
                    Button(action: onOpen) {
                        Image(uiImage: ui)
                            .resizable()
                            .aspectRatio(ui.size, contentMode: .fit)
                            .frame(maxWidth: 260, maxHeight: 340)
                            .clipShape(.rect(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Photo")
                } else {
                    Label("Photo", systemImage: "photo")
                }
                if !m.text.isEmpty { Text(richText(m.text)).font(.system(size: size)) }
            }
        case "voice":
            VoiceBubble(message: m)
        case "file":
            FileBubble(message: m)
        case "brokey":
            BrocolisKeyCard(engine: e, message: m)
        case "contact":
            ContactBubble(engine: e, message: m)
        default:
            Text(richText(m.text))
                .font(.system(size: big ? 46 : size))
                .textSelection(.enabled)
        }
    }
}

struct VoiceBubble: View {
    let message: Message
    @State private var player = VoicePlayer()

    var body: some View {
        HStack(spacing: 10) {
            Button { player.toggle(Data(dataURL: message.data), mime: message.mime) } label: {
                Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title2).frame(width: 36, height: 36)
            }
            .accessibilityLabel(player.playing ? "Pause" : "Écouter")
            HStack(spacing: 2) {
                ForEach(0..<24, id: \.self) { i in
                    Capsule().fill(Color.secondary.opacity(0.6))
                        .frame(width: 3, height: CGFloat(6 + (Int(message.id.unicodeScalars.map(\.value).reduce(UInt32(i)) { $0 &* 31 &+ $1 }) % 18)))
                }
            }
            Text(formatDuration(message.dur)).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
            Button { player.nextSpeed() } label: {
                Text(player.speedLabel)
                    .font(.caption.bold().monospacedDigit())
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(Color.primary.opacity(0.08), in: .capsule)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Vitesse de lecture : \(player.speedLabel)")
        }
        .frame(minWidth: 220)
        .onDisappear { player.stop() }
    }
}

struct FileBubble: View {
    let message: Message
    @State private var url: URL?

    var body: some View {
        Button {
            if let data = Data(dataURL: message.data) { url = temporaryFile(data, name: message.name) }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "doc.fill").font(.title).foregroundStyle(Color.talkaGreen)
                VStack(alignment: .leading, spacing: 2) {
                    Text(message.name.isEmpty ? "Fichier" : message.name).font(.subheadline.weight(.medium)).lineLimit(2)
                    Text("\(formatBytes(message.size)) · \((message.name as NSString).pathExtension.uppercased())")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "square.and.arrow.down")
            }
            .padding(8)
            .background(Color.primary.opacity(0.05), in: .rect(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .sheet(item: Binding(get: { url.map(SharedURL.init) }, set: { url = $0?.url })) { shared in
            ActivityView(items: [shared.url]).presentationDetents([.medium, .large])
        }
    }
}

struct SharedURL: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ c: UIActivityViewController, context: Context) {}
}

struct ContactBubble: View {
    @Environment(AppModel.self) private var model
    let engine: Engine
    let message: Message

    var body: some View {
        let code = message.code
        let saved = engine.contact(code)?.saved == true
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Avatar(id: code, name: message.name.isEmpty ? code : message.name, size: 40)
                VStack(alignment: .leading) {
                    Text(message.name.isEmpty ? TalkaCode.pretty(code) : message.name).font(.subheadline.bold())
                    Text(TalkaCode.pretty(code)).font(.caption.monospaced()).foregroundStyle(.secondary)
                }
            }
            if code != engine.me.code {
                Divider()
                Button(saved ? "Écrire" : "Ajouter aux contacts") {
                    if saved {
                        model.openChat(code)
                    } else {
                        Task {
                            _ = try? await engine.addContact(code, name: message.name)
                            model.openChat(code)
                        }
                    }
                }
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity)
            }
        }
        .frame(minWidth: 200)
    }
}

// MARK: Photo en grand, infos d'un message

struct ImageViewer: View {
    @Environment(\.dismiss) private var dismiss
    let engine: Engine
    let message: Message
    @State private var scale: CGFloat = 1

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                if let ui = UIImage.fromDataURL(message.data) {
                    Image(uiImage: ui)
                        .resizable()
                        .scaledToFit()
                        .scaleEffect(scale)
                        .gesture(MagnifyGesture().onChanged { scale = max(1, $0.magnification) }.onEnded { _ in withAnimation { scale = 1 } })
                }
            }
            .toolbarBackground(.black, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .navigationTitle(engine.displayName(message.from))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Fermer") { dismiss() } }
                if let ui = UIImage.fromDataURL(message.data) {
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: Image(uiImage: ui), preview: SharePreview("Photo", image: Image(uiImage: ui)))
                    }
                }
            }
        }
    }
}

struct MessageInfoSheet: View {
    @Environment(\.dismiss) private var dismiss
    let engine: Engine
    let chat: Chat
    let message: Message

    var body: some View {
        NavigationStack {
            let m = engine.store.message(message.chat, message.id) ?? message
            List {
                Section("Envoyé") { Text("\(Dates.separator(m.at)) à \(Dates.clock(m.at))") }
                ForEach(m.to, id: \.self) { code in
                    HStack(spacing: 12) {
                        engine.avatar(code, size: 36)
                        Text(engine.displayName(code))
                        Spacer()
                        TickView(tick: m.readBy.contains(code) ? .read : m.deliveredTo.contains(code) ? .delivered : m.sentTo.contains(code) ? .sent : .pending)
                    }
                }
            }
            .navigationTitle("Infos du message")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Les photos de fond décodées une fois (la discussion se redessine souvent).
/// Quelques-unes à la fois : le fond général (réglages) et celui de chaque
/// discussion ouverte ne se chassent plus l'un l'autre à chaque affichage.
@MainActor
enum WallpaperCache {
    private static var cache: [(key: Int, image: UIImage)] = []
    static let size = 4

    static func image(_ dataURL: String) -> UIImage? {
        let key = dataURL.hashValue
        if let i = cache.firstIndex(where: { $0.key == key }) {
            let hit = cache.remove(at: i)
            cache.append(hit)
            return hit.image
        }
        guard let image = UIImage.fromDataURL(dataURL) else { return nil }
        cache.append((key, image))
        if cache.count > size { cache.removeFirst() }
        return image
    }
}

/// Qui a réagi à un message, avec quoi ; sa propre réaction se retire d'un
/// toucher (comme dans le navigateur).
struct ReactionsSheet: View {
    let engine: Engine
    let message: Message
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let e = engine
        let me = e.me.code
        let list = message.reactions.sorted { a, b in
            a.key == me ? true : b.key == me ? false : e.displayName(a.key) < e.displayName(b.key)
        }
        NavigationStack {
            List(list, id: \.key) { code, emoji in
                let mine = code == me
                Button {
                    guard mine else { return }
                    e.react(message.chat, message.id, emoji)
                    dismiss()
                } label: {
                    HStack(spacing: 12) {
                        e.avatar(code, size: 40)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(mine ? "Vous" : e.displayName(code)).foregroundStyle(.primary)
                            if mine {
                                Text("Touchez pour retirer").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Text(emoji).font(.title2)
                    }
                }
                .disabled(!mine)
            }
            .navigationTitle("Réactions")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } }
            }
        }
    }
}
