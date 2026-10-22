//! Le site de présentation de Talka (`/`), la page de confidentialité, la
//! coquille de l'application (`/app`) et la page 404 : des pages Paquets,
//! rendues par le serveur avec leurs balises SEO.

use paquets::prelude::*;

use crate::{Site, assets};

const DESCRIPTION: &str = "Talka, la messagerie où un code remplace votre numéro de téléphone : \
discussions, groupes, appels audio et vidéo, vocaux, statuts. Chiffrée de bout en bout, sans \
compte et sans base de données : tout reste dans votre navigateur.";

/// La fenêtre du téléphone (sous l'encoche, clavier qui redimensionne la
/// page), puis ce qui permet de charger le navigateur (Rust compilé en WebAssembly) et
/// de lui dire où il est : la page (`site` ou `app`), le chemin du site, le
/// relais, et si c'est l'export statique (GitHub Pages).
fn head_tags(site: &Site, page: &str) -> String {
    let manifest = if site.export {
        format!(
            r#"<link rel="manifest" href="{}"/>"#,
            attr(&site.url("/manifest.webmanifest"))
        )
    } else {
        String::new()
    };
    format!(
        r##"<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content"/><link rel="icon" href="{logo}" type="image/svg+xml"/><link rel="apple-touch-icon" href="{touch}"/>{manifest}<meta name="mobile-web-app-capable" content="yes"/><meta name="apple-mobile-web-app-capable" content="yes"/><meta name="apple-mobile-web-app-title" content="Talka"/><meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"/><meta name="color-scheme" content="light dark"/><meta name="theme-color" content="#0e6b63"/><meta name="talka-page" content="{page}"/><meta name="talka-base" content="{base}"/><meta name="talka-api" content="{api}"/><meta name="talka-static" content="{export}"/><link rel="preload" href="{wasm}" as="fetch" type="application/wasm" crossorigin=""/><script type="module" src="{js}"></script>"##,
        logo = attr(&site.url("/logo.svg")),
        touch = attr(&site.url("/icons/apple-touch-icon.png")),
        base = attr(&site.base),
        api = attr(&site.api),
        export = if site.export { "1" } else { "" },
        wasm = attr(&site.url(&assets::url(assets::WASM))),
        js = attr(&site.url(&assets::url(assets::WASM_JS))),
    )
}

/// Une valeur d'attribut HTML.
fn attr(v: &str) -> String {
    v.replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('<', "&lt;")
}

fn site_page(site: &Site, title: &str, description: &str) -> Page {
    Page::new(title)
        .lang("fr")
        .description(description)
        .seo(
            Seo::new()
                .site_name("Talka")
                .locale("fr_FR")
                .image(site.url("/icons/icon-512.png")),
        )
        .plain()
        .head(head_tags(site, "site"))
        .stylesheet(site.url(&assets::url("site.css")))
}

fn logo(site: &Site) -> Element {
    a().class("brand")
        .href(site.url("/"))
        .attr("aria-label", "Talka, accueil")
        .child(
            img()
                .attr("src", site.url("/logo.svg"))
                .attr("alt", "")
                .attr("width", "36")
                .attr("height", "36"),
        )
        .child(span().text("Talka"))
}

fn site_header(site: &Site) -> Element {
    header().class("site-header").child(
        div()
            .class("wrap bar")
            .child(logo(site))
            .child(
                nav()
                    .class("links")
                    .attr("aria-label", "Sections")
                    .child(
                        a().href(site.url("/#fonctionnalites"))
                            .text("Fonctionnalités"),
                    )
                    .child(a().href(site.url("/#code")).text("Le code"))
                    .child(a().href(site.url("/#securite")).text("Sécurité"))
                    .child(a().href(site.url("/#faq")).text("FAQ")),
            )
            .child(
                a().class("button small")
                    .href(site.url("/app/"))
                    .text("Ouvrir Talka"),
            ),
    )
}

