//! Démarre Talka sur `PORT` (fourni par Heroku), 3000 sinon. Aucune base de
//! données : le serveur relaie, les navigateurs gardent.
//!
//! `talka export <dossier>` écrit la version GitHub Pages (voir `talka::export`).

fn main() -> std::io::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) == Some("export") {
        let dir = std::path::PathBuf::from(args.get(2).map_or("dist", String::as_str));
        let site = talka::export::site_from_env();
        return tokio::runtime::Runtime::new()?.block_on(talka::export::run(&dir, &site));
    }
    println!("Talka 💬 écrit avec Paquets 📦");
    if !talka::assets::has_client() {
        println!("⚠️  le navigateur n'est pas compilé : lancez `cargo xtask web`, puis recompilez");
    }
    talka::app().run()
}
