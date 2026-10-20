// Brocolis 🥦 dans l'app : les clés partagées avec l'app Brocolis, et les
// discussions qu'elles chiffrent, en plus de Talka. Le même réglage que le
// navigateur : un iPhone et un ordinateur se lisent.

import SwiftUI
import TalkaKit
import UIKit

let brocolisAbout = "Brocolis chiffre vos messages une seconde fois, avec des clés que vous partagez avec vos proches dans l'app Brocolis 🥦. Talka les chiffre déjà de bout en bout ; seuls ceux qui ont la clé ouvrent cette seconde couche, ici ou dans l'app Brocolis (clavier, Partager)."
let brocolisStrength = "Chaque clé : 256 bits tirés au hasard (AES-256-GCM) ; le base64 n'est que sa façon de s'écrire. Le point faible, c'est le partage : envoyez-la par Talka plutôt que par un canal en clair, et comparez son empreinte de vive voix. Sur l'iPhone, les clés sont dans le trousseau, pour cet appareil seulement."

/// L'empreinte d'une clé, à comparer de vive voix.
struct BrocolisFingerprint: View {
    let key: String
    var body: some View {
        Text(Brocolis.fingerprint(key) ?? "")
            .font(.caption.monospaced())
            .foregroundStyle(.secondary)
            .accessibilityLabel("Empreinte \(Brocolis.fingerprint(key) ?? "")")
    }
}

/// Les clés Brocolis de l'iPhone : ajouter, créer, copier, renommer, oublier.
struct BrocolisKeysView: View {
    @Environment(AppModel.self) private var model
    @State private var adding = false
    @State private var renaming: BrocolisKey?
    @State private var newName = ""
    @State private var forgetting: BrocolisKey?
    @State private var renewing: BrocolisKey?

    var body: some View {
        if let e = model.engine {
            List {
                Section {
                    if e.brocolisKeys.isEmpty {
                        Text("Aucune clé pour l'instant.").foregroundStyle(.secondary)
                    }
                    ForEach(e.brocolisKeys) { k in
                        HStack(spacing: 12) {
                            Text("🥦").font(.title2)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(k.name)
                                BrocolisFingerprint(key: k.key)
                            }
                            Spacer()
                            Button {
                                UIPasteboard.general.string = k.key
                                model.show("Clé copiée : à coller dans l'app Brocolis")
                            } label: {
                                Image(systemName: "doc.on.doc")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Copier la clé")
                        }
                        .opacity(k.retired ? 0.6 : 1)
                        .swipeActions {
                            Button(role: .destructive) { forgetting = k } label: { Label("Oublier", systemImage: "trash") }
                            Button { newName = k.name; renaming = k } label: { Label("Renommer", systemImage: "pencil") }
                            if !k.retired {
                                Button { renewing = k } label: { Label("Renouveler", systemImage: "arrow.triangle.2.circlepath") }
                                    .tint(.green)
                            }
                        }
                    }
                } footer: {
                    Text(brocolisAbout)
                }
                Section {
                    Button { adding = true } label: { Label("Ajouter une clé", systemImage: "plus") }
                    Button { create(e) } label: { Label("Créer une clé", systemImage: "key") }
                } footer: {
                    Text("Une clé créée ici est copiée : partagez-la par un canal sûr, ou envoyez-la par Talka depuis une discussion (⋯ → Chiffrer avec Brocolis). Balayez une clé pour la renommer, la renouveler ou l'oublier.\n\n\(brocolisStrength)")
                }
            }
            .navigationTitle("Brocolis 🥦")
            .sheet(isPresented: $adding) { AddBrocolisKey() }
            .alert("Renommer la clé", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Nom", text: $newName)
                Button("Annuler", role: .cancel) {}
                Button("Enregistrer") {
                    if let k = renaming { e.renameBrocolisKey(k.id, name: newName) }
                }
            }
            .alert("Oublier cette clé ?", isPresented: Binding(get: { forgetting != nil }, set: { if !$0 { forgetting = nil } }), presenting: forgetting) { k in
                Button("Oublier", role: .destructive) { e.removeBrocolisKey(k.id) }
            } message: { k in
                Text("« \(k.name) » ne chiffrera plus rien. Les messages déjà ouverts le restent ; les nouveaux, à cette clé, resteront scellés.")
            }
            .alert("Renouveler cette clé ?", isPresented: Binding(get: { renewing != nil }, set: { if !$0 { renewing = nil } }), presenting: renewing) { k in
                Button("Renouveler") {
                    let n = (try? e.renewBrocolisKey(k.id)) ?? 0
                    model.show(n == 0 ? "🥦 Clé renouvelée : partagez la neuve avec vos proches" : "🥦 Clé renouvelée et envoyée dans \(n) discussion\(n > 1 ? "s" : "")")
                }
                Button("Annuler", role: .cancel) {}
            } message: { k in
                Text("Une clé neuve remplace « \(k.name) » dans ses discussions et y est envoyée par Talka. L'ancienne garde l'historique lisible, mais ne chiffre plus rien.")
            }
        }
    }

    func create(_ e: Engine) {
        guard let k = try? e.addBrocolisKey(name: "", pasted: Brocolis.generateKey()) else { return }
        UIPasteboard.general.string = k.key
        model.show("🥦 Clé créée et copiée : partagez-la avec vos proches")
        // Tout de suite son nom : pour qui est-elle ?
        newName = ""
        renaming = k
    }
}

