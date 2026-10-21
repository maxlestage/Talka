// Plusieurs appareils par compte (v4, docs/SECURITE.md §7.11 et §7.12) :
// Réglages → Appareils liés (la liste, renommer cet appareil, en retirer un,
// en lier un nouveau par son QR code), et, sur le nouvel appareil, l'attente
// de la liaison (le QR code à scanner, puis le code de vérification).

import SwiftUI
import TalkaKit

// MARK: Réglages → Appareils liés

struct LinkedDevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var renaming = false
    @State private var newName = ""
    @State private var removing: AccountDevice?
    @State private var linking = false

    var body: some View {
        if let e = model.engine {
            List {
                Section {
                    ForEach(e.devices.list) { d in
                        DeviceRow(device: d, mine: d.dev == e.dev) {
                            if d.dev == e.dev {
                                newName = d.name
                                renaming = true
                            } else {
                                removing = d
                            }
                        }
                    }
                } footer: {
                    Text("Chaque appareil a ses propres clés : vos contacts chiffrent chaque message pour chacun d'eux. Un appareil retiré efface tout ce que Talka y garde.")
                }
                Section {
                    Button { linking = true } label: {
                        Label("Scanner son QR code", systemImage: "qrcode.viewfinder")
                    }
                    .disabled(e.devices.list.count >= maxDevices)
                } header: {
                    Text("Lier un appareil")
                } footer: {
                    Text("Pour en lier un : ouvrez Talka sur le nouvel appareil et choisissez « J'ai déjà Talka sur un autre appareil ». Un QR code s'affiche. Six appareils au plus : iPhone, iPad ou navigateur.")
                }
            }
            .navigationTitle("Appareils liés")
            .alert("Renommer cet appareil", isPresented: $renaming) {
                TextField("Nom", text: $newName)
                Button("Enregistrer") { e.renameDevice(e.dev, name: newName) }
                Button("Annuler", role: .cancel) {}
            }
            .confirmationDialog("Retirer cet appareil ?", isPresented: removingShown, titleVisibility: .visible, presenting: removing) { d in
                Button("Retirer « \(d.name) »", role: .destructive) {
                    Task { await e.unlinkDevice(d.dev) }
                }
            } message: { _ in
                Text("Il effacera tout ce que Talka y garde, et ne recevra plus vos messages.")
            }
            .sheet(isPresented: $linking) { LinkDeviceSheet() }
        }
    }

    var removingShown: Binding<Bool> {
        Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })
    }
}

struct DeviceRow: View {
    let device: AccountDevice
    let mine: Bool
    let action: () -> Void

    var icon: String {
        let name = device.name.lowercased()
        if name.contains("ipad") { return "ipad" }
        if name.contains("iphone") { return "iphone" }
        if name.contains("mac") || name.contains("windows") || name.contains("linux") { return "laptopcomputer" }
        return "macbook.and.iphone"
    }

    var added: String {
        Date(timeIntervalSince1970: device.at / 1000).formatted(date: .abbreviated, time: .omitted)
    }

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(Color.talkaGreen)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(device.name.isEmpty ? "Appareil" : device.name)
                Text(mine ? "Cet appareil" : "Lié le \(added)")
                    .font(.caption)
                    .foregroundStyle(mine ? Color.talkaDark : .secondary)
            }
            Spacer()
            if mine {
                Button("Renommer", action: action).buttonStyle(.borderless)
            } else {
                Button("Retirer", role: .destructive, action: action).buttonStyle(.borderless)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Le code de vérification d'une liaison, le même sur les deux appareils.
struct VerificationCode: View {
    let code: String

    var body: some View {
        VStack(spacing: 6) {
            Text("Code de vérification").font(.caption).foregroundStyle(.secondary)
            Text(code)
                .font(.system(size: 40, weight: .bold, design: .monospaced))
                .foregroundStyle(Color.talkaDark)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }
}

/// Lier un nouvel appareil : scanner son QR code (ou coller son code de
/// liaison) ; notre compte lui part chiffré, pour lui seul.
struct LinkDeviceSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var scanning = false
    @State private var busy = false
    @State private var verif: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                if let verif {
                    Section {
                        Label("Compte envoyé au nouvel appareil", systemImage: "checkmark.seal.fill")
                            .foregroundStyle(Color.talkaGreen)
                        VerificationCode(code: verif)
                    } footer: {
                        Text("Le nouvel appareil doit afficher le même code de vérification. S'il est différent, retirez-le (Réglages → Appareils liés).")
                    }
                } else {
                    form
                }
            }
            .navigationTitle("Lier un appareil")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbar }
            .sheet(isPresented: $scanning) {
                QRScanner { found in
                    scanning = false
                    scanned(found)
                }
                .ignoresSafeArea()
            }
        }
        .interactiveDismissDisabled(busy)
    }

    @ViewBuilder var form: some View {
        Section {
            Button { scanning = true } label: {
                Label("Scanner le QR code", systemImage: "qrcode.viewfinder")
            }
            TextField("ou collez le code de liaison", text: $text, axis: .vertical)
                .font(.footnote.monospaced())
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button {
                if let s = UIPasteboard.general.string { text = s }
            } label: {
                Label("Coller", systemImage: "doc.on.clipboard")
            }
        } header: {
            Text("Le nouvel appareil")
        } footer: {
            Text("Sur le nouvel appareil : « J'ai déjà Talka sur un autre appareil », puis visez son QR code (ou « Copier le code de liaison », et collez-le ici). Vos contacts, vos discussions et vos messages lui partent chiffrés, pour lui seul.")
        }
        if let error {
            Section { Text(error).foregroundStyle(.red) }
        }
    }

    @ToolbarContentBuilder var toolbar: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            if verif == nil { Button("Annuler") { dismiss() }.disabled(busy) }
        }
        ToolbarItem(placement: .confirmationAction) {
            if verif != nil {
                Button("OK") { dismiss() }
            } else if busy {
                ProgressView()
            } else {
                Button("Lier") { link(text) }.disabled(TalkaV4.LinkOffer.parse(text) == nil)
            }
        }
    }

    func scanned(_ found: String) {
        let t = found.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(TalkaV4.linkPrefix) else {
            error = "Ce QR code n'est pas un code de liaison Talka."
            return
        }
        text = t
        link(t)
    }

    func link(_ s: String) {
        guard let e = model.engine, !busy else { return }
        busy = true
        error = nil
        Task {
            do {
                verif = try await e.linkDevice(s)
            } catch {
                self.error = error.localizedDescription
            }
            busy = false
        }
    }
}

