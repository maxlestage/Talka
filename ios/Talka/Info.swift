// Les infos d'un contact (nom, code, numéro de sécurité, blocage) et d'un
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
                        Toggle(isOn: Binding(get: { chat?.muted ?? false }, set: { v in e.updateChat(code) { $0.muted = v } })) {
                            Label("Silence", systemImage: "bell.slash")
                        }
                        WallpaperPicker(chatId: code)
                    }
                }
                Section {
                    NavigationLink {
                        SafetyView(code: code)
                    } label: {
                        Label("Chiffrement", systemImage: "lock.fill")
                    }
                } footer: {
                    Text("Les messages et appels sont chiffrés de bout en bout. Touchez pour vérifier le numéro de sécurité.")
                }
                Section {
                    Button(c?.blocked == true ? "Débloquer \(e.displayName(code))" : "Bloquer \(e.displayName(code))", role: c?.blocked == true ? nil : .destructive) {
                        e.setBlocked(code, !(c?.blocked ?? false))
                    }
                    if chat != nil {
                        Button("Vider la discussion", role: .destructive) { e.clearChat(code) }
                        Button("Supprimer la discussion", role: .destructive) { confirmDelete = true }
                    }
                }
            }
            .navigationTitle("Infos du contact")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { name = c?.name ?? "" }
            .onDisappear { save(e) }
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
            Picker(selection: Binding(get: { e.chat(chatId)?.wallpaper ?? "" }, set: { v in e.updateChat(chatId) { $0.wallpaper = v } })) {
                Text("Par défaut").tag("")
                ForEach(wallpapers, id: \.0) { Text($0.1).tag($0.0) }
            } label: {
                Label("Fond d'écran", systemImage: "photo")
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
            let number = TalkaCrypto.safetyNumber(e.me.code, code)
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
    @State private var photo: PhotosPickerItem?
    @State private var adding = false
    @State private var confirmLeave = false

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
                    Section("Nom du groupe") {
                        TextField("Nom du groupe", text: $name)
                            .onSubmit { rename(e, chat) }
                    }
                }
                Section {
                    Picker(selection: Binding(get: { chat.timer }, set: { e.setTimer(chatId, secs: $0) })) {
                        timerOptions()
                    } label: {
                        Label("Messages éphémères", systemImage: "timer")
                    }
                    .disabled(chat.left)
                    Toggle(isOn: Binding(get: { chat.muted }, set: { v in e.updateChat(chatId) { $0.muted = v } })) {
                        Label("Silence", systemImage: "bell.slash")
                    }
                    WallpaperPicker(chatId: chatId)
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
                    Button("Vider la discussion", role: .destructive) { e.clearChat(chatId) }
                }
            }
            .navigationTitle("Infos du groupe")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { name = chat.name }
            .onDisappear { if admin { rename(e, chat) } }
            .onChange(of: photo) { _, item in
                Task {
                    guard let data = try? await item?.loadTransferable(type: Data.self), let ui = UIImage(data: data),
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
