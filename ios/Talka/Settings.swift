// L'onglet Réglages : le profil, le code, la confidentialité, le verrou,
// l'apparence, les notifications, le serveur, la sauvegarde.

import PhotosUI
import SwiftUI
import TalkaKit
import UniformTypeIdentifiers

struct SettingsTab: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var about = ""
    @State private var photo: PhotosPickerItem?
    @State private var takingPhoto = false
    @State private var showCode = false
    @State private var settingPin = false
    @State private var exporting = false
    @State private var importing = false
    @State private var importData: Data?
    @State private var password = ""
    /// La sauvegarde chiffrée, prête à être enregistrée dans Fichiers.
    @State private var backupFile: BackupFile?
    @State private var savingBackup = false
    @State private var server = ""
    @State private var confirmWipe = false

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                Form {
                    profile(e)
                    Section {
                        Toggle("Confirmations de lecture", isOn: setting(e, \.readReceipts))
                        Toggle("Masquer « en ligne »", isOn: setting(e, \.hideOnline))
                        Toggle("Pastille « en ligne »", isOn: setting(e, \.presenceDot))
                        NavigationLink {
                            BlockedView()
                        } label: {
                            LabeledContent("Contacts bloqués", value: "\(e.contacts.values.filter(\.blocked).count)")
                        }
                    } header: {
                        Text("Confidentialité")
                    } footer: {
                        Text("Sans confirmations de lecture, vous ne voyez pas non plus celles des autres. La pastille « en ligne » : un point vert sur l'avatar des contacts connectés, gris sinon.")
                    }
                    Section {
                        NavigationLink {
                            BrocolisKeysView()
                        } label: {
                            LabeledContent("Clés Brocolis 🥦", value: "\(e.brocolisKeys.count)")
                        }
                    } header: {
                        Text("Brocolis 🥦")
                    } footer: {
                        Text("Une seconde couche de chiffrement, avec les clés de l'app Brocolis.")
                    }
                    Section("Verrouillage") {
                        if model.hasPin {
                            if let bio = model.biometryName {
                                Toggle(bio, isOn: Binding(get: { model.faceID }, set: { model.faceID = $0 }))
                            }
                            Picker("Verrouiller", selection: setting(e, \.lockAfter)) {
                                Text("Immédiatement").tag(0)
                                Text("Après 1 minute").tag(60)
                                Text("Après 5 minutes").tag(300)
                                Text("Après 1 heure").tag(3600)
                            }
                            Button("Changer le code PIN") { settingPin = true }
                            Button("Retirer le code PIN", role: .destructive) { model.setPin(nil) }
                        } else {
                            Button("Définir un code PIN") { settingPin = true }
                        }
                    }
                    Section("Apparence") {
                        Picker("Thème", selection: setting(e, \.theme)) {
                            Text("Automatique").tag("auto")
                            Text("Clair").tag("light")
                            Text("Sombre").tag("dark")
                        }
                        WallpaperRows(value: setting(e, \.wallpaper))
                        Stepper("Taille du texte : \(e.settings.fontSize)", value: setting(e, \.fontSize), in: 12...22)
                    }
                    Section {
                        Picker("Conserver les messages", selection: setting(e, \.keepDays)) {
                            Text("24 heures").tag(1)
                            Text("7 jours").tag(7)
                            Text("1 mois").tag(30)
                            Text("3 mois").tag(90)
                            Text("6 mois").tag(180)
                            Text("1 an").tag(365)
                            Text("Toujours").tag(0)
                        }
                    } header: {
                        Text("Discussions")
                    } footer: {
                        Text("Plus vieux, ils s'effacent de cet iPhone, sauf les messages importants ⭐.")
                    }
                    Section {
                        Toggle("Notifications", isOn: Binding(get: { e.settings.notify }, set: { on in
                            if on {
                                Task {
                                    let ok = await Notifier.requestPermission()
                                    e.saveSettings { $0.notify = ok }
                                    if !ok { model.show("Autorisez les notifications de Talka dans les Réglages de l'iPhone.") }
                                }
                            } else {
                                e.saveSettings { $0.notify = false }
                            }
                        }))
                        Toggle("Sons", isOn: setting(e, \.sound))
                    } header: {
                        Text("Notifications")
                    } footer: {
                        Text("Talka prévient quand l'application est ouverte ou en arrière-plan récent : aucun message ne passe par les serveurs d'Apple.")
                    }
                    Section {
                        TextField("Serveur", text: $server)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onSubmit {
                                let s = server.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
                                guard URL(string: s)?.host != nil, s != model.serverString else { return }
                                model.changeServer(s == AppModel.builtInServer ? "" : s)
                            }
                        LabeledContent("État", value: e.netState == .online ? "Connecté" : e.netState == .connecting ? "Connexion…" : "Hors ligne")
                    } header: {
                        Text("Serveur")
                    } footer: {
                        Text("Le serveur ne fait que passer les messages chiffrés. Vos contacts doivent utiliser le même.")
                    }
                    Section {
                        Button { exporting = true } label: { Label("Exporter une sauvegarde", systemImage: "square.and.arrow.up") }
                        Button { importing = true } label: { Label("Restaurer une sauvegarde", systemImage: "square.and.arrow.down") }
                        LabeledContent("Espace utilisé", value: formatBytes(Double(e.store.usage())))
                    } header: {
                        Text("Sauvegarde")
                    } footer: {
                        Text("La sauvegarde est chiffrée avec un mot de passe. Elle se restaure sur un iPhone comme dans un navigateur.")
                    }
                    Section {
                        Button("Tout effacer", role: .destructive) { confirmWipe = true }
                    } footer: {
                        Text("Talka \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · Code ouvert, chiffré de bout en bout.")
                    }
                }
                .navigationTitle("Réglages")
                .onAppear {
                    name = e.profile.name
                    about = e.profile.about
                    server = model.serverString
                }
                .onChange(of: photo) { _, item in
                    guard let item else { return }
                    photo = nil // la même photo pourra être rechoisie
                    Task {
                        guard let data = try? await item.loadTransferable(type: Data.self), let ui = UIImage(data: data),
                              let url = ui.avatarDataURL()
                        else { return }
                        e.saveProfile { $0.avatar = url }
                    }
                }
                .sheet(isPresented: $showCode) { MyCodeSheet() }
                .fullScreenCover(isPresented: $takingPhoto) {
                    CameraPicker { image in
                        takingPhoto = false
                        if let url = image?.avatarDataURL() { e.saveProfile { $0.avatar = url } }
                    }
                    .ignoresSafeArea()
                }
                .sheet(isPresented: $settingPin) { PinSetup() }
                // « Enregistrer dans Fichiers » : iCloud Drive, Sur mon iPhone…
                .fileExporter(isPresented: $savingBackup, document: backupFile, contentType: .data,
                              defaultFilename: model.backupFilename) { result in
                    backupFile = nil
                    switch result {
                    case .success: model.show("Sauvegarde enregistrée ✓")
                    case .failure(let error): model.show("Sauvegarde non enregistrée : \(error.localizedDescription)")
                    }
                }
                .alert("Mot de passe de la sauvegarde", isPresented: $exporting) {
                    SecureField("Au moins 8 caractères", text: $password)
                    Button("Exporter") {
                        defer { password = "" }
                        guard password.count >= 8 else { return model.show("Mot de passe trop court (8 caractères au moins).") }
                        guard let data = model.engine?.exportBackup(password: password) else {
                            return model.show("La sauvegarde n'a pas pu être créée.")
                        }
                        backupFile = BackupFile(data: data)
                        // Après la fermeture de l'alerte : présentée pendant,
                        // la fenêtre d'enregistrement ne s'ouvrait pas.
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { savingBackup = true }
                    }
                    Button("Annuler", role: .cancel) { password = "" }
                } message: {
                    Text("Il sera demandé pour restaurer. Sans lui, la sauvegarde est illisible.")
                }
                // Tous les fichiers : « .talka », inconnu du système, était grisé
                // avec [.data, .json]. Le contenu est vérifié à la restauration.
                .fileImporter(isPresented: $importing, allowedContentTypes: [.item]) { result in
                    guard let url = try? result.get() else { return }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    importData = try? Data(contentsOf: url)
                }
                .alert("Restaurer la sauvegarde ?", isPresented: Binding(get: { importData != nil }, set: { if !$0 { importData = nil } })) {
                    SecureField("Mot de passe", text: $password)
                    Button("Restaurer", role: .destructive) {
                        guard let data = importData else { return }
                        defer { password = "" }
                        do {
                            try model.restore(data, password: password)
                            model.show("Sauvegarde restaurée ✓")
                        } catch {
                            model.show(error.localizedDescription)
                        }
                    }
                    Button("Annuler", role: .cancel) { password = "" }
                } message: {
                    Text("Les données de cet iPhone seront remplacées par celles de la sauvegarde.")
                }
                .confirmationDialog("Tout effacer ?", isPresented: $confirmWipe, titleVisibility: .visible) {
                    Button("Effacer le code, les contacts et les messages", role: .destructive) { model.wipe() }
                } message: {
                    Text("Votre code sera perdu pour toujours, sauf si vous avez une sauvegarde.")
                }
            }
        }
    }

    @ViewBuilder func profile(_ e: Engine) -> some View {
        Section {
            HStack(spacing: 14) {
                PhotosPicker(selection: $photo, matching: .images) {
                    e.avatar(e.me.code, size: 64)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("Changer de photo")
                VStack(alignment: .leading, spacing: 6) {
                    TextField("Votre nom", text: $name)
                        .font(.title3.weight(.semibold))
                        .onSubmit { saveProfile(e) }
                    TextField("Infos (ex. Disponible)", text: $about)
                        .foregroundStyle(.secondary)
                        .onSubmit { saveProfile(e) }
                }
            }
            PhotosPicker(selection: $photo, matching: .images) {
                Label(e.profile.avatar.isEmpty ? "Ajouter une photo de la galerie" : "Changer de photo (galerie)", systemImage: "photo.on.rectangle")
            }
            if CameraPicker.available {
                Button { takingPhoto = true } label: { Label("Prendre une photo", systemImage: "camera") }
            }
            if !e.profile.avatar.isEmpty {
                Button("Retirer la photo", role: .destructive) { e.saveProfile { $0.avatar = "" } }
            }
            Button { showCode = true } label: {
                HStack {
                    VStack(alignment: .leading) {
                        Text("Mon code").foregroundStyle(.primary)
                        Text(TalkaCode.pretty(e.me.code)).font(.title3.monospaced().bold()).foregroundStyle(Color.talkaDark)
                    }
                    Spacer()
                    Image(systemName: "qrcode").font(.title)
                }
            }
        }
        .onDisappear { saveProfile(e) }
    }

    func saveProfile(_ e: Engine) {
        let n = String(name.trimmingCharacters(in: .whitespaces).prefix(40))
        let a = String(about.trimmingCharacters(in: .whitespaces).prefix(140))
        guard !n.isEmpty, n != e.profile.name || a != e.profile.about else { return }
        e.saveProfile {
            $0.name = n
            $0.about = a
        }
    }

    func setting<T>(_ e: Engine, _ key: WritableKeyPath<TalkaKit.Settings, T>) -> Binding<T> {
        Binding(get: { e.settings[keyPath: key] }, set: { v in e.saveSettings { $0[keyPath: key] = v } })
    }
}