// MARK: L'accueil → « J'ai déjà Talka sur un autre appareil »

/// Ce nouvel appareil attend d'être lié : il montre son QR code (et son code
/// de liaison, à copier), puis le code de vérification.
struct LinkWaitView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var link: DeviceLink?
    @State private var verif: String?
    @State private var error: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let verif {
                        linked(verif)
                    } else if let link {
                        waiting(link)
                    } else {
                        ProgressView().padding(.top, 40)
                    }
                    if let error {
                        Text(error).foregroundStyle(.red).multilineTextAlignment(.center)
                        Button("Réessayer", action: start)
                    }
                }
                .padding(24)
                .frame(maxWidth: 520)
                .frame(maxWidth: .infinity)
            }
            .navigationTitle("Lier cet appareil")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if verif == nil {
                        Button("Annuler") {
                            link?.cancel()
                            dismiss()
                        }
                    }
                }
            }
        }
        .interactiveDismissDisabled(verif != nil)
        .onAppear { if link == nil { start() } }
        .onDisappear { if verif == nil { link?.cancel() } }
    }

    @ViewBuilder func waiting(_ link: DeviceLink) -> some View {
        Text("Sur l'appareil où Talka est déjà ouvert (iPhone, iPad, navigateur) : Réglages → Appareils liés → Scanner son QR code, puis visez celui-ci.")
            .multilineTextAlignment(.center)
            .foregroundStyle(.secondary)
        if let qr = QR.image(link.text) {
            Image(uiImage: qr)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 260)
                .padding(12)
                .background(.white, in: .rect(cornerRadius: 16))
                .accessibilityLabel("QR code de liaison")
        }
        Button {
            UIPasteboard.general.string = link.text
            model.show("Code de liaison copié")
        } label: {
            Label("Copier le code de liaison", systemImage: "doc.on.doc")
        }
        .buttonStyle(.bordered)
        HStack(spacing: 10) {
            ProgressView()
            Text("En attente de l'autre appareil…").foregroundStyle(.secondary)
        }
        Label("Votre compte arrive chiffré pour cet appareil seul : le relais ne peut pas le lire.", systemImage: "lock.fill")
            .font(.footnote)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
    }

    @ViewBuilder func linked(_ code: String) -> some View {
        Image(systemName: "checkmark.seal.fill")
            .font(.system(size: 56))
            .foregroundStyle(Color.talkaGreen)
            .padding(.top, 20)
        Text("Appareil lié").font(.title2.bold())
        Text("Vérifiez que l'autre appareil affiche le même code :")
            .multilineTextAlignment(.center)
            .foregroundStyle(.secondary)
        VerificationCode(code: code)
        Button {
            dismiss()
            model.finishLinking()
        } label: {
            Text("Commencer").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent)
    }

    func start() {
        error = nil
        link?.cancel()
        guard let l = model.makeDeviceLink() else {
            error = "Pas de relais : réessayez."
            return
        }
        l.onDone = { result in
            switch result {
            case .success(let code):
                verif = code
            case .failure(let e):
                error = e.localizedDescription
            }
        }
        l.start()
        link = l
    }
}
