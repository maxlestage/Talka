// L'œuf de Pâques 🥦, comme dans le navigateur : un message qui parle de
// brocoli fait pleuvoir des brocolis sur la conversation. Rien ne voyage en
// plus : l'iPhone regarde le texte qu'il a déjà déchiffré.

import SwiftUI

struct BrocoliRain: View {
    struct Drop: Identifiable {
        let id: Int
        let x: CGFloat
        let delay: Double
        let duration: Double
        let size: CGFloat
        let turn: Double
    }

    /// Tirés une fois : la pluie ne change pas à chaque rendu.
    @State private var drops = (0..<36).map { i in
        Drop(id: i, x: CGFloat.random(in: 0...1), delay: Double.random(in: 0...1.2),
             duration: Double.random(in: 2.2...3.8), size: CGFloat.random(in: 22...44),
             turn: Double.random(in: -360...360))
    }
    @State private var start = Date()

    var body: some View {
        TimelineView(.animation) { timeline in
            let t = timeline.date.timeIntervalSince(start)
            GeometryReader { geo in
                ForEach(drops) { d in
                    let p = max(0, (t - d.delay) / d.duration)
                    Text("🥦")
                        .font(.system(size: d.size))
                        .rotationEffect(.degrees(d.turn * p))
                        .position(x: d.x * geo.size.width, y: -40 + p * (geo.size.height + 80))
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
