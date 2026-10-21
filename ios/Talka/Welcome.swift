// L'accueil (créer son code, ou restaurer une sauvegarde) et le verrou (PIN,
// Face ID).

import PhotosUI
import SwiftUI
import TalkaKit
import UniformTypeIdentifiers

struct WelcomeView: View {
    @Environment(AppModel.self) private var model
    @State private var name = ""
    @State private var avatar = ""
    @State private var photo: PhotosPickerItem?
    @State private var server = AppModel.builtInServer
    @State private var importing = false
    @State private var backup: Data?
    @State private var password = ""
    @State private var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        ScrollView {
            VStack(spacing: 22) {
                Logo(size: 88).padding(.top, 40)
                VStack(spacing: 8) {
                    Text("Bienvenue sur Talka").font(.largeTitle.bold())
                    Text("Pas de numéro de téléphone : vous recevez un code, comme **K7Q2M-X9PRT**. Donnez-le à vos proches pour discuter, chiffré de bout en bout.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                }
                PhotosPicker(selection: $photo, matching: .images) {
                    ZStack(alignment: .bottomTrailing) {
                        if avatar.isEmpty {
                            Circle().fill(Color(.systemGray5)).frame(width: 110, height: 110)
                                .overlay { Image(systemName: "camera.fill").font(.largeTitle).foregroundStyle(.secondary) }
                        } else {
                            Avatar(id: "", name: name, image: avatar, size: 110)
                        }
                        Image(systemName: "plus.circle.fill").font(.title).foregroundStyle(Color.talkaGreen).background(Circle().fill(.background))
                    }
                }
                .accessibilityLabel("Choisir une photo")
                .onChange(of: photo) { _, item in
                    Task {
                        if let data = try? await item?.loadTransferable(type: Data.self), let ui = UIImage(data: data) {
                            avatar = ui.avatarDataURL() ?? ""
                        }
                    }
                }
                VStack(alignment: .leading, spacing: 8) {
                    TextField("Votre nom", text: $name)
                        .textContentType(.name)
                        .focused($focused)
                        .submitLabel(.go)
                        .onSubmit(create)
                        .padding()
                        .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: 12))
                        .accessibilityLabel("Votre nom")
                    Text("Visible par vos contacts. Vous pourrez le changer.").font(.footnote).foregroundStyle(.secondary)
                }
                if AppModel.builtInServer.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        TextField("https://talka.exemple.fr", text: $server)
                            .keyboardType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .padding()
                            .background(Color(.secondarySystemBackground), in: .rect(cornerRadius: 12))
                        Text("L'adresse du serveur Talka (le même que celui du navigateur).").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                if let error {
                    Text(error).foregroundStyle(.red).font(.callout)
                }
                Button(action: create) {
                    Text("Créer mon code").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Restaurer une sauvegarde") { importing = true }
                    .font(.callout)
                Label("Tout reste sur cet appareil. Le serveur ne fait que passer des messages chiffrés.", systemImage: "lock.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 30)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data, .json]) { result in
            guard let url = try? result.get() else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            backup = try? Data(contentsOf: url)
        }
        .alert("Mot de passe de la sauvegarde", isPresented: Binding(get: { backup != nil }, set: { if !$0 { backup = nil } })) {
            SecureField("Mot de passe", text: $password)
            Button("Restaurer", action: restore)
            Button("Annuler", role: .cancel) { password = "" }
        }
    }

    func validServer() -> String? {
        let s = server.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let full = s.contains("://") ? s : "https://\(s)"
        guard let url = URL(string: full), url.host != nil else {
            error = "L'adresse du serveur n'est pas valide."
            return nil
        }
        return full
    }

    func create() {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty, let s = validServer() else { return }
        model.createAccount(name: String(n.prefix(40)), avatar: avatar, server: s)
    }

    func restore() {
        guard let data = backup else { return }
        backup = nil
        do {
            try model.restore(data, password: password)
            // Une sauvegarde du navigateur ne connaît pas toujours le serveur.
            if model.engine == nil, let s = validServer() {
                var settings = model.store.read("kv", "settings", as: TalkaKit.Settings.self) ?? TalkaKit.Settings()
                settings.server = s
                model.store.write("kv", "settings", settings)
                model.start()
            }
            model.show("Sauvegarde restaurée ✓")
        } catch {
            self.error = error.localizedDescription
        }
        password = ""
    }
}

// MARK: Le verrou

/// La base protégée par Face ID : rien ne s'ouvre sans le visage (ou le
/// code de l'iPhone).
struct VaultLockView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Logo(size: 70)
            Text("Talka est verrouillé").font(.title2.bold())
            Text("Vos messages sont chiffrés sur cet iPhone et ne s'ouvrent qu'avec \(model.biometryName ?? "Face ID") ou le code de l'iPhone.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button {
                Task { await model.unlockVault() }
            } label: {
                Label("Déverrouiller", systemImage: model.biometryName == "Touch ID" ? "touchid" : "faceid")
                    .frame(maxWidth: 260)
            }
            .buttonStyle(.borderedProminent)
            .tint(.talkaGreen)
            Spacer()
        }
        .padding()
        .task { await model.unlockVault() }
    }
}

