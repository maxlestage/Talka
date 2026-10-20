// Talka sur l'Apple Watch : les discussions récentes de l'iPhone, lues,
// répondues (dictée, réponses rapides, emoji) et marquées comme lues depuis
// le poignet. L'iPhone reste l'appareil Talka : la montre passe par lui.

import SwiftUI

@main
struct TalkaWatchApp: App {
    @State private var model = WatchModel()

    var body: some Scene {
        WindowGroup {
            ChatsView()
                .environment(model)
                .tint(.talkaGreen)
        }
    }
}

extension Color {
    static let talkaGreen = Color(red: 0, green: 0.6588, blue: 0.5176)
    static let tickBlue = Color(red: 0.3255, green: 0.7412, blue: 0.9216)
    static let bubbleOut = Color(red: 0, green: 0.3608, blue: 0.2941)
    static let bubbleIn = Color(red: 0.1255, green: 0.1725, blue: 0.2)

    /// Les couleurs d'avatar, les mêmes que sur l'iPhone et dans le navigateur.
    static let avatars: [Color] = [
        .init(red: 0.898, green: 0.2588, blue: 0.6392), .init(red: 0.1216, green: 0.4784, blue: 0.9255),
        .init(red: 0.8275, green: 0.4196, blue: 0), .init(red: 0.0078, green: 0.651, blue: 0.5961),
        .init(red: 0.498, green: 0.4, blue: 1), .init(red: 0.7686, green: 0.3255, blue: 0.1765),
        .init(red: 0, green: 0.502, blue: 0.4118), .init(red: 0.651, green: 0.1725, blue: 0.4431),
    ]
}