fn site_footer(site: &Site) -> Element {
    footer()
        .class("site-footer")
        .child(
            div()
                .class("wrap foot")
                .child(
                    div().child(logo(site)).child(
                        p().class("muted").text(
                            "La messagerie sans numéro, sans compte et sans base de données.",
                        ),
                    ),
                )
                .child(
                    nav()
                        .class("foot-links")
                        .child(a().href(site.url("/app/")).text("L'application"))
                        .child(
                            a().href(site.url("/confidentialite/"))
                                .text("Confidentialité"),
                        )
                        .child(a().href(site.url("/#faq")).text("Questions"))
                        .child(
                            a().href("https://github.com/maxlestage/Paquets")
                                .attr("rel", "noopener")
                                .text("Fait avec Paquets 📦"),
                        ),
                ),
        )
        .child(
            div().class("wrap").child(
                p().class("muted tiny")
                    .text("© 2026 Talka · Maxime Nathan Lestage"),
            ),
        )
}

/// Un message du téléphone de démonstration.
fn bubble(out: bool, text: &str, time: &str) -> Element {
    div()
        .class(if out { "bubble out" } else { "bubble in" })
        .child(span().text(text.to_owned()))
        .child(small().text(time.to_owned()).child(if out {
            span().class("ticks").text(" ✓✓")
        } else {
            span()
        }))
}

/// Une icône en trait (comme celles de l'application), en SVG.
fn icon(path: &str) -> Element {
    span().class("ico").html(format!(
        r#"<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">{path}</svg>"#
    ))
}

const VIDEO: &str =
    r#"<rect x="2" y="6" width="14" height="12" rx="3"/><path d="m16 10 6-3v10l-6-3"/>"#;
const CALL: &str = r#"<path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1.9.4 1.8.7 2.7a2 2 0 0 1-.5 2.1L8 9.8a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.5c.9.3 1.8.6 2.7.7a2 2 0 0 1 1.7 2z"/>"#;
const MIC: &str =
    r#"<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5"/>"#;
const PLAY: &str = r#"<path d="M7 4v16l13-8z" fill="currentColor"/>"#;

fn phone() -> Element {
    // L'application d'aujourd'hui : des bulles de verre sur une photo.
    let bars = [3, 5, 8, 11, 7, 4, 6, 10, 12, 8, 5, 3, 6, 9, 5, 3];
    let mut wave = div().class("wave");
    for h in bars {
        wave = wave.child(span().attr("style", format!("height:{}px", h + 3)));
    }
    div()
        .class("phone")
        .attr("aria-hidden", "true")
        .child(
            div()
                .class("phone-top")
                .child(div().class("avatar").text("🦊").child(span().class("dot")))
                .child(
                    div()
                        .child(strong().text("Léa"))
                        .child(small().text("en ligne")),
                )
                .child(div().class("phone-icons").child(icon(VIDEO)).child(icon(CALL))),
        )
        .child(
            div()
                .class("phone-chat")
                .child(div().class("day").text("Aujourd'hui"))
                .child(div().class("e2e").text("🔒 Chiffré de bout en bout. Personne d'autre, pas même Talka, ne peut lire ces messages."))
                .child(bubble(false, "Salut ! C'est quoi ton code Talka ? 😊", "09:41"))
                .child(bubble(true, "K7Q2M-X9PRT, pas besoin de mon numéro 😉", "09:42"))
                .child(
                    div()
                        .class("bubble in reacted")
                        .child(span().text("Ajouté ! On s'appelle ce soir ?"))
                        .child(small().text("09:42"))
                        .child(span().class("reaction").text("❤️")),
                )
                .child(
                    div()
                        .class("bubble out voice")
                        .child(span().class("play").child(icon(PLAY)))
                        .child(wave)
                        .child(small().text("0:07 · 09:43").child(span().class("ticks").text(" ✓✓"))),
                )
                .child(bubble(false, "Parfait, à ce soir !", "09:44")),
        )
        .child(
            div()
                .class("phone-input")
                .child(span().text("😊  Message"))
                .child(span().class("mic").child(icon(MIC))),
        )
}

fn feature(icon: &str, title: &str, text: &str) -> Element {
    article()
        .class("feature reveal")
        .child(div().class("feature-icon").text(icon.to_owned()))
        .child(h3().text(title.to_owned()))
        .child(p().text(text.to_owned()))
}

