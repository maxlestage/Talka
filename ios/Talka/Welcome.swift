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
    @State private var importing = false
    @State private var backup: Data?
    @State private var password = ""
    @State private var error: String?
    @State private var linking = false
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
                if let error {
                    Text(error).foregroundStyle(.red).font(.callout)
                }
                Button(action: create) {
                    Text("Créer mon code").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("J'ai déjà Talka sur un autre appareil") { linking = true }
                    .font(.callout)
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
        .sheet(isPresented: $linking) { LinkWaitView() }
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
        } message: {
            Text("Cet appareil remplacera celui d'où vient la sauvegarde : s'il sert encore, il sera retiré du compte. Pour ajouter un appareil, liez-le plutôt par son QR code.")
        }
    }

    func create() {
        let n = name.trimmingCharacters(in: .whitespaces)
        guard !n.isEmpty else { return }
        model.createAccount(name: String(n.prefix(40)), avatar: avatar)
    }

    func restore() {
        guard let data = backup else { return }
        backup = nil
        do {
            try model.restore(data, password: password)
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
            Text("Vos messages sont chiffrés sur cet appareil et ne s'ouvrent qu'avec \(model.biometryName ?? "Face ID") ou le code de l'appareil.")
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

/// Le verrou : le code de Talka (ou Face ID). Talka fermé, sa base chiffrée
/// par le code : le code ouvre d'abord la clé de la base (`codeLocked`).
struct LockView: View {
    @Environment(AppModel.self) private var model
    @State private var pin = ""
    @State private var wrong = false
    @State private var checking = false
    @State private var forgot = false
    // Les essais manqués survivent à un redémarrage de l'app.
    @State private var fails = UserDefaults.standard.integer(forKey: "talka.essais")
    @State private var until = Date(timeIntervalSince1970: UserDefaults.standard.double(forKey: "talka.essais.jusqua"))

    /// La base n'est pas encore ouverte : le code ouvre sa clé.
    var cold: Bool { model.codeLocked }
    /// Un code d'avant cette version : tapé une fois, il chiffrera la base
    /// (Face ID attend jusque-là).
    var mustType: Bool { !cold && !model.codeEncrypts }
    var faceAvailable: Bool { cold ? StoreVault.hasFaceUnlock : (model.faceID && !mustType) }

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            ScrollView {
                VStack(spacing: 22) {
                    Logo(size: 70).padding(.top, 30)
                    Text("Talka est verrouillé").font(.title2.bold())
                    PinDots(count: pin.count).modifier(Shake(amount: wrong ? 1 : 0))
                    TimelineView(.periodic(from: .now, by: 1)) { ctx in
                        message(wait: Int(until.timeIntervalSince(ctx.date).rounded(.up)))
                    }
                    PinPad(pin: $pin, onDone: check)
                        .disabled(checking)
                    if let bio = faceAvailable ? model.biometryName : nil {
                        Button {
                            Task { await biometry() }
                        } label: {
                            Label(bio, systemImage: bio == "Touch ID" ? "touchid" : "faceid")
                        }
                    }
                    Button("Code oublié ?") { forgot = true }
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding()
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .onChange(of: pin) { _, _ in wrong = false }
        .task { await biometry() }
        .confirmationDialog("Code oublié", isPresented: $forgot, titleVisibility: .visible) {
            Button("Tout effacer", role: .destructive) { model.wipe() }
        } message: {
            Text("Sans lui, vos messages ne s'ouvrent plus sur cet appareil. Vous pouvez tout effacer, puis restaurer une sauvegarde.")
        }
    }

    @ViewBuilder func message(wait: Int) -> some View {
        let text = wait > 0 ? "Trop d'essais : réessayez dans \(wait) s"
            : checking ? "Vérification…"
            : wrong ? "Code incorrect"
            : mustType ? "Entrez votre code une fois : il chiffrera désormais vos messages."
            : "Entrez votre code"
        Text(text)
            .multilineTextAlignment(.center)
            .foregroundStyle(wrong || wait > 0 ? .red : .secondary)
    }

    func biometry() async {
        if cold {
            await model.unlockColdWithFace()
        } else if !mustType {
            await model.unlockWithBiometry()
        }
    }

    func check() {
        guard Date() >= until, !checking else { return }
        let entered = pin
        pin = ""
        checking = true
        Task {
            let ok: Bool
            if cold {
                ok = await model.unlock(code: entered)
            } else {
                ok = model.checkPin(entered)
            }
            checking = false
            if ok {
                model.locked = false
                fails = 0
            } else {
                fails += 1
                // Choisi dans les réglages : au dixième code faux, tout part.
                if model.wipeAfterFails, fails >= 10 {
                    model.wipe()
                    model.show("10 codes faux : Talka a tout effacé sur cet appareil.")
                    return
                }
                if fails >= 5 { until = Date().addingTimeInterval(min(300, 15 * pow(2, Double(fails - 5)))) }
                withAnimation(.default) { wrong = true }
            }
            UserDefaults.standard.set(fails, forKey: "talka.essais")
            UserDefaults.standard.set(fails == 0 ? 0 : until.timeIntervalSince1970, forKey: "talka.essais.jusqua")
        }
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