/// Ajouter une clé reçue : collée seule, ou toute l'invitation de l'app Brocolis.
struct AddBrocolisKey: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var pasted = ""
    @State private var name = ""
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("La clé, ou toute l'invitation reçue", text: $pasted, axis: .vertical)
                        .lineLimit(2...6)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button {
                        if let s = UIPasteboard.general.string { pasted = s }
                    } label: {
                        Label("Coller", systemImage: "doc.on.clipboard")
                    }
                } footer: {
                    Text("Dans l'app Brocolis : copiez la clé, ou partagez l'invitation reçue.")
                }
                Section {
                    TextField("Nom (par exemple : Maman, Travail)", text: $name)
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Ajouter une clé")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Ajouter") { add() }
                        .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    func add() {
        guard let e = model.engine else { return }
        do {
            let k = try e.addBrocolisKey(name: name, pasted: pasted)
            model.show("🥦 Clé « \(k.name) » ajoutée · empreinte \(Brocolis.fingerprint(k.key) ?? "")")
            dismiss()
        } catch let failure {
            error = failure.localizedDescription
        }
    }
}

/// Les clés qui chiffrent ce qui part dans une discussion.
struct BrocolisChatSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let chatId: String
    @State private var selected: Set<String> = []

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                Form {
                    let keys = e.brocolisKeys.filter { !$0.retired }
                    if keys.isEmpty {
                        Section {
                            Text("Ajoutez d'abord une clé, partagée avec vos correspondants.")
                        } footer: {
                            Text(brocolisAbout)
                        }
                    } else {
                        Section {
                            ForEach(keys) { k in
                                Toggle(isOn: Binding(
                                    get: { selected.contains(k.id) },
                                    set: { on in
                                        if on { selected.insert(k.id) } else { selected.remove(k.id) }
                                    })) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("🥦 \(k.name)")
                                        BrocolisFingerprint(key: k.key)
                                    }
                                }
                            }
                        } footer: {
                            Text("Ce qui part dans cette discussion sera chiffré avec les clés choisies, en plus de Talka. Écrivez ((comme ceci)) pour ne chiffrer qu'un passage. Plusieurs clés : un seul message, lisible par chacune.")
                        }
                    }
                    Section {
                        Button {
                            createAndSend(e)
                        } label: {
                            Label("Créer une clé et l'envoyer ici", systemImage: "paperplane")
                        }
                        if !selected.isEmpty {
                            Button {
                                for id in keys.map(\.id) where selected.contains(id) { e.shareBrocolisKey(chatId, keyId: id) }
                                model.show("🥦 Clé envoyée par Talka")
                                dismiss()
                            } label: {
                                Label("Envoyer les clés cochées ici", systemImage: "key")
                            }
                        }
                    } footer: {
                        Text("Une clé envoyée par Talka voyage chiffrée de bout en bout ; vos correspondants l'ajoutent d'un geste. Comparez son empreinte de vive voix.")
                    }
                    Section {
                        NavigationLink("Gérer les clés") { BrocolisKeysView() }
                    }
                }
                .navigationTitle("Brocolis 🥦")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Enregistrer") {
                            let ids = e.brocolisKeys.map(\.id).filter { selected.contains($0) }
                            e.setChatBrocolis(chatId, ids)
                            model.show(ids.isEmpty ? "Brocolis désactivé pour cette discussion" : "🥦 Brocolis activé pour cette discussion")
                            dismiss()
                        }
                    }
                }
                .onAppear { selected = Set(e.chat(chatId)?.brocolis ?? []) }
            }
        }
    }

    /// Une clé neuve, au nom de la discussion : activée ici et envoyée par Talka.
    func createAndSend(_ e: Engine) {
        let title = e.chat(chatId).map { e.title($0) } ?? ""
        guard let k = try? e.addBrocolisKey(name: title, pasted: Brocolis.generateKey()) else { return }
        e.setChatBrocolis(chatId, (e.chat(chatId)?.brocolis ?? []) + [k.id])
        e.shareBrocolisKey(chatId, keyId: k.id)
        model.show("🥦 Clé créée, envoyée par Talka et activée ici")
        dismiss()
    }
}

/// La bulle d'une clé partagée par Talka : son nom, son empreinte, et
/// « Ajouter la clé » (qui l'active aussi dans la discussion).
struct BrocolisKeyCard: View {
    @Environment(AppModel.self) private var model
    let engine: Engine
    let message: Message

    var body: some View {
        let e = engine
        let m = message
        let have = e.sharedKey(of: m)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Text("🥦").font(.title2)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Clé Brocolis « \(m.name) »").font(.subheadline.bold())
                    BrocolisFingerprint(key: m.data.isEmpty ? have?.key ?? "" : m.data)
                }
            }
            if m.from == e.me.code {
                Text("Vous avez partagé cette clé. Comparez l'empreinte de vive voix.").font(.caption).foregroundStyle(.secondary)
            } else if let have {
                Text("✓ Dans vos clés (« \(have.name) »)").font(.caption).foregroundStyle(.secondary)
            } else if m.data.isEmpty {
                Text("Clé oubliée sur cet iPhone.").font(.caption).foregroundStyle(.secondary)
            } else {
                Button {
                    do {
                        let k = try e.acceptBrocolisKey(m)
                        model.show("🥦 « \(k.name) » ajoutée et activée ici · empreinte \(Brocolis.fingerprint(k.key) ?? "")")
                    } catch {
                        model.show(error.localizedDescription)
                    }
                } label: {
                    Text("Ajouter la clé").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.talkaGreen)
            }
        }
        .frame(minWidth: 220, alignment: .leading)
    }
}