/// Une ligne du tableau « ce que voit le relais ».
fn row(what: &str, now: &str, next: &str) -> Element {
    tr().child(th().attr("scope", "row").text(what.to_owned()))
        .child(td().text(now.to_owned()))
        .child(td().class("next").text(next.to_owned()))
}

/// Une étape de la v4, avec son état (« Disponible », « En cours », « Prévu »).
fn milestone(n: &str, state: &str, icon: &str, title: &str, text: &str) -> Element {
    article()
        .class("feature milestone reveal")
        .child(
            div()
                .class("milestone-head")
                .child(div().class("feature-icon").text(icon.to_owned()))
                .child(
                    span()
                        .class(match state {
                            "Disponible" => "milestone-state done",
                            "En cours" => "milestone-state now",
                            _ => "milestone-state",
                        })
                        .text(format!("{n} · {state}")),
                ),
        )
        .child(h3().text(title.to_owned()))
        .child(p().text(text.to_owned()))
}

fn step(n: &str, title: &str, text: &str) -> Element {
    li().class("step reveal")
        .child(span().class("step-n").text(n.to_owned()))
        .child(h3().text(title.to_owned()))
        .child(p().text(text.to_owned()))
}

fn faq(q: &str, a_: &str) -> Element {
    details()
        .class("faq-item")
        .child(summary().text(q.to_owned()))
        .child(p().text(a_.to_owned()))
}

