//! Démarre Talka sur `PORT` (fourni par Heroku), 3000 sinon. Aucune base de
//! données : le serveur relaie, les navigateurs gardent.
//!
//! `talka export <dossier>` écrit la version GitHub Pages (voir `talka::export`).
//! `talka vapid` fabrique une clé pour les notifications (voir `talka::push`).

fn main() -> std::io::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("export") {
        let dir = std::path::PathBuf::from(args.get(2).map_or("dist", String::as_str));
        let site = talka::export::site_from_env();
        return tokio::runtime::Runtime::new()?.block_on(talka::export::run(&dir, &site));
    }
    if args.get(1).map(String::as_str) == Some("vapid") {
        let secret = talka::push::Vapid::generate().ok_or(std::io::ErrorKind::Other)?;
        println!("TALKA_VAPID_KEY={secret}");
        return Ok(());
    }
    if talka::push::vapid().is_none() {
        println!("ℹ️  sans TALKA_VAPID_KEY : pas de notifications quand Talka est fermé");
    }
    println!("Talka 💬 écrit avec Paquets 📦");
    if !talka::assets::has_client() {
        println!("⚠️  le navigateur n'est pas compilé : lancez `cargo xtask web`, puis recompilez");
    }
    talka::app().run()
}
