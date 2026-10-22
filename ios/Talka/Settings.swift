// L'onglet Réglages : le profil, le code, la confidentialité, le verrou,
// l'apparence, les notifications, la sauvegarde.

import PhotosUI
import LocalAuthentication
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
    @State private var confirmWipe = false
    @State private var confirmRemovePin = false

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
                    security(e)
                    devices(e)
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
                        Toggle("Entrée pour envoyer", isOn: setting(e, \.enterSends))
                    } header: {
                        Text("Discussions")
                    } footer: {
                        Text("Plus vieux, ils s'effacent de cet appareil, sauf les messages importants ⭐. Entrée pour envoyer : avec un clavier (iPad, Mac), Maj + Entrée va à la ligne.")
                    }
                    Section {
                        Toggle("Notifications", isOn: Binding(get: { e.settings.notify }, set: { on in
                            if on {
                                Task {
                                    let ok = await Notifier.requestPermission()
                                    e.saveSettings { $0.notify = ok }
                                    if !ok { model.show("Autorisez les notifications de Talka dans les Réglages de l'appareil.") }
                                }
                            } else {
                                e.saveSettings { $0.notify = false }
                            }
                        }))
                        Picker("Aperçu", selection: setting(e, \.notifyPreview)) {
                            Text("Nom et message").tag("all")
                            Text("Nom seulement").tag("name")
                            Text("Rien (« Nouveau message »)").tag("none")
                        }
                        Toggle("Sons", isOn: setting(e, \.sound))
                    } header: {
                        Text("Notifications")
                    } footer: {
                        Text("Talka prévient quand l'application est ouverte ou en arrière-plan récent : aucun message ne passe par les serveurs d'Apple. L'aperçu choisit ce que montre l'écran verrouillé.")
                    }
                    Section {
                        Button { exporting = true } label: { Label("Exporter une sauvegarde", systemImage: "square.and.arrow.up") }
                        Button { importing = true } label: { Label("Restaurer une sauvegarde", systemImage: "square.and.arrow.down") }
                        LabeledContent("Espace utilisé", value: formatBytes(Double(e.store.usage())))
                    } header: {
                        Text("Sauvegarde")
                    } footer: {
                        Text("La sauvegarde est chiffrée avec un mot de passe. Elle se restaure sur un iPhone, un iPad ou dans un navigateur.")
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
                    Text("Les données de cet appareil seront remplacées par celles de la sauvegarde.")
                }
                .confirmationDialog("Retirer le code ?", isPresented: $confirmRemovePin, titleVisibility: .visible) {
                    Button("Retirer le code", role: .destructive) {
                        model.setPin(nil)
                        model.show("Code retiré")
                    }
                } message: {
                    Text("Talka s'ouvrira sans code, et vos messages ne seront plus chiffrés par lui (ils restent chiffrés par l'appareil).")
                }
                .confirmationDialog("Tout effacer ?", isPresented: $confirmWipe, titleVisibility: .visible) {
                    Button("Effacer le code, les contacts et les messages", role: .destructive) {
                        Task { await model.leaveAndWipe() }
                    }
                } message: {
                    Text("Votre code sera perdu pour toujours, sauf si vous avez une sauvegarde.")
                }
            }
        }
    }

    /// Le verrou de Talka, comme dans le navigateur : le code (qui chiffre
    /// aussi les messages), Face ID, le délai, l'effacement après 10 codes
    /// faux ; et l'écran caché pendant un enregistrement.
    @ViewBuilder func security(_ e: Engine) -> some View {
        let bio = model.biometryName
        Section {
            if model.hasPin {
                Button {
                    settingPin = true
                } label: {
                    SettingLabel(title: "Changer le code de verrouillage", detail: "Talka demande votre code à l'ouverture", icon: "lock.fill")
                }
                if let bio {
                    Toggle(isOn: Binding(get: { model.faceID }, set: { model.faceID = $0 })) {
                        SettingLabel(title: bio, detail: "Ouvre Talka sans taper le code", icon: bio == "Touch ID" ? "touchid" : "faceid")
                    }
                }
                Picker(selection: setting(e, \.lockAfter)) {
                    Text("Dès que je quitte Talka").tag(0)
                    Text("Après 1 minute").tag(60)
                    Text("Après 5 minutes").tag(300)
                    Text("Après 15 minutes").tag(900)
                    Text("Après 1 heure").tag(3600)
                } label: {
                    SettingLabel(title: "Verrouillage automatique", detail: nil, icon: "timer")
                }
                Toggle(isOn: Binding(get: { model.wipeAfterFails }, set: { model.wipeAfterFails = $0 })) {
                    SettingLabel(title: "Effacer après 10 codes faux", detail: "Au dixième code faux, Talka efface tout sur cet appareil", icon: "trash")
                }
                Button(role: .destructive) {
                    confirmRemovePin = true
                } label: {
                    SettingLabel(title: "Retirer le code de verrouillage", detail: nil, icon: "lock.open", tint: .red)
                }
            } else {
                Button {
                    settingPin = true
                } label: {
                    SettingLabel(title: "Code de verrouillage", detail: "6 à 12 chiffres, qui chiffrent aussi vos messages sur cet appareil", icon: "lock.fill")
                }
                if let name = bio ?? (LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) ? "le code de l'appareil" : nil) {
                    Toggle(isOn: Binding(get: { model.vaultProtected }, set: { on in Task { await model.protectVault(on) } })) {
                        SettingLabel(title: "Protéger mes messages par \(name)", detail: "Ils ne s'ouvrent qu'avec votre visage, votre empreinte ou le code de l'appareil", icon: "faceid")
                    }
                }
            }
            Toggle(isOn: Binding(get: { model.hideWhenCaptured }, set: { model.hideWhenCaptured = $0 })) {
                SettingLabel(title: "Masquer pendant un enregistrement", detail: "Enregistrement, recopie (AirPlay) ou partage de l'écran : Talka se cache", icon: "eye.slash")
            }
        } header: {
            Text("Verrouillage et sécurité")
        } footer: {
            Text(securityFooter(bio))
        }
    }

    /// Les appareils du compte (v4) : iPhone, iPad, navigateur.
    @ViewBuilder func devices(_ e: Engine) -> some View {
        Section {
            NavigationLink {
                LinkedDevicesView()
            } label: {
                LabeledContent("Appareils liés", value: "\(e.devices.list.count)")
            }
        } header: {
            Text("Appareils")
        } footer: {
            Text("Votre compte sur votre iPhone, votre iPad et votre ordinateur à la fois : vos messages arrivent partout, chiffrés pour chaque appareil.")
        }
    }

    func securityFooter(_ bio: String?) -> String {
        if !model.hasPin {
            return "Vos messages sont chiffrés sur cet appareil par une clé que seul Talka peut lire. Avec un code, ils ne s'ouvrent plus sans lui, même pour quelqu'un qui copierait les fichiers. Dans le sélecteur d'apps, Talka montre toujours un écran neutre."
        }
        if !model.codeEncrypts {
            return "Tapez votre code une fois au prochain déverrouillage : il chiffrera aussi vos messages sur cet appareil."
        }
        let face = bio.map { " (ou \($0))" } ?? ""
        return "Votre code chiffre aussi vos messages sur cet appareil : sans lui\(face), ils ne s'ouvrent pas, même si quelqu'un copie les fichiers ou a l'appareil déverrouillé. Dans le sélecteur d'apps, Talka montre toujours un écran neutre."
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
    @State private var problem: String?

    var body: some View {
        VStack(spacing: 24) {
            HStack {
                Button("Annuler") { dismiss() }
                Spacer()
            }
            Spacer()
            Text(first == nil ? "Choisissez un code PIN" : "Confirmez le code PIN").font(.title2.bold())
            Text(problem ?? "6 à 12 chiffres, demandés à l'ouverture de Talka.")
                .foregroundStyle(problem != nil ? .red : .secondary)
            PinDots(count: pin.count)
            PinPad(pin: $pin) {
                if let f = first {
                    if f == pin {
                        model.setPin(pin)
                        model.show("Code PIN activé")
                        dismiss()
                    } else {
                        problem = "Les codes ne correspondent pas."
                        first = nil
                    }
                } else if pin.count < TalkaCrypto.pinMin {
                    problem = "6 chiffres au moins."
                } else if Set(pin).count == 1 || "01234567890123".contains(pin) || "98765432109876".contains(pin) {
                    problem = "Trop facile à deviner : choisissez-en un autre."
                } else {
                    first = pin
                    problem = nil
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

/// Une ligne de réglage : une icône, un titre et une précision, comme dans
/// le navigateur.
struct SettingLabel: View {
    let title: String
    let detail: String?
    let icon: String
    var tint: Color = .primary

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(tint)
                if let detail {
                    Text(detail).font(.caption).foregroundStyle(.secondary)
                }
            }
        } icon: {
            Image(systemName: icon).foregroundStyle(tint == .red ? Color.red : Color.secondary)
        }
    }
}