/// La page d'accueil : le site de présentation.
pub fn home(site: &Site) -> Page {
    let hero = section()
        .class("hero")
        .child(
            div()
                .class("wrap hero-grid")
                .child(
                    div()
                        .class("hero-text")
                        .child(span().class("pill").text("🔒 Chiffré de bout en bout · Sans numéro"))
                        .child(h1().text("Parlez à tout le monde. Sans donner votre numéro."))
                        .child(p().class("lead").text(
                            "Talka, c'est la messagerie que vous connaissez : discussions, groupes, appels vidéo, \
                             vocaux, statuts. Sauf qu'ici, un code remplace votre numéro de téléphone, et tout \
                             reste dans votre navigateur.",
                        ))
                        .child(
                            div()
                                .class("cta")
                                .child(a().class("button").href(site.url("/app/")).text("Ouvrir Talka, c'est gratuit"))
                                .child(a().class("button ghost").href("#code").text("Comment ça marche ?")),
                        )
                        .child(
                            p().class("muted small")
                                .child(span().text("Aucune inscription · Rien à télécharger dans le navigateur · "))
                                .child(span().id("live-count").text("Aussi en app iPhone et Apple Watch")),
                        ),
                )
                .child(div().class("hero-phone").child(phone())),
        );

    let code_section = section()
        .id("code")
        .class("band")
        .child(
            div()
                .class("wrap two")
                .child(
                    div()
                        .class("reveal")
                        .child(span().class("kicker").text("Le système de code"))
                        .child(h2().text("Votre code remplace votre numéro."))
                        .child(p().class("lead").text(
                            "À la première ouverture, votre appareil crée vos clés de chiffrement. Votre code Talka \
                             en est l'empreinte : dix caractères, uniques au monde, que vous donnez à qui vous voulez.",
                        ))
                        .child(
                            ul().class("checks")
                                .child(li().text("Personne ne peut deviner votre numéro, votre e-mail ou votre nom."))
                                .child(li().text("Le code est vérifié mathématiquement : impossible de se faire passer pour vous."))
                                .child(li().text("Partagez-le par lien, par QR code ou de vive voix."))
                                .child(li().text("Ajoutez en plus un code de verrouillage pour protéger l'application.")),
                        ),
                )
                .child(
                    div()
                        .class("code-card reveal")
                        .child(small().class("muted").text("Mon code Talka"))
                        .child(div().class("code-big").text("K7Q2M-X9PRT"))
                        .child(div().class("qr-fake").attr("aria-hidden", "true"))
                        .child(small().class("muted").text("talka · /c/K7Q2MX9PRT")),
                ),
        )
        .child(
            ol().class("wrap steps")
                .child(step("1", "Ouvrez Talka", "Choisissez un nom et une photo. Vos clés sont créées sur votre appareil, en une seconde."))
                .child(step("2", "Partagez votre code", "Envoyez votre lien d'invitation ou montrez votre QR code. Ou entrez le code d'un ami."))
                .child(step("3", "Discutez", "Messages, photos, vocaux, appels : tout est chiffré avant même de quitter votre téléphone.")),
        );

    let features = section()
        .id("fonctionnalites")
        .class("section")
        .child(
            div()
                .class("wrap")
                .child(span().class("kicker").text("Tout ce que vous aimez"))
                .child(h2().text("Une vraie messagerie, du navigateur au poignet."))
                .child(
                    div()
                        .class("features")
                        .child(feature("💬", "Discussions", "Accusés de réception ✓✓, « en train d'écrire… », en ligne et vu à, réponses, transferts, recherche."))
                        .child(feature("👥", "Groupes", "Créez un groupe, nommez-le, ajoutez des membres avec leur code. Chaque message est chiffré pour chacun."))
                        .child(feature("📹", "Appels audio et vidéo", "En direct, d'appareil à appareil (WebRTC), avec micro coupé, caméra et historique des appels."))
                        .child(feature("🎙️", "Messages vocaux", "Maintenez, parlez, envoyez. Écoutez en 1×, 1,5× ou 2× ; les vocaux qui se suivent s'enchaînent. Même depuis la montre."))
                        .child(feature("📷", "Photos et fichiers", "Depuis la galerie ou l'appareil photo, compressées automatiquement ; documents jusqu'à 3 Mo, légendes."))
                        .child(feature("⭕", "Statuts", "Un texte coloré ou une photo, visible par vos contacts pendant 24 heures."))
                        .child(feature("😍", "Réactions et emojis", "Un double-tap pour ❤️, ou l'emoji de votre choix, et voyez qui a réagi ; modifiez ou supprimez pour tout le monde, gardez en important ⭐."))
                        .child(feature("📌", "Messages épinglés", "Gardez l'adresse, le code ou le rendez-vous en haut de la discussion, à un toucher."))
                        .child(feature("⏳", "Capsule temporelle", "Un message scellé jusqu'à la date choisie : une enveloppe et un compte à rebours, puis il s'ouvre tout seul. Introuvable ailleurs."))
                        .child(feature("🧹", "Messages qui s'effacent", "Par discussion : 24 heures, 7 jours ou 90 jours. Sur tout l'appareil : gardez vos messages de 24 heures à 1 an, ou toujours ; les importants ⭐ restent."))
                        .child(feature("🔐", "Code de verrouillage", "Un code à 4 ou 6 chiffres protège Talka quand vous prêtez votre appareil."))
                        .child(feature("💾", "Sauvegarde chiffrée", "Un fichier protégé par mot de passe, à garder dans Fichiers ou iCloud : il se restaure sur un iPhone comme dans un navigateur."))
                        .child(feature("📲", "Installable", "Ajoutez Talka à l'écran d'accueil : plein écran, notifications, hors ligne."))
                        .child(feature("🌙", "Thème clair et sombre", "Il suit votre appareil, ou choisissez. Un fond par discussion, une couleur ou l'une de vos photos, vu à travers des bulles de verre."))
                        .child(feature("📱", "App iPhone", "Talka en app native SwiftUI, qui discute avec le navigateur : mêmes codes, mêmes groupes, même chiffrement. Avec ses widgets."))
                        .child(feature("⌚", "Apple Watch", "Lisez vos discussions au poignet, répondez par un vocal, en dictant, d'un emoji ou d'une réponse rapide. Et des complications.")),
                ),
        );

    let no_db = section()
        .class("section band dark")
        .child(
            div()
                .class("wrap")
                .child(span().class("kicker").text("Pas de base de données"))
                .child(h2().text("Vos messages vivent chez vous. Nulle part ailleurs."))
                .child(p().class("lead").text(
                    "Talka n'a pas de base de données. Vos discussions, contacts, photos et clés sont rangés dans la \
                     mémoire de votre navigateur (Memoire ORM, sur IndexedDB) ; sur iPhone, dans une base SQLite de l'application. Le serveur n'est qu'un relais : il transmet des messages \
                     chiffrés qu'il ne sait pas lire, et les oublie dès que votre ami les a reçus.",
                ))
                .child(
                    div()
                        .class("flow reveal")
                        .child(div().class("node").child(strong().text("📱 Vous")).child(small().text("messages, clés, contacts")))
                        .child(div().class("arrow").text("🔒 → "))
                        .child(div().class("node relay").child(strong().text("🛰️ Relais Talka")).child(small().text("ne garde rien, ne lit rien")))
                        .child(div().class("arrow").text(" → 🔒"))
                        .child(div().class("node").child(strong().text("📱 Votre ami")).child(small().text("déchiffre chez lui"))),
                ),
        );

    let security = section()
        .id("securite")
        .class("section")
        .child(
            div()
                .class("wrap")
                .child(span().class("kicker").text("Sécurité"))
                .child(h2().text("Chiffré avant de partir. Vérifié à l'arrivée."))
                .child(
                    div()
                        .class("features three")
                        .child(feature("🔑", "Une clé par message", "Protocole v3 : sessions à double cliquet, établies en hybride X25519 + ML-KEM-768 (résistant aux ordinateurs quantiques). Chaque message a sa clé, effacée dès qu'elle a servi."))
                        .child(feature("🧾", "Le code est une preuve", "Votre code est l'empreinte SHA-256 de vos clés publiques : le serveur ne peut pas glisser les siennes à la place."))
                        .child(feature("✍️", "Connexion signée", "Pour recevoir vos messages, votre appareil signe un défi du serveur (ECDSA). Sans votre clé, pas de connexion."))
                        .child(feature("🧹", "Rien ne traîne", "Un message attend au plus 30 jours sur le relais s'il n'est pas livré, puis disparaît."))
                        .child(feature("🛡️", "Un serveur blindé", "Pare-feu applicatif, limitation de débit, politique de contenu stricte et HTTPS : le bouclier de Paquets."))
                        .child(feature("🙈", "Aucune trace", "Ni numéro, ni e-mail, ni carnet d'adresses envoyé. Entre contacts, le relais ne sait même pas qui écrit (expéditeur scellé). Pas de publicité, pas de pisteur.")),
                ),
        );

    // La v4, étape par étape (rien n'est annoncé comme disponible avant
    // d'être en ligne et vérifié).
    let v4 = section()
        .id("v4")
        .class("section band")
        .child(
            div()
                .class("wrap")
                .child(span().class("kicker").text("Talka v4, étape par étape"))
                .child(h2().text("Un relais qui ne sait plus rien."))
                .child(p().class("lead").text(
                    "Le relais ne lit rien et ne connaît ni votre numéro ni votre nom. \
                     Avec la v4, entre contacts, il ne sait plus ni qui écrit, ni à qui : \
                     les messages passent par des boîtes aux lettres anonymes qui changent chaque jour. \
                     Et votre compte vit sur plusieurs appareils à la fois.",
                ))
                .child(
                    table()
                        .class("compare reveal")
                        .child(
                            thead().child(
                                tr().child(th().text("Ce que voit le relais"))
                                    .child(th().text("Avant"))
                                    .child(th().text("Avec la v4")),
                            ),
                        )
                        .child(
                            tbody()
                                .child(row("Votre numéro, votre e-mail", "Jamais", "Jamais"))
                                .child(row("Le contenu des messages", "Chiffré, illisible", "Chiffré, illisible"))
                                .child(row("Qui écrit", "Son code", "Personne, entre contacts (un premier message à un inconnu montre encore son auteur)"))
                                .child(row("À qui", "Le code du destinataire", "Une boîte aux lettres qui change chaque jour, entre contacts"))
                                .child(row("Les notifications", "Un aperçu chiffré pour votre appareil : illisible", "Un aperçu chiffré pour votre appareil : illisible")),
                        ),
                )
                .child(
                    div()
                        .class("features roadmap")
                        .child(milestone("1", "Disponible", "🔔", "Notifications chiffrées", "App fermée, la notification montre qui écrit et quoi, « Léa : On se voit à 19 h ? ». Cet aperçu est chiffré par votre contact pour votre appareil : ni le relais, ni Google ou Apple ne peuvent le lire."))
                        .child(milestone("2", "Disponible", "📱", "Plusieurs appareils", "Le même compte sur l'iPhone, l'iPad et l'ordinateur, liés par un QR code. Chaque appareil a ses propres clés ; chaque message part chiffré pour chacun, et vos appareils restent synchronisés."))
                        .child(milestone("3", "Disponible", "🕶️", "Expéditeur scellé", "Le relais ne voit plus qui envoie : un jeton de remise, donné seulement à vos contacts, prouve qu'on a le droit d'écrire, sans dire qui l'on est."))
                        .child(milestone("4", "Disponible", "📮", "Boîtes aux lettres tournantes", "Entre contacts, les messages ne sont plus adressés à votre code mais à une boîte pseudonyme renouvelée chaque jour, que seuls vous et votre contact savez calculer.")),
                )
                .child(p().class("muted small").text(
                    "Compatible : un appareil v4 parle v3 avec un appareil qui ne l'a pas encore. \
                     La spécification, les vecteurs de test communs au navigateur et à l'iPhone et le code sont publics.",
                )),
        );

    let built = section()
        .class("section band")
        .child(
            div()
                .class("wrap two")
                .child(
                    div()
                        .class("reveal")
                        .child(span().class("kicker").text("Sous le capot"))
                        .child(h2().text("Propulsé par Paquets 📦"))
                        .child(p().class("lead").text(
                            "Le serveur de Talka est une application Paquets, le framework full-stack en Rust : \
                             Vitesse pour le HTTP et la WebSocket, Securite pour le bouclier, Active pour ces pages \
                             rendues par le serveur. Et l'application elle-même est écrite en Rust, compilée en WebAssembly.",
                        ))
                        .child(a().class("button ghost").href("https://github.com/maxlestage/Paquets").attr("rel", "noopener").text("Découvrir Paquets")),
                )
                .child(
                    ul().class("stack reveal")
                        .child(li().child(strong().text("⚡ Vitesse")).child(span().text("HTTP, WebSocket du relais")))
                        .child(li().child(strong().text("🛡️ Securite")).child(span().text("WAF, débit, CSP, HTTPS")))
                        .child(li().child(strong().text("🌿 Active")).child(span().text("pages SEO, PWA")))
                        .child(li().child(strong().text("🦀 Navigateur")).child(span().text("Rust en WebAssembly, Memoire ORM, WebRTC")))
                        .child(li().child(strong().text("🍎 iPhone et Watch")).child(span().text("SwiftUI, SQLite, WatchConnectivity"))),
                ),
        );

    let faq_section = section()
        .id("faq")
        .class("section")
        .child(
            div()
                .class("wrap narrow")
                .child(span().class("kicker").text("Questions"))
                .child(h2().text("Questions fréquentes"))
                .child(faq("Faut-il un numéro de téléphone ou un e-mail ?", "Non. Talka ne vous demande qu'un nom à afficher. Votre code Talka est créé sur votre appareil et c'est lui que vous partagez."))
                .child(faq("Où sont gardés mes messages ?", "Sur votre appareil : dans le navigateur (Memoire ORM, sur IndexedDB), ou dans l'app iPhone (SQLite). Talka n'a pas de base de données sur un serveur. Si vous effacez les données du site, vos discussions disparaissent : pensez à la sauvegarde chiffrée dans les Réglages."))
                .child(faq("Et si mon ami n'est pas connecté ?", "Le relais garde le message chiffré jusqu'à ce que votre ami ouvre Talka (30 jours au plus), puis l'oublie. Vous voyez ✓ quand le message est parti, ✓✓ quand il est arrivé, ✓✓ en bleu quand il est lu."))
                .child(faq("Puis-je utiliser Talka sur plusieurs appareils ?", "Oui, jusqu'à six à la fois : iPhone, iPad, navigateur. Sur le nouvel appareil, ouvrez Talka et choisissez « J'ai déjà Talka sur un autre appareil » ; un QR code s'affiche. Sur l'appareil où Talka est déjà ouvert : Réglages → Appareils liés → Scanner son QR code. Vos discussions arrivent, chiffrées pour lui seul ; ensuite, tout reste synchronisé. Retirer un appareil l'efface."))
                .child(faq("Y a-t-il une app iPhone et Apple Watch ?", "Oui, en bêta sur TestFlight. L'app iPhone discute avec le navigateur (mêmes codes, mêmes groupes). L'app de la montre passe par l'iPhone : elle n'a pas de clés à elle, et rien ne passe par un serveur entre les deux."))
                .child(faq("Puis-je ouvrir Talka dans deux onglets ?", "Un seul onglet est actif à la fois, comme sur WhatsApp Web : le dernier ouvert prend la main, l'autre vous propose « Utiliser ici »."))
                .child(faq("Mes messages s'effacent-ils ?", "Seulement si vous le choisissez. Par discussion, les messages éphémères disparaissent après 24 heures, 7 jours ou 90 jours. Pour tout l'appareil, le réglage « Conserver les messages » efface ce qui est plus vieux que la durée choisie (3 mois par défaut, ou toujours garder) ; les messages importants ⭐ restent."))
                .child(faq("À quoi sert le code de verrouillage ?", "C'est un code à 4 ou 6 chiffres que vous choisissez. Il est demandé à l'ouverture de Talka, pour que personne ne lise vos discussions sur votre appareil."))
                .child(faq("Les appels passent-ils par le serveur ?", "Non : l'audio et la vidéo vont directement d'un appareil à l'autre (WebRTC, chiffré DTLS-SRTP). Le serveur ne sert qu'à vous mettre en relation."))
                .child(faq("Est-ce gratuit ?", "Oui. Pas d'abonnement, pas de publicité.")),
        );

    let cta = section().class("section final").child(
        div()
            .class("wrap center")
            .child(h2().text("Votre code vous attend."))
            .child(
                p().class("lead")
                    .text("Ouvrez Talka, choisissez un nom, et partagez votre code. C'est tout."),
            )
            .child(
                a().class("button big")
                    .href(site.url("/app/"))
                    .text("Ouvrir Talka"),
            ),
    );

    site_page(
        site,
        "Talka — la messagerie où un code remplace votre numéro",
        DESCRIPTION,
    )
    .body(site_header(site))
    .body(
        html::main()
            .child(hero)
            .child(code_section)
            .child(features)
            .child(no_db)
            .child(security)
            .child(v4)
            .child(built)
            .child(faq_section)
            .child(cta),
    )
    .body(site_footer(site))
}

