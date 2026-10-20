// Brocolis 🥦 dans l'app : les clés partagées avec l'app Brocolis, et les
// discussions qu'elles chiffrent, en plus de Talka. Le même réglage que le
// navigateur : un iPhone et un ordinateur se lisent.

import SwiftUI
import TalkaKit
import UIKit

let brocolisAbout = "Brocolis chiffre vos messages une seconde fois, avec des clés que vous partagez avec vos proches dans l'app Brocolis 🥦. Talka les chiffre déjà de bout en bout ; seuls ceux qui ont la clé ouvrent cette seconde couche, ici ou dans l'app Brocolis (clavier, Partager)."

/// Les clés Brocolis de l'iPhone : ajouter, créer, copier, renommer, oublier.
struct BrocolisKeysView: View {
    @Environment(AppModel.self) private var model
    @State private var adding = false
    @State private var renaming: BrocolisKey?
    @State private var newName = ""
    @State private var forgetting: BrocolisKey?

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
                            Text(k.name)
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
                        .swipeActions {
                            Button(role: .destructive) { forgetting = k } label: { Label("Oublier", systemImage: "trash") }
                            Button { newName = k.name; renaming = k } label: { Label("Renommer", systemImage: "pencil") }
                        }
                    }
                } footer: {
                    Text(brocolisAbout)
                }
                Section {
                    Button { adding = true } label: { Label("Ajouter une clé", systemImage: "plus") }
                    Button { create(e) } label: { Label("Créer une clé", systemImage: "key") }
                } footer: {
                    Text("Une clé créée ici est copiée : partagez-la par un canal sûr, et importez-la dans l'app Brocolis.")
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
            model.show("🥦 Clé « \(k.name) » ajoutée")
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
                    if e.brocolisKeys.isEmpty {
                        Section {
                            Text("Ajoutez d'abord une clé, partagée avec vos correspondants.")
                        } footer: {
                            Text(brocolisAbout)
                        }
                    } else {
                        Section {
                            ForEach(e.brocolisKeys) { k in
                                Toggle("🥦 \(k.name)", isOn: Binding(
                                    get: { selected.contains(k.id) },
                                    set: { on in
                                        if on { selected.insert(k.id) } else { selected.remove(k.id) }
                                    }))
                            }
                        } footer: {
                            Text("Ce qui part dans cette discussion sera chiffré avec les clés choisies, en plus de Talka. Écrivez ((comme ceci)) pour ne chiffrer qu'un passage. Plusieurs clés : un seul message, lisible par chacune.")
                        }
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
}
