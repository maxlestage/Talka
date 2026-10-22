// L'onglet Actus : des textes sur fond de couleur ou des photos, visibles
// 24 heures par vos contacts.

import PhotosUI
import SwiftUI
import TalkaKit

let statusColors = ["#008069", "#5e47de", "#c4532d", "#1f7aec", "#a62c71", "#b7820b", "#243b4a"]

extension Color {
    /// `#rrggbb`.
    init(hex: String) {
        let v = UInt32(hex.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x008069
        self.init(red: Double(v >> 16 & 0xFF) / 255, green: Double(v >> 8 & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
    }
}

struct StatusTab: View {
    @Environment(AppModel.self) private var model
    @State private var writing = false
    @State private var photo: PhotosPickerItem?
    @State private var photoData: String?
    @State private var viewing: [StatusItem]?

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                let _ = e.statusRevision
                let all = e.statuses()
                let mine = all.filter { $0.owner == e.me.code }
                let others = Dictionary(grouping: all.filter { $0.owner != e.me.code && e.contact($0.owner)?.blocked != true }, by: \.owner)
                    .sorted { ($0.value.last?.at ?? 0) > ($1.value.last?.at ?? 0) }
                // Les actus en sourdine : à part, en bas, jamais « Récentes ».
                let hidden = others.filter { e.contact($0.key)?.statusMuted == true }
                let shown = others.filter { e.contact($0.key)?.statusMuted != true }
                let unseen = shown.filter { $0.value.contains { !$0.seen } }
                let seen = shown.filter { !$0.value.contains { !$0.seen } }
                List {
                    Section {
                        Button {
                            if mine.isEmpty { writing = true } else { viewing = mine }
                        } label: {
                            HStack(spacing: 12) {
                                ZStack(alignment: .bottomTrailing) {
                                    StatusRing(count: mine.count, unseen: 0) { e.avatar(e.me.code, size: 50) }
                                    if mine.isEmpty {
                                        Image(systemName: "plus.circle.fill").foregroundStyle(Color.talkaGreen)
                                            .background(Circle().fill(Color(.systemBackground)))
                                    }
                                }
                                VStack(alignment: .leading) {
                                    Text("Mon actu").font(.body.weight(.semibold)).foregroundStyle(.primary)
                                    Text(mine.last.map { "\(Dates.list($0.at)) · \(viewers(mine)) vue(s)" } ?? "Touchez pour publier")
                                        .font(.subheadline).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    if !unseen.isEmpty {
                        Section("Récentes") { ForEach(unseen, id: \.key) { row(e, $0.key, $0.value) } }
                    }
                    if !seen.isEmpty {
                        Section("Vues") { ForEach(seen, id: \.key) { row(e, $0.key, $0.value) } }
                    }
                    if !hidden.isEmpty {
                        Section("Masquées") { ForEach(hidden, id: \.key) { row(e, $0.key, $0.value) } }
                    }
                    if others.isEmpty {
                        Text("Les actus de vos contacts apparaîtront ici. Elles disparaissent au bout de 24 heures.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .navigationTitle("Actus")
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        PhotosPicker(selection: $photo, matching: .images) { Image(systemName: "camera") }
                            .accessibilityLabel("Publier une photo")
                        Button { writing = true } label: { Image(systemName: "pencil") }
                            .accessibilityLabel("Publier un texte")
                    }
                }
                .sheet(isPresented: $writing) { TextStatusComposer() }
                .sheet(item: Binding(get: { photoData.map(SharedText.init) }, set: { photoData = $0?.text })) { d in
                    PhotoStatusComposer(data: d.text)
                }
                .fullScreenCover(item: Binding(get: { viewing.map(StatusGroup.init) }, set: { viewing = $0?.items })) { g in
                    StatusViewer(items: g.items)
                }
                .onChange(of: photo) { _, item in
                    guard let item else { return }
                    photo = nil
                    Task {
                        if let data = try? await item.loadTransferable(type: Data.self), let ui = UIImage(data: data) {
                            photoData = ui.compressed(max: 1280, quality: 0.8)?.0
                        }
                    }
                }
            }
        }
    }

    func viewers(_ items: [StatusItem]) -> Int { Set(items.flatMap(\.viewers.keys)).count }

    func row(_ e: Engine, _ owner: String, _ items: [StatusItem]) -> some View {
        let muted = e.contact(owner)?.statusMuted == true
        return Button { viewing = items } label: {
            HStack(spacing: 12) {
                StatusRing(count: items.count, unseen: muted ? 0 : items.filter { !$0.seen }.count) { e.avatar(owner, size: 50) }
                VStack(alignment: .leading) {
                    Text(e.displayName(owner)).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    Text(Dates.list(items.last?.at ?? 0)).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            .opacity(muted ? 0.6 : 1)
        }
        .contextMenu {
            Button {
                e.updateContact(owner) { $0.statusMuted.toggle() }
            } label: {
                Label(muted ? "Afficher ses actus" : "Masquer ses actus", systemImage: muted ? "bell" : "bell.slash")
            }
        }
    }
}

struct SharedText: Identifiable {
    let text: String
    var id: Int { text.hashValue }
}

struct StatusGroup: Identifiable {
    let items: [StatusItem]
    var id: String { items.map(\.key).joined() }
}

/// L'anneau autour de l'avatar : vert pour les actus non vues.
struct StatusRing<Content: View>: View {
    let count: Int
    let unseen: Int
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .padding(3)
            .overlay {
                if count > 0 {
                    Circle().stroke(unseen > 0 ? Color.talkaAccent : Color(.systemGray3), lineWidth: 2.5)
                }
            }
    }
}

struct TextStatusComposer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var color = 0
    @FocusState private var focused: Bool

    var body: some View {
        ZStack {
            Color(hex: statusColors[color]).ignoresSafeArea()
            TextField("Écrivez une actu", text: $text, axis: .vertical)
                .font(.system(size: 30, weight: .semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(.white)
                .tint(.white)
                .focused($focused)
                .padding(30)
            VStack {
                HStack {
                    Button { dismiss() } label: { Image(systemName: "xmark").font(.title2) }
                    Spacer()
                    Button { color = (color + 1) % statusColors.count } label: { Image(systemName: "paintpalette.fill").font(.title2) }
                        .accessibilityLabel("Changer la couleur")
                }
                .foregroundStyle(.white)
                .padding()
                Spacer()
                HStack {
                    Spacer()
                    Button {
                        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !t.isEmpty else { return }
                        model.engine?.postStatus(kind: "text", text: String(t.prefix(700)), bg: statusColors[color], data: "")
                        dismiss()
                    } label: {
                        Image(systemName: "paperplane.fill").font(.title2).foregroundStyle(.white)
                            .frame(width: 56, height: 56).background(Color.talkaAccent, in: .circle)
                    }
                    .accessibilityLabel("Publier")
                }
                .padding()
            }
        }
        .onAppear { focused = true }
    }
}

struct PhotoStatusComposer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let data: String
    @State private var text = ""

    var body: some View {
        NavigationStack {
            VStack {
                if let ui = UIImage.fromDataURL(data) {
                    Image(uiImage: ui).resizable().scaledToFit().clipShape(.rect(cornerRadius: 12))
                }
                TextField("Légende", text: $text).textFieldStyle(.roundedBorder)
            }
            .padding()
            .navigationTitle("Nouvelle actu")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Publier") {
                        model.engine?.postStatus(kind: "image", text: String(text.prefix(700)), bg: "", data: data)
                        dismiss()
                    }
                }
            }
        }
    }
}

/// Les actus d'une personne, l'une après l'autre (5 secondes chacune).
struct StatusViewer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let items: [StatusItem]
    @State private var index = 0
    @State private var progress: Double = 0
    @State private var paused = false
    @State private var showViewers = false

    var body: some View {
        if let e = model.engine, index < items.count {
            let st = items[index]
            ZStack {
                (st.kind == "text" ? Color(hex: st.bg.isEmpty ? statusColors[0] : st.bg) : Color.black).ignoresSafeArea()
                if st.kind == "image", let ui = UIImage.fromDataURL(st.data) {
                    Image(uiImage: ui).resizable().scaledToFit()
                }
                if st.kind == "text" {
                    Text(st.text).font(.system(size: 30, weight: .semibold)).foregroundStyle(.white)
                        .multilineTextAlignment(.center).padding(30)
                } else if !st.text.isEmpty {
                    VStack {
                        Spacer()
                        Text(st.text).foregroundStyle(.white).padding().frame(maxWidth: .infinity).background(.black.opacity(0.5))
                    }
                }
                HStack(spacing: 0) {
                    Color.clear.contentShape(Rectangle()).onTapGesture { go(-1) }
                    Color.clear.contentShape(Rectangle()).onTapGesture { go(1) }
                }
                .onLongPressGesture(minimumDuration: 0.2, perform: {}, onPressingChanged: { paused = $0 })
                VStack(spacing: 10) {
                    HStack(spacing: 4) {
                        ForEach(items.indices, id: \.self) { i in
                            GeometryReader { g in
                                Capsule().fill(.white.opacity(0.35))
                                    .overlay(alignment: .leading) {
                                        Capsule().fill(.white).frame(width: g.size.width * (i < index ? 1 : i == index ? progress : 0))
                                    }
                            }
                            .frame(height: 3)
                        }
                    }
                    HStack(spacing: 10) {
                        e.avatar(st.owner, size: 36)
                        VStack(alignment: .leading) {
                            Text(st.owner == e.me.code ? "Mon actu" : e.displayName(st.owner)).font(.subheadline.bold())
                            Text(Dates.list(st.at)).font(.caption)
                        }
                        Spacer()
                        if st.owner == e.me.code {
                            Button {
                                e.deleteStatus(st.id)
                                if items.count == 1 { dismiss() } else { go(1) }
                            } label: { Image(systemName: "trash") }
                                .accessibilityLabel("Supprimer")
                        }
                        Button { dismiss() } label: { Image(systemName: "xmark").font(.title3) }
                            .accessibilityLabel("Fermer")
                    }
                    .foregroundStyle(.white)
                    Spacer()
                    if st.owner == e.me.code {
                        Button { showViewers = true; paused = true } label: {
                            Label("\(st.viewers.count)", systemImage: "eye").foregroundStyle(.white)
                                .padding(.horizontal, 14).padding(.vertical, 8).background(.black.opacity(0.4), in: .capsule)
                        }
                    }
                }
                .padding()
            }
            .task(id: index) {
                e.markStatusSeen(st)
                progress = 0
                while progress < 1 {
                    try? await Task.sleep(for: .milliseconds(50))
                    if Task.isCancelled { return }
                    if !paused { progress += 0.01 }
                }
                go(1)
            }
            .sheet(isPresented: $showViewers, onDismiss: { paused = false }) {
                NavigationStack {
                    List(st.viewers.sorted { $0.value > $1.value }, id: \.key) { code, at in
                        HStack(spacing: 12) {
                            e.avatar(code, size: 36)
                            Text(e.displayName(code))
                            Spacer()
                            Text(Dates.list(at)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .overlay { if st.viewers.isEmpty { ContentUnavailableView("Pas encore de vues", systemImage: "eye.slash") } }
                    .navigationTitle("Vue par")
                    .navigationBarTitleDisplayMode(.inline)
                }
                .presentationDetents([.medium])
            }
        } else {
            Color.black.ignoresSafeArea().onAppear { dismiss() }
        }
    }

    func go(_ step: Int) {
        let next = index + step
        if next < 0 { progress = 0; return }
        if next >= items.count { dismiss(); return }
        index = next
    }
}