struct LockView: View {
    @Environment(AppModel.self) private var model
    @State private var pin = ""
    @State private var wrong = false
    @State private var fails = 0
    @State private var until = Date.distantPast

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 24) {
                Spacer()
                Logo(size: 70)
                Text("Talka est verrouillé").font(.title2.bold())
                PinDots(count: pin.count).modifier(Shake(amount: wrong ? 1 : 0))
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    let wait = Int(until.timeIntervalSince(ctx.date).rounded(.up))
                    Text(wait > 0 ? "Trop d'essais : réessayez dans \(wait) s" : wrong ? "Code PIN incorrect" : "Entrez votre code PIN")
                        .foregroundStyle(wrong || wait > 0 ? .red : .secondary)
                }
                PinPad(pin: $pin, onDone: check)
                if let bio = model.faceID ? model.biometryName : nil {
                    Button {
                        Task { await model.unlockWithBiometry() }
                    } label: {
                        Label(bio, systemImage: bio == "Touch ID" ? "touchid" : "faceid")
                    }
                }
                Spacer()
            }
            .padding()
        }
        .onChange(of: pin) { _, _ in wrong = false }
        .task { await model.unlockWithBiometry() }
    }

    func check() {
        guard Date() >= until else { return }
        if model.checkPin(pin) {
            model.locked = false
            fails = 0
        } else {
            fails += 1
            if fails >= 5 { until = Date().addingTimeInterval(min(300, 15 * pow(2, Double(fails - 5)))) }
            withAnimation(.default) { wrong = true }
        }
        pin = ""
    }
}

struct PinDots: View {
    let count: Int

    var body: some View {
        HStack(spacing: count > 8 ? 9 : 14) {
            ForEach(0..<max(4, count), id: \.self) { i in
                Circle()
                    .fill(i < count ? Color.talkaGreen : Color(.systemGray4))
                    .frame(width: 13, height: 13)
            }
        }
    }
}

/// Un clavier numérique : 4 à 12 chiffres, puis OK (6 au moins pour un
/// nouveau code).
struct PinPad: View {
    @Binding var pin: String
    let onDone: () -> Void

    var body: some View {
        let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["del", "0", "ok"]]
        VStack(spacing: 14) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 26) {
                    ForEach(row, id: \.self) { key in
                        switch key {
                        case "del":
                            Button { if !pin.isEmpty { pin.removeLast() } } label: {
                                Image(systemName: "delete.left").font(.title2).frame(width: 72, height: 72)
                            }
                            .foregroundStyle(.primary)
                            .accessibilityLabel("Effacer")
                        case "ok":
                            Button(action: onDone) {
                                Text("OK").font(.title3.bold()).frame(width: 72, height: 72)
                            }
                            .disabled(pin.count < 4)
                        default:
                            Button { if pin.count < TalkaCrypto.pinMax { pin += key } } label: {
                                Text(key).font(.title.weight(.medium))
                                    .frame(width: 72, height: 72)
                                    .background(Color(.secondarySystemBackground), in: .circle)
                            }
                            .foregroundStyle(.primary)
                        }
                    }
                }
            }
        }
    }
}

struct Shake: GeometryEffect {
    var amount: CGFloat
    var animatableData: CGFloat {
        get { amount }
        set { amount = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 10 * sin(amount * .pi * 6), y: 0))
    }
}
