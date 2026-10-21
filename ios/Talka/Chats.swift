// L'onglet Discussions : la liste, la recherche, et les feuilles pour
// ajouter un contact (code, QR code), créer un groupe, montrer son code.

import AVFoundation
import PhotosUI
import SwiftUI
import UserNotifications
import TalkaKit

struct ChatsTab: View {
    @Environment(AppModel.self) private var model
    @State private var query = ""
    @State private var filter = Filter.all
    @State private var sheet: Sheet?
    /// La discussion à mettre en sourdine (choix de la durée).
    @State private var muting: String?

    enum Filter: String, CaseIterable { case all = "Toutes", unread = "Non lues", groups = "Groupes", archived = "Archivées" }

    enum Sheet: Identifiable {
        case newChat, add(String), group, myCode, starred
        var id: String {
            switch self {
            case .newChat: "new"
            case .add(let c): "add\(c)"
            case .group: "group"
            case .myCode: "code"
            case .starred: "starred"
            }
        }
    }

    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        @Bindable var model = model
        if sizeClass == .regular {
            // iPad (et toute fenêtre large) : la liste à gauche, la
            // discussion à droite, sur tout l'écran.
            NavigationSplitView {
                list
            } detail: {
                NavigationStack {
                    if let id = model.path.last {
                        ConversationView(chatId: id).id(id)
                    } else {
                        ContentUnavailableView {
                            Label("Talka", systemImage: "bubble.left.and.bubble.right")
                        } description: {
                            Text("Choisissez une discussion. Vos messages sont chiffrés de bout en bout.")
                        }
                    }
                }
            }
            .navigationSplitViewStyle(.balanced)
        } else {
            NavigationStack(path: $model.path) {
                list
                    .navigationDestination(for: String.self) { id in ConversationView(chatId: id) }
            }
        }
    }

    /// La discussion choisie dans la liste (sur iPad, la colonne de droite).
    var selection: Binding<String?> {
        Binding(get: { model.path.last }, set: { id in model.path = id.map { [$0] } ?? [] })
    }

    @ViewBuilder var list: some View {
        if let e = model.engine {
            Group {
                if sizeClass == .regular {
                    List(selection: selection) { rows(e) }
                } else {
                    List { rows(e) }
                }
            }
            .listStyle(.plain)
            .overlay {
                if chats(e).isEmpty && e.searchMessages(query).isEmpty {
                    ContentUnavailableView {
                        Label(query.isEmpty ? "Aucune discussion" : "Aucun résultat", systemImage: "bubble.left.and.bubble.right")
                    } description: {
                        Text(query.isEmpty ? "Ajoutez un contact avec son code Talka pour commencer." : "")
                    } actions: {
                        if query.isEmpty, filter == .all {
                            Button("Nouvelle discussion") { sheet = .newChat }.buttonStyle(.borderedProminent)
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Rechercher")
            .confirmationDialog("Mettre en sourdine", isPresented: Binding(get: { muting != nil }, set: { if !$0 { muting = nil } }),
                                titleVisibility: .visible) {
                if let id = muting, let e = model.engine {
                    MuteChoices(engine: e, chatId: id, muted: false)
                }
            } message: {
                Text("Plus de notification ni de son ; les messages arrivent quand même.")
            }
            .navigationTitle("Discussions")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Button { sheet = .myCode } label: { Label("Mon code", systemImage: "qrcode") }
                        Button { sheet = .group } label: { Label("Nouveau groupe", systemImage: "person.3") }
                        Button { sheet = .starred } label: { Label("Messages importants", systemImage: "star") }
                        if let e = model.engine, e.chats.values.contains(where: { $0.unread > 0 || $0.markedUnread }) {
                            Button {
                                let n = e.markAllRead()
                                UNUserNotificationCenter.current().setBadgeCount(0) { _ in }
                                model.show(n > 1 ? "\(n) discussions marquées comme lues" : "Discussion marquée comme lue")
                            } label: {
                                Label("Tout marquer comme lu", systemImage: "checkmark.message")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("Plus")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { sheet = .newChat } label: { Image(systemName: "square.and.pencil") }
                        .accessibilityLabel("Nouvelle discussion")
                }
            }
            .sheet(item: $sheet) { s in
                switch s {
                case .newChat: NewChatSheet(sheet: $sheet)
                case .add(let code): AddContactSheet(code: code)
                case .group: NewGroupSheet()
                case .myCode: MyCodeSheet()
                case .starred: StarredSheet()
                }
            }
            .onChange(of: model.invite, initial: true) { _, code in
                guard let code else { return }
                model.invite = nil
                sheet = .add(code)
            }
        }
    }

    /// Les lignes de la liste : l’état du réseau, le filtre, les discussions,
    /// les messages trouvés.
    @ViewBuilder func rows(_ e: Engine) -> some View {
        if e.netState != .online {
            Label(e.netState == .connecting ? "Connexion…" : "Hors ligne : les messages partiront au retour du réseau.",
                  systemImage: "wifi.slash")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        Picker("Filtre", selection: $filter) {
            ForEach(Filter.allCases, id: \.self) { Text($0.rawValue) }
        }
        .pickerStyle(.segmented)
        .listRowSeparator(.hidden)
        ForEach(chats(e)) { chat in
            NavigationLink(value: chat.id) {
                ChatRow(engine: e, chat: chat)
            }
            .swipeActions(edge: .leading) {
                Button {
                    e.updateChat(chat.id) {
                        if $0.unread > 0 || $0.markedUnread {
                            $0.unread = 0; $0.markedUnread = false; $0.mentioned = false
                        } else { $0.markedUnread = true }
                    }
                    if chat.unread > 0 { e.markRead(chat.id) }
                } label: {
                    Label(chat.unread > 0 || chat.markedUnread ? "Lu" : "Non lu",
                          systemImage: chat.unread > 0 || chat.markedUnread ? "checkmark.message" : "message.badge")
                }
                .tint(.blue)
                Button {
                    e.updateChat(chat.id) { $0.pinned.toggle() }
                } label: {
                    Label(chat.pinned ? "Désépingler" : "Épingler", systemImage: chat.pinned ? "pin.slash" : "pin")
                }
                .tint(.orange)
            }
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) { e.deleteChat(chat.id) } label: { Label("Supprimer", systemImage: "trash") }
                Button {
                    e.updateChat(chat.id) { $0.archived.toggle() }
                } label: {
                    Label(chat.archived ? "Désarchiver" : "Archiver", systemImage: "archivebox")
                }
                .tint(.gray)
                Button {
                    if chat.muted { e.mute(chat.id, seconds: 0) } else { muting = chat.id }
                } label: {
                    Label(chat.muted ? "Son" : "Silence", systemImage: chat.muted ? "bell" : "bell.slash")
                }
                .tint(.indigo)
            }
        }
        // La recherche trouve aussi les messages de toutes les
        // discussions (comme le navigateur).
        let found = e.searchMessages(query)
        if !found.isEmpty {
            Section("Messages") {
                ForEach(found, id: \.key) { m in
                    Button {
                        model.reveal = m.id
                        model.openChat(m.chat)
                    } label: {
                        FoundRow(engine: e, message: m)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    func chats(_ e: Engine) -> [Chat] {
        let q = query.trimmingCharacters(in: .whitespaces)
        return e.chats.values
            .filter { c in
                switch filter {
                case .all: !c.archived
                case .unread: !c.archived && (c.unread > 0 || c.markedUnread)
                case .groups: !c.archived && c.group
                case .archived: c.archived
                }
            }
            .filter { c in
                q.isEmpty || e.title(c).localizedCaseInsensitiveContains(q) || c.id.contains(q.uppercased().replacingOccurrences(of: "-", with: ""))
                    || (c.last?.text.localizedCaseInsensitiveContains(q) ?? false)
            }
            .sorted { a, b in
                if a.pinned != b.pinned { return a.pinned }
                return max(a.lastAt, a.last?.at ?? 0) > max(b.lastAt, b.last?.at ?? 0)
            }
    }
}

/// Un message trouvé par la recherche : sa discussion, sa date, le passage.
struct FoundRow: View {
    let engine: Engine
    let message: Message

    var body: some View {
        let e = engine
        let m = message
        let who = m.from == e.me.code ? "Vous : " : e.isGroup(m.chat) ? "\(e.displayName(m.from)) : " : ""
        HStack(spacing: 12) {
            e.avatar(m.chat, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(e.chat(m.chat).map(e.title) ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                    Spacer()
                    Text(Dates.list(m.at)).font(.caption).foregroundStyle(.secondary)
                }
                Text(who + e.readable(m)).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct ChatRow: View {
    let engine: Engine
    let chat: Chat

    var body: some View {
        let e = engine
        let typing = e.typing[chat.id] ?? []
        HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                e.presenceAvatar(chat.id, size: 52)
                if !chat.group, e.presence[chat.id]?.online == true {
                    Circle().fill(Color.talkaAccent).frame(width: 14, height: 14)
                        .overlay(Circle().stroke(Color(.systemBackground), lineWidth: 2))
                }
            }
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(e.title(chat)).font(.body.weight(.semibold)).lineLimit(1)
                    Spacer()
                    if let last = chat.last {
                        Text(Dates.list(last.at))
                            .font(.caption)
                            .foregroundStyle(chat.unread > 0 ? Color.talkaAccent : .secondary)
                    }
                }
                HStack(spacing: 4) {
                    if !typing.isEmpty {
                        Text(chat.group ? "\(typing.map(e.displayName).joined(separator: ", ")) écrit…" : "écrit…")
                            .foregroundStyle(Color.talkaGreen)
                            .lineLimit(1)
                    } else if !chat.draft.isEmpty {
                        (Text("Brouillon : ").foregroundColor(.red) + Text(chat.draft)).lineLimit(1)
                    } else if let last = chat.last {
                        if last.from == e.me.code, last.kind != "system", let m = e.store.message(chat.id, last.id) {
                            TickView(tick: m.tick)
                        }
                        Text(chat.group && last.from != e.me.code && last.from != "system" && !last.from.isEmpty
                             ? "\(e.displayName(last.from)) : \(last.text)" : last.text)
                            .lineLimit(1)
                    } else {
                        Text(chat.group ? "Groupe créé" : "Dites bonjour 👋").lineLimit(1)
                    }
                    Spacer()
                    if chat.muted { Image(systemName: "bell.slash.fill").font(.caption) }
                    if chat.pinned { Image(systemName: "pin.fill").font(.caption) }
                    if chat.mentioned {
                        // On nous a mentionné : « @ », même en sourdine.
                        Text("@")
                            .font(.caption.bold())
                            .foregroundStyle(.white)
                            .frame(minWidth: 20, minHeight: 20)
                            .background(Color.talkaAccent, in: .circle)
                            .accessibilityLabel("Vous êtes mentionné")
                    }
                    if chat.unread > 0 || chat.markedUnread {
                        Text(chat.unread > 0 ? "\(chat.unread)" : " ")
                            .font(.caption.bold())
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .frame(minWidth: 20, minHeight: 20)
                            .background(chat.muted ? Color.gray : Color.talkaAccent, in: .capsule)
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }
}

/// ✓, ✓✓, ✓✓ bleus, horloge, erreur.
struct TickView: View {
    let tick: Message.Tick

    var body: some View {
        switch tick {
        case .pending: Image(systemName: "clock").font(.caption2).accessibilityLabel("En attente")
        case .sent: Image(systemName: "checkmark").font(.caption2.bold()).accessibilityLabel("Envoyé")
        case .delivered: DoubleCheck().accessibilityLabel("Distribué")
        case .read: DoubleCheck().foregroundStyle(Color.tickBlue).accessibilityLabel("Lu")
        case .failed: Image(systemName: "exclamationmark.circle.fill").font(.caption).foregroundStyle(.red).accessibilityLabel("Échec")
        }
    }
}

struct DoubleCheck: View {
    var body: some View {
        ZStack {
            Image(systemName: "checkmark").offset(x: -3)
            Image(systemName: "checkmark").offset(x: 3)
        }
        .font(.caption2.bold())
        .padding(.horizontal, 3)
    }
}

// MARK: Nouvelle discussion

struct NewChatSheet: View {
    @Environment(AppModel.self) private var model
    @Binding var sheet: ChatsTab.Sheet?

    var body: some View {
        ContactPicker(title: "Nouvelle discussion") {
            Section {
                Button { sheet = .add("") } label: { Label("Ajouter un contact par son code", systemImage: "person.badge.plus") }
                Button { sheet = .group } label: { Label("Nouveau groupe", systemImage: "person.3") }
                Button { sheet = .myCode } label: { Label("Mon code et mon QR code", systemImage: "qrcode") }
            }
        } pick: { code in
            sheet = nil
            model.openChat(code)
        }
    }
}

struct AddContactSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State var code: String
    @State private var name = ""
    @State private var scanning = false
    @State private var busy = false
    @State private var error: String?

    init(code: String) { _code = State(initialValue: code.isEmpty ? "" : TalkaCode.pretty(code)) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Code (ex. K7Q2M-X9PRT)", text: $code)
                        .font(.body.monospaced())
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .accessibilityLabel("Code")
                    TextField("Nom (facultatif)", text: $name)
                        .textContentType(.name)
                    Button { scanning = true } label: { Label("Scanner un QR code", systemImage: "qrcode.viewfinder") }
                } footer: {
                    Text("Demandez son code Talka à votre contact, ou scannez son QR code.")
                }
                if let error {
                    Text(error).foregroundStyle(.red)
                }
            }
            .navigationTitle("Ajouter un contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if busy { ProgressView() } else { Button("Ajouter", action: add).disabled(TalkaCode.normalize(code) == nil) }
                }
            }
            .sheet(isPresented: $scanning) {
                QRScanner { text in
                    scanning = false
                    if let c = TalkaCode.find(in: text) {
                        if let fp = TalkaCode.fingerprint(in: text) { model.engine?.expectFingerprint(c, fp) }
                        code = TalkaCode.pretty(c)
                    } else { error = "Ce QR code n'est pas un code Talka." }
                }
                .ignoresSafeArea()
            }
        }
    }

    func add() {
        guard let c = TalkaCode.normalize(code), let e = model.engine else { return }
        busy = true
        error = nil
        Task {
            do {
                let reachable = try await e.addContact(c, name: name)
                busy = false
                dismiss()
                model.openChat(c)
                if !reachable { model.show("Contact ajouté. Ses clés arriveront à sa prochaine connexion.") }
            } catch {
                busy = false
                self.error = error.localizedDescription
            }
        }
    }
}

/// Le lecteur de QR code (la caméra).
struct QRScanner: UIViewControllerRepresentable {
    let found: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let c = ScannerController()
        c.found = found
        return c
    }

    func updateUIViewController(_ c: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var found: ((String) -> Void)?
        let session = AVCaptureSession()
        var done = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input)
            else {
                let label = UILabel()
                label.text = "Caméra indisponible"
                label.textColor = .white
                label.frame = view.bounds
                label.textAlignment = .center
                view.addSubview(label)
                return
            }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.layer.bounds
            view.layer.addSublayer(preview)
            let session = session
            DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            view.layer.sublayers?.first { $0 is AVCaptureVideoPreviewLayer }?.frame = view.layer.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            let session = session
            DispatchQueue.global(qos: .userInitiated).async { session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done, let text = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            done = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            found?(text)
        }
    }
}

// MARK: Groupe

struct NewGroupSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var members: Set<String> = []
    @State private var avatar = ""
    @State private var photo: PhotosPickerItem?

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                Form {
                    Section {
                        HStack(spacing: 14) {
                            PhotosPicker(selection: $photo, matching: .images) {
                                Avatar(id: "group", name: name, image: avatar, group: true, size: 56)
                            }
                            TextField("Nom du groupe", text: $name)
                        }
                    }
                    Section("Membres (\(members.count))") {
                        let contacts = e.contacts.values.filter { $0.saved && !$0.blocked }
                            .sorted { e.displayName($0.code).localizedCompare(e.displayName($1.code)) == .orderedAscending }
                        if contacts.isEmpty { Text("Ajoutez d'abord des contacts.").foregroundStyle(.secondary) }
                        ForEach(contacts) { c in
                            Button {
                                if members.contains(c.code) { members.remove(c.code) } else { members.insert(c.code) }
                            } label: {
                                HStack(spacing: 12) {
                                    e.avatar(c.code, size: 36)
                                    Text(e.displayName(c.code)).foregroundStyle(.primary)
                                    Spacer()
                                    Image(systemName: members.contains(c.code) ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(members.contains(c.code) ? Color.talkaGreen : .secondary)
                                }
                            }
                        }
                    }
                }
                .navigationTitle("Nouveau groupe")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Annuler") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Créer") {
                            let g = e.createGroup(name: String(name.trimmingCharacters(in: .whitespaces).prefix(60)), members: Array(members), avatar: avatar)
                            dismiss()
                            model.openChat(g.id)
                        }
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || members.isEmpty)
                    }
                }
                .onChange(of: photo) { _, item in
                    Task {
                        if let data = try? await item?.loadTransferable(type: Data.self), let ui = UIImage(data: data) {
                            avatar = ui.avatarDataURL() ?? ""
                        }
                    }
                }
            }
        }
    }
}

