// Talka pour iPhone et iPad : la messagerie où un code remplace le numéro de
// téléphone. Le même protocole et le même chiffrement que le navigateur :
// un iPhone et un ordinateur discutent ensemble, dans les mêmes groupes.

import Combine
import SwiftUI
import TalkaKit

@main
struct TalkaApp: App {
    @State private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(.talkaGreen)
                .preferredColorScheme(model.colorScheme)
                .onOpenURL { model.open($0) }
                .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                    if let url = activity.webpageURL { model.open(url) }
                }
                .onChange(of: phase) { _, phase in model.scene(phase) }
        }
    }
}

/// Ce que montre l'aperçu d'iOS quand Talka n'est pas au premier plan.
struct PrivacyCover: View {
    var note: String?

    var body: some View {
        ZStack {
            Color.chatBackground.ignoresSafeArea()
            VStack(spacing: 12) {
                Image(systemName: "lock.fill").font(.system(size: 40)).foregroundStyle(Color.talkaGreen)
                Text("Talka").font(.title2.bold())
                if let note {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 30)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

extension Color {
    static let talkaGreen = Color(red: 0, green: 0.6588, blue: 0.5176)
    static let talkaDark = Color(red: 0, green: 0.502, blue: 0.4118)
    static let talkaAccent = Color(red: 0.1451, green: 0.8275, blue: 0.4)
    static let tickBlue = Color(red: 0.3255, green: 0.7412, blue: 0.9216)
    static let bubbleOut = Color(light: .init(red: 0.851, green: 0.9922, blue: 0.8275, alpha: 1), dark: .init(red: 0, green: 0.3608, blue: 0.2941, alpha: 1))
    static let bubbleIn = Color(light: .white, dark: .init(red: 0.1255, green: 0.1725, blue: 0.2, alpha: 1))
    static let chatBackground = Color(light: .init(red: 0.9373, green: 0.9176, blue: 0.8863, alpha: 1), dark: .init(red: 0.0431, green: 0.0784, blue: 0.102, alpha: 1))

    init(light: UIColor, dark: UIColor) {
        self.init(UIColor { $0.userInterfaceStyle == .dark ? dark : light })
    }
}

/// L'accueil, le verrou, ou l'application ; l'appel et les bandeaux par-dessus.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var phase

    var body: some View {
        ZStack(alignment: .top) {
            Group {
                if model.vaultLocked {
                    VaultLockView()
                } else if model.codeLocked {
                    // Le verrou (au-dessus) ouvre la base avec le code.
                    Color(.systemBackground).ignoresSafeArea()
                } else if model.engine == nil {
                    WelcomeView()
                } else {
                    MainView()
                }
            }
            if let toast = model.toast {
                ToastView(text: toast.text)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task(id: toast.id) {
                        try? await Task.sleep(for: .seconds(3))
                        withAnimation { if model.toast == toast { model.toast = nil } }
                    }
                    .onTapGesture { withAnimation { model.toast = nil } }
                    .zIndex(2)
            }
            if model.locked || model.codeLocked {
                LockView().zIndex(3).transition(.opacity)
            }
            if let step = model.loading {
                LoadingView(step: step).zIndex(4).transition(.opacity)
            }
            // Hors du premier plan (sélecteur d'apps, centre de contrôle) :
            // l'aperçu d'iOS ne montre ni les discussions ni les clés.
            if phase != .active, model.engine != nil {
                PrivacyCover().zIndex(5)
            }
            // L'écran enregistré, recopié (AirPlay) ou partagé : Talka se
            // cache (Réglages → Sécurité).
            if model.screenCaptured, model.hideWhenCaptured, model.engine != nil {
                PrivacyCover(note: "Talka est masqué pendant l'enregistrement ou la recopie de l'écran.").zIndex(5)
            }
        }
        .animation(.spring(duration: 0.3), value: model.toast)
        .animation(.easeInOut(duration: 0.2), value: model.locked)
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            model.screenCaptured = UIScreen.main.isCaptured
        }
        .fullScreenCover(item: Binding(get: { model.call }, set: { if $0 == nil { model.call = nil } })) { call in
            CallScreen(call: call)
        }
    }
}

struct ToastView: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(.white)
            .lineLimit(3)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: 520)
            .background(Color.black.opacity(0.85), in: .rect(cornerRadius: 14))
            .shadow(radius: 10, y: 4)
    }
}

/// Les quatre onglets, comme WhatsApp.
struct MainView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        TabView(selection: $model.tab) {
            ChatsTab()
                .tabItem { Label("Discussions", systemImage: "bubble.left.and.bubble.right.fill") }
                .badge(model.unreadTotal)
                .tag(AppModel.Tab.chats)
            StatusTab()
                .tabItem { Label("Actus", systemImage: "circle.dashed") }
                .tag(AppModel.Tab.status)
            CallsTab()
                .tabItem { Label("Appels", systemImage: "phone.fill") }
                .tag(AppModel.Tab.calls)
            SettingsTab()
                .tabItem { Label("Réglages", systemImage: "gearshape.fill") }
                .tag(AppModel.Tab.settings)
        }
    }
}

/// Le logo de Talka.
struct Logo: View {
    var size: CGFloat = 80

    var body: some View {
        Image("Logo")
            .resizable()
            .frame(width: size, height: size)
            .clipShape(.rect(cornerRadius: size * 0.23))
            .shadow(color: .talkaGreen.opacity(0.35), radius: 12, y: 6)
            .accessibilityHidden(true)
    }
}

/// L'écran de chargement : le logo qui respire, une barre qui avance,
/// l'étape en cours (comme le navigateur).
struct LoadingView: View {
    let step: String
    @State private var breathe = false
    @State private var slide = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Color("LaunchBackground").ignoresSafeArea()
            VStack(spacing: 14) {
                Logo(size: 88)
                    .scaleEffect(breathe ? 0.94 : 1)
                    .opacity(breathe ? 0.85 : 1)
                Text("Talka").font(.title.bold())
                Capsule()
                    .fill(Color(.systemGray5))
                    .frame(width: 180, height: 4)
                    .overlay(alignment: .leading) {
                        Capsule()
                            .fill(Color(red: 0.055, green: 0.42, blue: 0.39))
                            .frame(width: 72, height: 4)
                            .offset(x: slide ? 180 : -72)
                    }
                    .clipShape(.capsule)
                    .padding(.top, 4)
                Text(step).font(.callout)
                Text("Vos messages restent sur cet appareil, chiffrés de bout en bout.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 260)
            }
            .padding()
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Chargement de Talka. \(step)")
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.9).repeatForever(autoreverses: true)) { breathe = true }
            withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: false)) { slide = true }
        }
    }
}