struct BlockedView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if let e = model.engine {
            let list = e.contacts.values.filter(\.blocked).sorted { e.displayName($0.code) < e.displayName($1.code) }
            List(list) { c in
                HStack(spacing: 12) {
                    e.avatar(c.code, size: 36)
                    Text(e.displayName(c.code))
                    Spacer()
                    Button("Débloquer") { e.setBlocked(c.code, false) }.buttonStyle(.bordered)
                }
            }
            .overlay { if list.isEmpty { ContentUnavailableView("Aucun contact bloqué", systemImage: "hand.raised") } }
            .navigationTitle("Contacts bloqués")
        }
    }
}

/// Choisir un code PIN (deux fois).
struct PinSetup: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var first: String?
    @State private var mismatch = false

    var body: some View {
        VStack(spacing: 24) {
            HStack {
                Button("Annuler") { dismiss() }
                Spacer()
            }
            Spacer()
            Text(first == nil ? "Choisissez un code PIN" : "Confirmez le code PIN").font(.title2.bold())
            Text(mismatch ? "Les codes ne correspondent pas." : "4 à 6 chiffres, demandés à l'ouverture de Talka.")
                .foregroundStyle(mismatch ? .red : .secondary)
            PinDots(count: pin.count)
            PinPad(pin: $pin) {
                if let f = first {
                    if f == pin {
                        model.setPin(pin)
                        model.show("Code PIN activé")
                        dismiss()
                    } else {
                        mismatch = true
                        first = nil
                    }
                } else {
                    first = pin
                    mismatch = false
                }
                pin = ""
            }
            Spacer()
        }
        .padding()
    }
}

/// Une sauvegarde chiffrée, pour la fenêtre « Enregistrer dans Fichiers ».
struct BackupFile: FileDocument {
    static var readableContentTypes: [UTType] { [.data, .json] }
    var data: Data

    init(data: Data) { self.data = data }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