/// La page de confidentialité.
pub fn privacy(site: &Site) -> Page {
    let item = |title: &str, text: &str| {
        div()
            .class("legal-item")
            .child(h2().text(title.to_owned()))
            .child(p().text(text.to_owned()))
    };
    site_page(
        site,
        "Confidentialité — Talka",
        "Ce que Talka garde, et surtout ce qu'il ne garde pas.",
    )
    .body(site_header(site))
    .body(
        html::main().class("section").child(
            div()
                .class("wrap narrow legal")
                .child(h1().text("Confidentialité"))
                .child(p().class("lead").text("Talka est conçu pour savoir le moins de choses possible sur vous."))
                .child(item("Pas de compte, pas de base de données", "Talka ne vous demande ni numéro, ni e-mail, ni mot de passe. Il n'y a pas de base de données : vos messages, contacts, photos, statuts et clés sont rangés sur votre appareil : dans votre navigateur (Memoire ORM, sur IndexedDB) ou dans l'app iPhone (SQLite, protégée par iOS). Ils y sont gardés chiffrés (AES-256), avec une clé que le navigateur ne laisse pas sortir ou que l'iPhone garde dans son trousseau : Talka les affiche en clair, mais une copie des fichiers ne montre rien. Protégés en plus par votre code de verrouillage, une passkey ou Face ID, ils ne s'ouvrent pas sans vous. L'Apple Watch ne reçoit de l'iPhone qu'un résumé de vos discussions récentes, directement, sans passer par un serveur."))
                .child(item("Ce que voit le relais", "Pour transmettre un message, le serveur voit le code du destinataire, la taille et l'heure du message chiffré ; il ne peut pas en lire le contenu. Entre contacts, il ne sait pas qui écrit (expéditeur scellé) : il voit seulement le code de l'expéditeur d'un tout premier message à quelqu'un, et votre adresse IP. Il voit aussi qui est en ligne et quels codes vous suivez (pour la présence). Il garde en mémoire vive vos clés publiques et votre paquet de clés de session, signé (pour que vos contacts puissent vous écrire) et l'heure de votre dernière connexion. Tout cela disparaît quand le serveur redémarre."))
                .child(item("Notifications", "Si vous activez les notifications du navigateur, le relais garde en mémoire vive l'adresse que votre navigateur lui donne, et envoie au service de notifications de ce navigateur (Google, Mozilla, Apple ou Microsoft) un réveil qui porte l'aperçu du message (qui écrit, et quoi), chiffré par votre contact pour la clé de notification de votre navigateur : ni le relais ni ce service ne peuvent le lire, seul votre appareil le déchiffre. Ce service apprend seulement que vous avez reçu quelque chose, et quand. Désactivez les notifications, ou effacez vos données, et l'adresse est oubliée."))
                .child(item("Messages en attente", "Un message destiné à quelqu'un qui n'est pas connecté attend, chiffré, en mémoire vive, au plus 30 jours. Il est effacé dès que le destinataire confirme l'avoir reçu."))
                .child(item("Appels", "L'audio et la vidéo passent directement d'un appareil à l'autre. Pour établir la connexion, vos appareils peuvent contacter un serveur STUN public, qui voit votre adresse IP."))
                .child(item("Effacer vos données", "Réglages → Supprimer toutes mes données : tout ce que Talka a rangé sur votre appareil est effacé. Pensez à exporter une sauvegarde avant, si vous voulez la garder."))
                .child(item("Cookies et pisteurs", "Aucun. Talka n'utilise ni cookie publicitaire, ni outil de mesure d'audience.")),
        ),
    )
    .body(site_footer(site))
}