// MARK: Mon code

struct MyCodeSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                let link = model.inviteLink(e.me.code)
                ScrollView {
                    VStack(spacing: 20) {
                        e.avatar(e.me.code, size: 72)
                        Text(e.profile.name).font(.title2.bold())
                        if let qr = QR.image(link.absoluteString) {
                            Image(uiImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .frame(maxWidth: 240)
                                .padding(12)
                                .background(.white, in: .rect(cornerRadius: 16))
                                .accessibilityLabel("QR code de votre code Talka")
                        }
                        CodeBadge(code: e.me.code)
                        Text("Vos contacts scannent ce QR code ou saisissent votre code pour vous écrire.")
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                        HStack {
                            Button {
                                UIPasteboard.general.string = TalkaCode.pretty(e.me.code)
                                model.show("Code copié")
                            } label: {
                                Label("Copier", systemImage: "doc.on.doc").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.bordered)
                            ShareLink(item: link, message: Text("Écris-moi sur Talka ! Mon code : \(TalkaCode.pretty(e.me.code))")) {
                                Label("Partager", systemImage: "square.and.arrow.up").frame(maxWidth: .infinity)
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                    .padding(24)
                }
                .navigationTitle("Mon code")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } } }
            }
        }
    }
}

// MARK: Messages importants

struct StarredSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            if let e = model.engine {
                let _ = e.revisions
                let list = e.starred()
                List(list, id: \.key) { m in
                    Button {
                        dismiss()
                        model.openChat(m.chat)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(e.displayName(m.from)).font(.subheadline.bold())
                                if let c = e.chat(m.chat), c.group { Text("· \(e.title(c))").font(.subheadline).foregroundStyle(.secondary) }
                                Spacer()
                                Text(Dates.list(m.at)).font(.caption).foregroundStyle(.secondary)
                            }
                            Text(e.readable(m)).foregroundStyle(.primary).lineLimit(3)
                        }
                    }
                }
                .overlay { if list.isEmpty { ContentUnavailableView("Aucun message important", systemImage: "star") } }
                .navigationTitle("Messages importants")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("OK") { dismiss() } } }
            }
        }
    }
}
