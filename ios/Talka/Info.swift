// Les infos d'un contact (nom, code, numéro de sécurité, protection de la
// discussion — session à double cliquet ou ancien chiffrement —, blocage) et d'un
// groupe (membres, admins, photo, nom).

import PhotosUI
import SwiftUI
import TalkaKit

struct ContactInfoView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let code: String
    @State private var name = ""
    @State private var confirmDelete = false
    @State private var confirmClear = false
    @State private var showStarred = false
    /// La protection de la discussion : session à double cliquet (v3) ou
    /// ancien chiffrement, comme dans le navigateur.
    @State private var protection: V3.Protection?

    var body: some View {
        if let e = model.engine {
            let c = e.contact(code)
            let chat = e.chat(code)
            Form {
                Section {
                    VStack(spacing: 10) {
                        e.avatar(code, size: 110)
                        Text(e.displayName(code)).font(.title2.bold())
                        Text(TalkaCode.pretty(code)).font(.body.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
                        if let p = e.presence[code] {
                            Text(p.online ? "en ligne" : p.seen.map(Dates.lastSeen) ?? "").font(.caption).foregroundStyle(.secondary)
                        }
                        HStack(spacing: 12) {
                            action("Audio", "phone.fill") { Task { await model.startCall(code, video: false) } }
                            action("Vidéo", "video.fill") { Task { await model.startCall(code, video: true) } }
                            ShareLink(item: model.inviteLink(code)) {
                                VStack(spacing: 4) {
                                    Image(systemName: "square.and.arrow.up").font(.title3)
                                    Text("Partager").font(.caption)
                                }
                                .frame(maxWidth: .infinity, minHeight: 56)
                                .background(Color(.tertiarySystemGroupedBackground), in: .rect(cornerRadius: 10))
                            }
                        }
                        .disabled(c?.blocked == true)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
                if let about = c?.about, !about.isEmpty {
                    Section("Infos") { Text(about) }
                }
                Section {
                    TextField("Nom du contact", text: $name)
                        .onSubmit { save(e) }
                    if let p = c?.profileName, !p.isEmpty {
                        LabeledContent("Son nom de profil", value: p)
                    }
                } header: {
                    Text("Nom")
                } footer: {
                    Text("Visible seulement par vous.")
                }
                if chat != nil {
                    Section {
                        Picker(selection: Binding(get: { chat?.timer ?? 0 }, set: { e.setTimer(code, secs: $0) })) {
                            timerOptions()
                        } label: {
                            Label("Messages éphémères", systemImage: "timer")
                        }
                        if chat != nil { MuteMenu(chatId: code) }
                        WallpaperPicker(chatId: code)
                        StarredRow(engine: e, chatId: code, shown: $showStarred)
                    }
                }
                // Les groupes en commun, comme WhatsApp (et le navigateur).
                let common = e.groupsInCommon(code)
                if !common.isEmpty {
                    Section("Groupes en commun (\(common.count))") {
                        ForEach(common) { g in
                            Button { model.openChat(g.id) } label: {
                                HStack(spacing: 12) {
                                    e.avatar(g.id, size: 36)
                                    Text(e.title(g)).foregroundStyle(.primary)
                                }
                            }
                        }
                    }
                }
                Section {
                    NavigationLink {
                        SafetyView(code: code)
                    } label: {
                        Label("Chiffrement", systemImage: "lock.fill")
                    }
                    // Avant iOS 26 (pas de ML-KEM), les sessions sont
                    // classiques : `protection` le dit.
                    if let protection {
                        Text(protection.label)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                } footer: {
                    Text("Les messages et appels sont chiffrés de bout en bout. Touchez pour vérifier le numéro de sécurité.")
                }
                Section {
                    Button(c?.blocked == true ? "Débloquer \(e.displayName(code))" : "Bloquer \(e.displayName(code))", role: c?.blocked == true ? nil : .destructive) {
                        e.setBlocked(code, !(c?.blocked ?? false))
                    }
                    if chat != nil {
                        Button("Vider la discussion", role: .destructive) { confirmClear = true }
                        Button("Supprimer la discussion", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle("Infos du contact")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { name = c?.name ?? "" }
            .onDisappear { save(e) }
            .task(id: code) { protection = await e.protection(code) }
            .clearChatDialog(isPresented: $confirmClear, engine: e, chatId: code)
            .sheet(isPresented: $showStarred) { StarredSheet(chatId: code) }
            .confirmationDialog("Supprimer la discussion ?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Supprimer", role: .destructive) {
                    e.deleteChat(code)
                    model.path = []
                }
            }
        }
    }

    func save(_ e: Engine) {
        let n = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        if n != e.contact(code)?.name ?? "" { e.updateContact(code) { $0.name = n } }
    }

    func action(_ label: String, _ icon: String, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3)
                Text(label).font(.caption)
            }
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Color(.tertiarySystemGroupedBackground), in: .rect(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .foregroundStyle(Color.talkaGreen)
    }
}

/// « jusqu'à 18:30 », « jusqu'à demain 09:00 », « Toujours », « Non ».
func muteState(_ chat: Chat?) -> String {
    guard let chat, chat.muted else { return "Non" }
    guard let until = chat.muteUntil else { return "Toujours" }
    let day = Dates.when(until)
    return "Jusqu'à " + (day == "aujourd'hui" ? "" : day + " ") + Dates.clock(until)
}

/// La sourdine, comme WhatsApp : 8 heures, une semaine ou toujours ; elle
/// se lève d'elle-même à l'échéance. Le même choix que dans le navigateur.
struct MuteMenu: View {
    @Environment(AppModel.self) private var model
    let chatId: String

    var body: some View {
        if let e = model.engine {
            let chat = e.chat(chatId)
            Menu {
                MuteChoices(engine: e, chatId: chatId, muted: chat?.muted ?? false)
            } label: {
                HStack {
                    Label("Silence", systemImage: "bell.slash").foregroundStyle(.primary)
                    Spacer()
                    Text(muteState(chat)).foregroundStyle(.secondary)
                }
            }
        }
    }
}

/// Les choix de la sourdine (menus et dialogues).
struct MuteChoices: View {
    let engine: Engine
    let chatId: String
    let muted: Bool

    var body: some View {
        if muted {
            Button { engine.mute(chatId, seconds: 0) } label: { Label("Réactiver le son", systemImage: "bell") }
        } else {
            Button("8 heures") { engine.mute(chatId, seconds: 8 * 3600) }
            Button("1 semaine") { engine.mute(chatId, seconds: 7 * 86400) }
            Button("Toujours") { engine.mute(chatId, seconds: nil) }
        }
    }
}

@ViewBuilder func timerOptions() -> some View {
    Text("Désactivés").tag(0)
    Text("24 heures").tag(86400)
    Text("7 jours").tag(7 * 86400)
    Text("90 jours").tag(90 * 86400)
}

struct WallpaperPicker: View {
    @Environment(AppModel.self) private var model
    let chatId: String

    var body: some View {
        if let e = model.engine {
            WallpaperRows(value: Binding(get: { e.chat(chatId)?.wallpaper ?? "" }, set: { v in e.updateChat(chatId) { $0.wallpaper = v } }),
                          allowDefault: true)
        }
    }
}

/// Le choix d'un fond d'écran : un des fonds de Talka, ou une photo de la
/// galerie (réduite, gardée sur l'appareil en adresse `data:`, jamais
/// envoyée) — la même valeur que dans le navigateur.
struct WallpaperRows: View {
    @Binding var value: String
    var allowDefault = false
    @State private var photo: PhotosPickerItem?
    @State private var pickingPhoto = false

    var isPhoto: Bool { value.hasPrefix("data:") }

    var current: String {
        if isPhoto { return "Ma photo" }
        if value.isEmpty { return allowDefault ? "Par défaut" : "Talka" }
        return wallpapers.first { $0.0 == value }?.1 ?? "Talka"
    }

    /// Une seule ligne : les fonds de Talka, et la photo de la galerie
    /// (choisir, ou changer de photo) dans le même menu.
    var body: some View {
        Menu {
            Picker(selection: Binding(get: { isPhoto ? "photo" : value }, set: { if $0 != "photo" { value = $0 } })) {
                if allowDefault { Text("Par défaut").tag("") }
                ForEach(wallpapers, id: \.0) { Text($0.1).tag($0.0) }
                if isPhoto { Text("Ma photo").tag("photo") }
            } label: {
                EmptyView()
            }
            .pickerStyle(.inline)
            Divider()
            Button { pickingPhoto = true } label: {
                Label(isPhoto ? "Changer de photo…" : "Une photo de la galerie…", systemImage: "photo.on.rectangle")
            }
        } label: {
            HStack(spacing: 10) {
                Label("Fond d'écran", systemImage: "paintpalette")
                    .foregroundStyle(.primary)
                Spacer(minLength: 8)
                if isPhoto, let image = WallpaperCache.image(value) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 26, height: 26)
                        .clipShape(.rect(cornerRadius: 6))
                        .accessibilityHidden(true)
                }
                Text(current).foregroundStyle(.secondary)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityLabel("Fond d'écran : \(current)")
        .photosPicker(isPresented: $pickingPhoto, selection: $photo, matching: .images)
        .onChange(of: photo) { _, item in
            guard let item else { return }
            photo = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self), let ui = UIImage(data: data),
                      let (url, _, _) = ui.compressed(max: 1280, quality: 0.78)
                else { return }
                value = url
            }
        }
    }
}

let wallpapers = [("doodle", "Talka"), ("plain", "Uni"), ("mint", "Menthe"), ("sky", "Ciel"), ("rose", "Rose"), ("night", "Nuit"), ("sand", "Sable")]

/// Le numéro de sécurité : le même sur les deux appareils.
struct SafetyView: View {
    @Environment(AppModel.self) private var model
    let code: String

    var body: some View {
        if let e = model.engine {
            let peer = e.contact(code)
            let number = TalkaCrypto.safetyNumber((e.me.code, e.me.sign, e.me.dh),
                                                  (code, peer?.sign ?? "", peer?.dh ?? ""))
            ScrollView {
                VStack(spacing: 20) {
                    Image(systemName: "lock.shield.fill").font(.system(size: 56)).foregroundStyle(Color.talkaGreen)
                    Text("Comparez ce numéro avec celui affiché sur l'appareil de \(e.displayName(code)). S'ils sont identiques, personne ne s'interpose entre vous.")
                        .multilineTextAlignment(.center)
                    let groups = number.split(separator: " ").map(String.init)
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 12) {
                        ForEach(groups.indices, id: \.self) { i in
                            Text(groups[i]).font(.title3.monospaced())
                        }
                    }
                    .padding()
                    .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: 12))
                    if let qr = QR.image("talka-safety:\(number)") {
                        Image(uiImage: qr).interpolation(.none).resizable().scaledToFit().frame(width: 180)
                            .padding(10).background(.white, in: .rect(cornerRadius: 12))
                    }
                    Button {
                        UIPasteboard.general.string = number
                        model.show("Numéro copié")
                    } label: { Label("Copier", systemImage: "doc.on.doc") }
                }
                .padding(24)
            }
            .navigationTitle("Numéro de sécurité")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

// MARK: Groupe

struct GroupInfoView: View {
    @Environment(AppModel.self) private var model
    let chatId: String
    @State private var name = ""
    @State private var desc = ""
    @State private var photo: PhotosPickerItem?
    @State private var adding = false
    @State private var confirmLeave = false
    @State private var confirmClear = false
    @State private var showStarred = false

    var body: some View {
        if let e = model.engine, let chat = e.chat(chatId) {
            let admin = chat.admins.contains(e.me.code) && !chat.left
            Form {
                Section {
                    VStack(spacing: 10) {
                        if admin {
                            PhotosPicker(selection: $photo, matching: .images) { e.avatar(chatId, size: 110) }
                                .accessibilityLabel("Changer la photo du groupe")
                        } else {
                            e.avatar(chatId, size: 110)
                        }
                        Text(e.title(chat)).font(.title2.bold())
                        Text("Groupe · \(chat.members.count) membres").foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
                if admin {
                    Section {
                        PhotosPicker(selection: $photo, matching: .images) {
                            Label("Photo du groupe (galerie)", systemImage: "photo.on.rectangle")
                        }
                    }
                    Section("Nom du groupe") {
                        TextField("Nom du groupe", text: $name)
                            .onSubmit { rename(e, chat) }
                    }
                }
                descriptionSection(e, chat, admin: admin)
                if admin {
                    Section {
                        Toggle("Seuls les admins peuvent écrire", isOn: Binding(get: { chat.adminsOnly }, set: { on in
                            let note = on ? "Vous avez réservé l'envoi de messages aux admins." : "Vous avez permis à tous les membres d'écrire."
                            e.updateGroup(chatId, note: note) { $0.adminsOnly = on }
                        }))
                    } footer: {
                        Text("Les autres membres lisent sans pouvoir répondre.")
                    }
                }
                Section {
                    Picker(selection: Binding(get: { chat.timer }, set: { e.setTimer(chatId, secs: $0) })) {
                        timerOptions()
                    } label: {
                        Label("Messages éphémères", systemImage: "timer")
                    }
                    .disabled(chat.left)
                    MuteMenu(chatId: chatId)
                    WallpaperPicker(chatId: chatId)
                    StarredRow(engine: e, chatId: chatId, shown: $showStarred)
                }
                Section("\(chat.members.count) membres") {
                    if admin {
                        Button { adding = true } label: { Label("Ajouter des membres", systemImage: "person.badge.plus") }
                    }
                    ForEach(chat.members.sorted { a, b in a == e.me.code || (b != e.me.code && e.displayName(a) < e.displayName(b)) }, id: \.self) { code in
                        HStack(spacing: 12) {
                            e.avatar(code, size: 38)
                            VStack(alignment: .leading) {
                                Text(code == e.me.code ? "Vous" : e.displayName(code))
                                Text(TalkaCode.pretty(code)).font(.caption.monospaced()).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if chat.admins.contains(code) {
                                Text("Admin").font(.caption.bold()).foregroundStyle(Color.talkaGreen)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(Color.talkaGreen.opacity(0.12), in: .capsule)
                            }
                        }
                        .contextMenu {
                            if code != e.me.code {
                                Button { model.openChat(code) } label: { Label("Écrire", systemImage: "message") }
                                if e.contact(code)?.saved != true {
                                    Button {
                                        Task { _ = try? await e.addContact(code, name: "") }
                                    } label: { Label("Ajouter aux contacts", systemImage: "person.badge.plus") }
                                }
                                if admin {
                                    if !chat.admins.contains(code) {
                                        Button {
                                            e.updateGroup(chatId, note: "Vous avez nommé \(e.displayName(code)) admin.") { $0.admins.append(code) }
                                        } label: { Label("Nommer admin", systemImage: "star") }
                                    }
                                    Button(role: .destructive) {
                                        e.updateGroup(chatId, note: "Vous avez retiré \(e.displayName(code)).") { c in
                                            c.members.removeAll { $0 == code }
                                            c.admins.removeAll { $0 == code }
                                        }
                                    } label: { Label("Retirer du groupe", systemImage: "person.badge.minus") }
                                }
                            }
                        }
                    }
                }
                Section {
                    if !chat.left {
                        Button("Quitter le groupe", role: .destructive) { confirmLeave = true }
                    } else {
                        Button("Supprimer le groupe", role: .destructive) {
                            e.deleteChat(chatId)
                            model.path = []
                        }
                    }
                    Button("Vider la discussion", role: .destructive) { confirmClear = true }
                }
            }
            .clearChatDialog(isPresented: $confirmClear, engine: e, chatId: chatId)
            .sheet(isPresented: $showStarred) { StarredSheet(chatId: chatId) }
            .navigationTitle("Infos du groupe")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                name = chat.name
                desc = chat.desc
            }
            .onDisappear {
                if admin {
                    rename(e, chat)
                    saveDesc(e)
                }
            }
            .onChange(of: photo) { _, item in
                guard let item else { return }
                photo = nil // la même photo pourra être rechoisie
                Task {
                    guard let data = try? await item.loadTransferable(type: Data.self), let ui = UIImage(data: data),
                          let url = ui.avatarDataURL()
                    else { return }
                    e.updateGroup(chatId, note: "Vous avez changé la photo du groupe.") { $0.avatar = url }
                }
            }
            .sheet(isPresented: $adding) {
                AddMembersSheet(chatId: chatId)
            }
            .confirmationDialog("Quitter « \(e.title(chat)) » ?", isPresented: $confirmLeave, titleVisibility: .visible) {
                Button("Quitter le groupe", role: .destructive) { e.leaveGroup(chatId) }
            }
        }
    }

    func rename(_ e: Engine, _ chat: Chat) {
        let n = String(name.trimmingCharacters(in: .whitespaces).prefix(60))
        guard !n.isEmpty, n != e.chat(chatId)?.name else { return }
        e.updateGroup(chatId, note: "Vous avez renommé le groupe en « \(n) ».") { $0.name = n }
    }

    /// La description : les admins l'écrivent, tous les membres la lisent.
    @ViewBuilder func descriptionSection(_ e: Engine, _ chat: Chat, admin: Bool) -> some View {
        if admin {
            Section {
                TextField("Ajouter une description", text: $desc, axis: .vertical)
                    .lineLimit(1...6)
                Button("Enregistrer la description") { saveDesc(e) }
                    .disabled(groupDesc(desc) == chat.desc)
            } header: {
                Text("Description")
            } footer: {
                Text("Visible par tous les membres.")
            }
        } else if !chat.desc.isEmpty {
            Section("Description") {
                Text(chat.desc).textSelection(.enabled)
            }
        }
    }

    func saveDesc(_ e: Engine) {
        let d = groupDesc(desc)
        guard let chat = e.chat(chatId), d != chat.desc else { return }
        let note = d.isEmpty ? "Vous avez supprimé la description du groupe." : "Vous avez changé la description du groupe."
        e.updateGroup(chatId, note: note) { $0.desc = d }
    }
}

struct AddMembersSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let chatId: String
    @State private var picked: Set<String> = []

    var body: some View {
        NavigationStack {
            if let e = model.engine, let chat = e.chat(chatId) {
                let list = e.contacts.values.filter { $0.saved && !$0.blocked && !chat.members.contains($0.code) }
                    .sorted { e.displayName($0.code) < e.displayName($1.code) }
                List(list) { c in
                    Button {
                        if picked.contains(c.code) { picked.remove(c.code) } else { picked.insert(c.code) }
                    } label: {
                        HStack(spacing: 12) {
                            e.avatar(c.code, size: 36)
                            Text(e.displayName(c.code)).foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: picked.contains(c.code) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(picked.contains(c.code) ? Color.talkaGreen : .secondary)
                        }
                    }
                }
                .overlay { if list.isEmpty { ContentUnavailableView("Tous vos contacts sont déjà dans le groupe", systemImage: "person.3") } }
                .navigationTitle("Ajouter des membres")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Ajouter") {
                            let codes = Array(picked)
                            let names = codes.map(e.displayName).joined(separator: ", ")
                            e.updateGroup(chatId, note: "Vous avez ajouté \(names).") { $0.members += codes }
                            dismiss()
                        }
                        .disabled(picked.isEmpty)
                    }
                }
            }
        }
    }
}

/// « Vider la discussion ? » : avec des messages importants, on peut les garder (comme
/// WhatsApp).
extension View {
    func clearChatDialog(isPresented: Binding<Bool>, engine e: Engine, chatId: String) -> some View {
        confirmationDialog("Vider la discussion ?", isPresented: isPresented, titleVisibility: .visible) {
            if e.hasStarred(chatId) {
                Button("Vider, garder les importants", role: .destructive) { e.clearChat(chatId, keepStarred: true) }
                Button("Tout vider", role: .destructive) { e.clearChat(chatId) }
            } else {
                Button("Vider", role: .destructive) { e.clearChat(chatId) }
            }
        } message: {
            Text("Les messages seront effacés de vos appareils.")
        }
    }
}

/// « Messages importants (n) » dans les infos d'un contact ou d'un groupe,
/// s'il y en a (comme WhatsApp et le navigateur).
struct StarredRow: View {
    let engine: Engine
    let chatId: String
    @Binding var shown: Bool

    var body: some View {
        let n = engine.starred(in: chatId).count
        if n > 0 {
            Button { shown = true } label: {
                LabeledContent {
                    Text("\(n)")
                } label: {
                    Label("Messages importants", systemImage: "star")
                }
            }
            .foregroundStyle(.primary)
        }
    }
}

/// Le menu ⋮ d'une discussion : les mêmes choix, dans le même ordre, que
/// celui du navigateur (`chat_menu`), comme WhatsApp.
struct ChatMoreMenu: View {
    @Environment(AppModel.self) private var model
    let chatId: String
    @Binding var search: String?
    var searchFocused: FocusState<Bool>.Binding
    @Binding var showInfo: Bool
    @Binding var exported: URL?
    @Binding var choosingBrocolis: Bool
    @State private var choosingWallpaper = false
    @State private var confirmClear = false
    @State private var confirmDelete = false
    @State private var addingContact = false
    @State private var contactName = ""

    var body: some View {
        if let e = model.engine, let chat = e.chat(chatId) {
            Menu {
                Button { showInfo = true } label: {
                    Label(chat.group ? "Infos du groupe" : "Infos du contact", systemImage: "info.circle")
                }
                Button {
                    withAnimation { search = search == nil ? "" : nil }
                    searchFocused.wrappedValue = search != nil
                } label: {
                    Label(search == nil ? "Rechercher" : "Fermer la recherche", systemImage: "magnifyingglass")
                }
                Picker(selection: Binding(get: { chat.timer }, set: { e.setTimer(chatId, secs: $0) })) {
                    timerOptions()
                } label: {
                    Label("Messages éphémères", systemImage: "timer")
                }
                .pickerStyle(.menu)
                Button { export(e) } label: {
                    Label("Exporter la discussion", systemImage: "square.and.arrow.up")
                }
                Button { choosingBrocolis = true } label: {
                    Label(chat.brocolis.isEmpty ? "Chiffrer avec Brocolis 🥦" : "Brocolis 🥦 : \(e.chatBrocolis(chat).map(\.name).joined(separator: ", "))",
                          systemImage: "lock.shield")
                }
                if chat.muted {
                    Button { e.mute(chatId, seconds: 0) } label: { Label("Réactiver les notifications", systemImage: "bell") }
                } else {
                    Menu {
                        MuteChoices(engine: e, chatId: chatId, muted: false)
                    } label: {
                        Label("Mettre en sourdine", systemImage: "bell.slash")
                    }
                }
                Button { choosingWallpaper = true } label: { Label("Fond d'écran", systemImage: "paintpalette") }
                // Quelqu'un qui nous a écrit sans être dans nos contacts.
                if !chat.group, e.contact(chatId)?.saved != true {
                    Button {
                        contactName = e.contact(chatId)?.profileName ?? ""
                        addingContact = true
                    } label: {
                        Label("Ajouter aux contacts", systemImage: "person.badge.plus")
                    }
                }
                if !chat.group {
                    if e.blocked(chatId) {
                        Button { e.setBlocked(chatId, false) } label: { Label("Débloquer", systemImage: "hand.raised.slash") }
                    } else {
                        Button(role: .destructive) { e.setBlocked(chatId, true) } label: { Label("Bloquer", systemImage: "hand.raised") }
                    }
                }
                Button(role: .destructive) { confirmClear = true } label: { Label("Vider la discussion", systemImage: "trash") }
                Button(role: .destructive) { confirmDelete = true } label: {
                    Label(chat.group ? "Quitter et supprimer" : "Supprimer la discussion", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .rotationEffect(.degrees(90))
            }
            .accessibilityLabel("Plus d'options")
            .clearChatDialog(isPresented: $confirmClear, engine: e, chatId: chatId)
            .alert("Ajouter aux contacts", isPresented: $addingContact) {
                TextField("Nom (facultatif)", text: $contactName)
                Button("Annuler", role: .cancel) {}
                Button("Ajouter") {
                    let name = contactName
                    Task { _ = try? await e.addContact(chatId, name: name) }
                }
            } message: {
                Text(TalkaCode.pretty(chatId))
            }
            .confirmationDialog("Supprimer la discussion ?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Supprimer", role: .destructive) {
                    if chat.group, !chat.left { e.leaveGroup(chatId) }
                    e.deleteChat(chatId)
                    model.path = []
                }
            } message: {
                Text("Elle disparaîtra de cet appareil avec tous ses messages.")
            }
            .sheet(isPresented: $choosingWallpaper) {
                NavigationStack {
                    Form { WallpaperPicker(chatId: chatId) }
                        .navigationTitle("Fond d'écran")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { choosingWallpaper = false } } }
                }
                .presentationDetents([.medium])
            }
        }
    }

    func export(_ e: Engine) {
        guard let export = e.exportChat(chatId) else { return }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(export.filename)
        if (try? Data(export.text.utf8).write(to: url)) != nil { exported = url }
    }
}
