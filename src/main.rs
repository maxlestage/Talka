//! Démarre Talka sur `PORT` (fourni par Heroku), 3000 sinon. Aucune base de
//! données : le serveur relaie, les navigateurs gardent.

fn main() -> std::io::Result<()> {
    println!("Talka 💬 écrit avec Paquets 📦");
    talka::app().run()
}