/// La coquille de l'application : le navigateur, en Rust compilé en
/// WebAssembly, fait le reste.
pub fn app_shell(site: &Site) -> Page {
    Page::new("Talka")
        .lang("fr")
        .description(DESCRIPTION)
        .seo(Seo::new().site_name("Talka"))
        .plain()
        .head(head_tags(site, "app"))
        .stylesheet(site.url(&assets::url("app.css")))
        // L'écran de chargement, affiché dès la première image, avant le
        // module WebAssembly : l'application le remplace une fois prête, et
        // y écrit l'étape en cours (`#boot-step`).
        .body(
            div()
                .id("root")
                .class("boot")
                .attr("role", "status")
                .attr("aria-live", "polite")
                .child(
                    img()
                        .class("boot-logo")
                        .attr("src", site.url("/logo.svg"))
                        .attr("alt", "")
                        .attr("width", "88")
                        .attr("height", "88"),
                )
                .child(p().class("boot-name").text("Talka"))
                .child(div().class("boot-bar").child(span()))
                .child(
                    p().id("boot-step")
                        .class("boot-step")
                        .text("Ouverture de Talka…"),
                )
                .child(
                    p().class("boot-note")
                        .text("Vos messages restent sur cet appareil, chiffrés de bout en bout."),
                ),
        )
        .body(
            noscript().text("Talka a besoin de JavaScript (pour charger son module WebAssembly)."),
        )
}

/// La page 404.
pub fn not_found(site: &Site) -> Page {
    site_page(site, "Page introuvable — Talka", "Cette page n'existe pas.")
        .seo(Seo::new().noindex(true))
        .status(404)
        .body(site_header(site))
        .body(
            html::main().class("section final").child(
                div()
                    .class("wrap center")
                    .child(h1().text("Cette page s'est perdue 🫥"))
                    .child(p().class("lead").text("Elle n'existe pas, ou plus."))
                    .child(
                        a().class("button")
                            .href(site.url("/"))
                            .text("Retour à l'accueil"),
                    ),
            ),
        )
}
