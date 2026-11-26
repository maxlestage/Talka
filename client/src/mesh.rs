//! Les appels à plusieurs : chacun relié à chacun, de pair à pair (WebRTC),
//! huit personnes au plus. Ce module ne fait que décider : qui fait sonner
//! qui, qui propose une liaison à qui, à qui répondre, quand l'appel est
//! fini. `calls` (le navigateur) et `CallEngine` (l'iPhone) exécutent ces
//! décisions avec WebRTC ; les mêmes règles des deux côtés.
//!
//! Les signaux (dans les enveloppes chiffrées, comme les messages) :
//!
//! - `offer` fait sonner : une invitation, avec la liste des participants
//!   (`members`) quand l'appel est à plusieurs ;
//! - `link` propose une liaison sans faire sonner, entre deux personnes
//!   déjà dans l'appel ;
//! - `answer` y répond, `ice` passe les candidats, `reject`, `busy` et
//!   `end` disent qu'on ne vient pas ou qu'on s'en va ;
//! - `join` : « je suis dans l'appel », à chacun de ceux qu'on n'a pas
//!   encore. Entre deux personnes dans l'appel, celle dont le code est le
//!   plus petit propose la liaison ; l'autre lui renvoie `join` une fois,
//!   pour qu'elle le fasse. Deux personnes ne se proposent donc jamais une
//!   liaison en même temps.
//!
//! Un ancien client ne connaît ni `link` ni `join` : invité, il ne parle
//! qu'à celui qui l'a appelé, comme pour un appel à deux.

use std::collections::{BTreeMap, BTreeSet};

/// Huit personnes au plus dans un appel (nous compris) : chacun envoie son
/// image à chacun des autres, et le réseau d'un téléphone ne suit pas
/// au-delà.
pub const MAX_PEOPLE: usize = 8;

/// Où en est la liaison avec un participant.
#[derive(Clone, Debug, PartialEq)]
pub enum Link {
    /// Nous l'avons fait sonner (`offer`) : en attente de sa réponse.
    Inviting,
    /// Nous lui avons proposé une liaison (`link`) : en attente de sa réponse.
    Offered,
    /// Il nous a fait sonner, ou proposé une liaison : nous n'avons pas
    /// encore répondu (son offre, à reprendre pour répondre).
    Incoming(String),
    /// Reliés : la réponse est donnée ou reçue.
    Open,
}

/// Ce qu'il faut faire, dans l'ordre.
#[derive(Clone, Debug, PartialEq)]
pub enum Do {
    /// Préparer une liaison et lui envoyer une offre : `ring`, le faire
    /// sonner (`offer`, une invitation) ; sinon `link`.
    Offer { peer: String, ring: bool },
    /// Préparer une liaison, y poser son offre et lui répondre (`answer`).
    Answer { peer: String, sdp: String },
    /// Lui dire que nous sommes dans l'appel (`join`).
    Join { peer: String },
    /// Lui dire que nous partons (`end`) et fermer la liaison.
    Hangup { peer: String },
    /// Fermer la liaison sans rien lui dire (lui est parti).
    Close { peer: String },
    /// Plus personne : l'appel est fini.
    End,
}

/// Un appel, vu de chez nous.
#[derive(Clone, Debug)]
pub struct Mesh {
    me: String,
    /// Tous ceux de l'appel, invités ou reliés (nous non compris).
    roster: BTreeSet<String>,
    links: BTreeMap<String, Link>,
    /// Nous sommes dans l'appel : appelant, ou appelé qui a répondu.
    joined: bool,
    /// Ceux à qui nous avons déjà renvoyé `join`.
    joined_back: BTreeSet<String>,
}

impl Mesh {
    /// Nous appelons `invitees` (un seul : un appel à deux). Les doublons,
    /// nous-même et ce qui dépasse huit personnes sont laissés de côté.
    pub fn outgoing(me: &str, invitees: &[String]) -> (Mesh, Vec<Do>) {
        let mut mesh = Mesh::new(me);
        mesh.joined = true;
        let mut todo = Vec::new();
        for peer in invitees {
            if mesh.admit(peer) && !mesh.links.contains_key(peer) {
                mesh.links.insert(peer.clone(), Link::Inviting);
                todo.push(Do::Offer {
                    peer: peer.clone(),
                    ring: true,
                });
            }
        }
        (mesh, todo)
    }

    /// `from` nous fait sonner, avec son offre et ceux qu'il appelle avec
    /// nous (vide : un appel à deux).
    pub fn incoming(me: &str, from: &str, sdp: &str, members: &[String]) -> Mesh {
        let mut mesh = Mesh::new(me);
        mesh.admit(from);
        mesh.links
            .insert(from.to_owned(), Link::Incoming(sdp.to_owned()));
        mesh.absorb(members);
        mesh
    }

    fn new(me: &str) -> Mesh {
        Mesh {
            me: me.to_owned(),
            roster: BTreeSet::new(),
            links: BTreeMap::new(),
            joined: false,
            joined_back: BTreeSet::new(),
        }
    }

    /// Le compte dans l'appel, s'il y a de la place (vrai s'il y est).
    fn admit(&mut self, peer: &str) -> bool {
        if peer.is_empty() || peer == self.me {
            return false;
        }
        if self.roster.contains(peer) {
            return true;
        }
        if self.roster.len() + 1 >= MAX_PEOPLE {
            return false;
        }
        self.roster.insert(peer.to_owned())
    }

    fn absorb(&mut self, members: &[String]) {
        for m in members {
            self.admit(m);
        }
    }

    /// Ceux de l'appel, nous compris (triés) : ce qu'une invitation leur
    /// annonce.
    pub fn members(&self) -> Vec<String> {
        let mut all: Vec<String> = self.roster.iter().cloned().collect();
        all.push(self.me.clone());
        all.sort();
        all
    }

    /// Les autres participants, invités ou reliés.
    pub fn others(&self) -> Vec<String> {
        self.roster.iter().cloned().collect()
    }

    /// Un appel à plusieurs (plus d'un autre participant).
    pub fn is_group(&self) -> bool {
        self.roster.len() > 1
    }

    pub fn joined(&self) -> bool {
        self.joined
    }

    pub fn link(&self, peer: &str) -> Option<&Link> {
        self.links.get(peer)
    }

    /// Ceux avec qui nous sommes reliés.
    pub fn open(&self) -> Vec<String> {
        self.links
            .iter()
            .filter(|(_, l)| **l == Link::Open)
            .map(|(p, _)| p.clone())
            .collect()
    }

    /// Une offre de `from` pour cet appel : une invitation (`ring`) ou une
    /// liaison (`link`).
    pub fn on_offer(&mut self, from: &str, sdp: &str, members: &[String], ring: bool) -> Vec<Do> {
        if !self.joined {
            // Encore à sonner : gardée pour la réponse. Une liaison ne
            // vient que de qui a reçu notre `join`, envoyé après avoir
            // répondu.
            if ring && self.admit(from) && self.links.get(from) != Some(&Link::Open) {
                self.links
                    .insert(from.to_owned(), Link::Incoming(sdp.to_owned()));
                self.absorb(members);
            }
            return Vec::new();
        }
        if !self.admit(from) {
            return Vec::new();
        }
        self.absorb(members);
        match self.links.get(from) {
            Some(Link::Open) => Vec::new(),
            // Nous lui avons aussi proposé : l'offre du plus petit code
            // l'emporte.
            Some(Link::Inviting | Link::Offered) if self.me.as_str() < from => Vec::new(),
            Some(Link::Inviting | Link::Offered) => {
                self.links.insert(from.to_owned(), Link::Open);
                vec![
                    Do::Close {
                        peer: from.to_owned(),
                    },
                    Do::Answer {
                        peer: from.to_owned(),
                        sdp: sdp.to_owned(),
                    },
                ]
            }
            _ => {
                self.links.insert(from.to_owned(), Link::Open);
                vec![Do::Answer {
                    peer: from.to_owned(),
                    sdp: sdp.to_owned(),
                }]
            }
        }
    }

    /// Nous répondons (l'appel sonnait) : une réponse à chaque offre reçue,
    /// puis `join` à ceux de l'appel que nous n'avons pas encore.
    pub fn accept(&mut self) -> Vec<Do> {
        if self.joined {
            return Vec::new();
        }
        self.joined = true;
        let mut todo = Vec::new();
        for (peer, link) in self.links.iter_mut() {
            if let Link::Incoming(sdp) = link {
                todo.push(Do::Answer {
                    peer: peer.clone(),
                    sdp: std::mem::take(sdp),
                });
                *link = Link::Open;
            }
        }
        for peer in &self.roster {
            if !self.links.contains_key(peer) {
                todo.push(Do::Join { peer: peer.clone() });
            }
        }
        todo
    }

    /// `from` est dans l'appel et ne nous a pas encore : le plus petit code
    /// propose la liaison, l'autre lui renvoie `join` (une fois).
    pub fn on_join(&mut self, from: &str, members: &[String]) -> Vec<Do> {
        if !self.admit(from) {
            return Vec::new();
        }
        self.absorb(members);
        if !self.joined || self.links.contains_key(from) {
            return Vec::new();
        }
        if self.me.as_str() < from {
            self.links.insert(from.to_owned(), Link::Offered);
            return vec![Do::Offer {
                peer: from.to_owned(),
                ring: false,
            }];
        }
        if self.joined_back.insert(from.to_owned()) {
            return vec![Do::Join {
                peer: from.to_owned(),
            }];
        }
        Vec::new()
    }

    /// Sa réponse à notre offre : vrai si nous l'attendions.
    pub fn on_answer(&mut self, from: &str) -> bool {
        match self.links.get_mut(from) {
            Some(link @ (Link::Inviting | Link::Offered)) => {
                *link = Link::Open;
                true
            }
            _ => false,
        }
    }

    /// `from` s'en va (`end`), ne vient pas (`reject`, `busy`), ou sa
    /// liaison a échoué. Plus personne : l'appel est fini.
    pub fn on_gone(&mut self, from: &str) -> Vec<Do> {
        let mut todo = Vec::new();
        if self.links.remove(from).is_some() {
            todo.push(Do::Close {
                peer: from.to_owned(),
            });
        }
        self.roster.remove(from);
        self.joined_back.remove(from);
        if self.links.is_empty() {
            todo.push(Do::End);
        }
        todo
    }

    /// Personne n'a répondu à temps : les invitations encore en attente
    /// s'arrêtent.
    pub fn no_answer(&mut self) -> Vec<Do> {
        let waiting: Vec<String> = self
            .links
            .iter()
            .filter(|(_, l)| **l == Link::Inviting)
            .map(|(p, _)| p.clone())
            .collect();
        let mut todo = Vec::new();
        for peer in waiting {
            self.links.remove(&peer);
            self.roster.remove(&peer);
            todo.push(Do::Hangup { peer });
        }
        if self.links.is_empty() {
            todo.push(Do::End);
        }
        todo
    }

    /// Nous raccrochons (ou refusons) : au revoir à chacun.
    pub fn hangup(&mut self) -> Vec<Do> {
        let mut todo: Vec<Do> = std::mem::take(&mut self.links)
            .into_keys()
            .map(|peer| Do::Hangup { peer })
            .collect();
        todo.push(Do::End);
        todo
    }

    /// Faire sonner quelqu'un de plus, pendant l'appel.
    pub fn add(&mut self, peer: &str) -> Result<Vec<Do>, &'static str> {
        if !self.joined {
            return Err("pas dans l'appel");
        }
        if peer == self.me || self.links.contains_key(peer) {
            return Err("déjà dans l'appel");
        }
        if !self.admit(peer) {
            return Err("complet");
        }
        self.links.insert(peer.to_owned(), Link::Inviting);
        Ok(vec![Do::Offer {
            peer: peer.to_owned(),
            ring: true,
        }])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn codes(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    /// Un réseau de participants qui s'échangent les signaux, sans WebRTC :
    /// chacun finit-il relié à chacun ?
    #[derive(Default)]
    struct Net {
        people: BTreeMap<String, Mesh>,
        /// (de, à, signal, membres) en attente.
        queue: Vec<(String, String, String, Vec<String>)>,
    }

    impl Net {
        fn run(&mut self, who: &str, todo: Vec<Do>) {
            let members = self.people[who].members();
            for d in todo {
                let (to, op) = match d {
                    Do::Offer { peer, ring } => (peer, if ring { "offer" } else { "link" }),
                    Do::Answer { peer, .. } => (peer, "answer"),
                    Do::Join { peer } => (peer, "join"),
                    Do::Hangup { peer } => (peer, "end"),
                    Do::Close { .. } | Do::End => continue,
                };
                self.queue
                    .push((who.to_owned(), to, op.to_owned(), members.clone()));
            }
        }

        /// Livre tout ; `accepts` répond dès qu'il sonne.
        fn settle(&mut self, accepts: &[&str]) {
            let mut guard = 0;
            while !self.queue.is_empty() {
                guard += 1;
                assert!(guard < 10_000, "les signaux ne s'arrêtent jamais");
                let (from, to, op, members) = self.queue.remove(0);
                let ring = op == "offer";
                let todo = match self.people.get_mut(&to) {
                    None if ring => {
                        self.people
                            .insert(to.clone(), Mesh::incoming(&to, &from, "sdp", &members));
                        if accepts.contains(&to.as_str()) {
                            self.people.get_mut(&to).unwrap().accept()
                        } else {
                            Vec::new()
                        }
                    }
                    None => Vec::new(),
                    Some(m) => match op.as_str() {
                        "offer" | "link" => {
                            let mut t = m.on_offer(&from, "sdp", &members, ring);
                            if ring && !m.joined() && accepts.contains(&to.as_str()) {
                                t.extend(m.accept());
                            }
                            t
                        }
                        "answer" => {
                            m.on_answer(&from);
                            Vec::new()
                        }
                        "join" => m.on_join(&from, &members),
                        "end" => m.on_gone(&from),
                        _ => Vec::new(),
                    },
                };
                self.run(&to, todo);
            }
        }

        fn all_linked(&self, who: &[&str]) {
            for a in who {
                let mut open = self.people[*a].open();
                open.sort();
                let mut want: Vec<String> = who
                    .iter()
                    .filter(|b| *b != a)
                    .map(|b| b.to_string())
                    .collect();
                want.sort();
                assert_eq!(open, want, "{a} n'est pas relié à tous");
            }
        }
    }

    #[test]
    fn a_two_person_call_is_a_plain_call() {
        let (mut a, todo) = Mesh::outgoing("AAAA", &codes(&["BBBB"]));
        assert_eq!(
            todo,
            [Do::Offer {
                peer: "BBBB".into(),
                ring: true
            }]
        );
        assert!(!a.is_group());
        let mut b = Mesh::incoming("BBBB", "AAAA", "sdp-a", &[]);
        assert_eq!(
            b.accept(),
            [Do::Answer {
                peer: "AAAA".into(),
                sdp: "sdp-a".into()
            }]
        );
        assert!(a.on_answer("BBBB"));
        assert_eq!(a.open(), ["BBBB"]);
        assert_eq!(
            b.on_gone("AAAA"),
            [
                Do::Close {
                    peer: "AAAA".into()
                },
                Do::End
            ]
        );
    }

    #[test]
    fn everyone_who_answers_ends_up_linked_to_everyone() {
        let mut net = Net::default();
        let (a, todo) = Mesh::outgoing("AAAA", &codes(&["BBBB", "CCCC", "DDDD", "EEEE"]));
        assert!(a.is_group());
        net.people.insert("AAAA".into(), a);
        net.run("AAAA", todo);
        net.settle(&["BBBB", "CCCC", "DDDD", "EEEE"]);
        net.all_linked(&["AAAA", "BBBB", "CCCC", "DDDD", "EEEE"]);
    }

    #[test]
    fn whoever_answers_late_still_joins_everyone() {
        let mut net = Net::default();
        let (a, todo) = Mesh::outgoing("CCCC", &codes(&["AAAA", "BBBB", "DDDD"]));
        net.people.insert("CCCC".into(), a);
        net.run("CCCC", todo);
        // D ne répond pas tout de suite.
        net.settle(&["AAAA", "BBBB"]);
        net.all_linked(&["CCCC", "AAAA", "BBBB"]);
        let todo = net.people.get_mut("DDDD").unwrap().accept();
        net.run("DDDD", todo);
        net.settle(&[]);
        net.all_linked(&["CCCC", "AAAA", "BBBB", "DDDD"]);
    }

    #[test]
    fn someone_added_during_a_call_joins_everyone() {
        let mut net = Net::default();
        let (a, todo) = Mesh::outgoing("BBBB", &codes(&["DDDD"]));
        net.people.insert("BBBB".into(), a);
        net.run("BBBB", todo);
        net.settle(&["DDDD"]);
        net.all_linked(&["BBBB", "DDDD"]);
        // D (pas l'appelant) ajoute A, puis B ajoute C.
        let todo = net.people.get_mut("DDDD").unwrap().add("AAAA").unwrap();
        net.run("DDDD", todo);
        net.settle(&["AAAA"]);
        let todo = net.people.get_mut("BBBB").unwrap().add("CCCC").unwrap();
        net.run("BBBB", todo);
        net.settle(&["CCCC"]);
        net.all_linked(&["AAAA", "BBBB", "CCCC", "DDDD"]);
        assert!(net.people["AAAA"].is_group());
    }

    #[test]
    fn two_who_answer_at_once_do_not_both_offer() {
        let mut b = Mesh::incoming("BBBB", "AAAA", "x", &codes(&["AAAA", "BBBB", "CCCC"]));
        let mut c = Mesh::incoming("CCCC", "AAAA", "x", &codes(&["AAAA", "BBBB", "CCCC"]));
        let tb = b.accept();
        let tc = c.accept();
        assert!(tb.contains(&Do::Join {
            peer: "CCCC".into()
        }));
        assert!(tc.contains(&Do::Join {
            peer: "BBBB".into()
        }));
        // Chacun reçoit le `join` de l'autre : seul le plus petit propose.
        assert_eq!(
            b.on_join("CCCC", &[]),
            [Do::Offer {
                peer: "CCCC".into(),
                ring: false
            }]
        );
        assert_eq!(
            c.on_join("BBBB", &[]),
            [Do::Join {
                peer: "BBBB".into()
            }]
        );
        assert!(c.on_join("BBBB", &[]).is_empty(), "renvoyé une seule fois");
        assert!(b.on_join("CCCC", &[]).is_empty(), "déjà proposé");
        assert_eq!(
            c.on_offer("BBBB", "sdp-b", &[], false),
            [Do::Answer {
                peer: "BBBB".into(),
                sdp: "sdp-b".into()
            }]
        );
    }

    #[test]
    fn two_invitations_at_once_keep_the_smaller_one() {
        // A et B s'invitent l'un l'autre en même temps (chacun ajoute l'autre).
        let (mut a, _) = Mesh::outgoing("AAAA", &codes(&["BBBB"]));
        let (mut b, _) = Mesh::outgoing("BBBB", &codes(&["AAAA"]));
        assert!(a.on_offer("BBBB", "sdp-b", &[], true).is_empty());
        assert_eq!(
            b.on_offer("AAAA", "sdp-a", &[], true),
            [
                Do::Close {
                    peer: "AAAA".into()
                },
                Do::Answer {
                    peer: "AAAA".into(),
                    sdp: "sdp-a".into()
                }
            ]
        );
    }

    #[test]
    fn a_call_holds_eight_people_at_most() {
        let many: Vec<String> = (0..12).map(|i| format!("P{i:03}")).collect();
        let (mut a, todo) = Mesh::outgoing("AAAA", &many);
        assert_eq!(todo.len(), MAX_PEOPLE - 1);
        assert_eq!(a.members().len(), MAX_PEOPLE);
        assert_eq!(a.add("ZZZZ"), Err("complet"));
        assert_eq!(a.add("P000"), Err("déjà dans l'appel"));
        // Invité avec une liste trop longue : huit au plus, l'appelant compris.
        let b = Mesh::incoming("BBBB", "AAAA", "x", &many);
        assert_eq!(b.members().len(), MAX_PEOPLE);
        assert!(b.others().contains(&"AAAA".to_owned()));
        // Ni soi-même ni un doublon.
        let (c, todo) = Mesh::outgoing("AAAA", &codes(&["AAAA", "BBBB", "BBBB"]));
        assert_eq!(todo.len(), 1);
        assert_eq!(c.others(), ["BBBB"]);
    }

    #[test]
    fn a_call_ends_when_everyone_has_left_or_declined() {
        let (mut a, _) = Mesh::outgoing("AAAA", &codes(&["BBBB", "CCCC", "DDDD"]));
        assert_eq!(
            a.on_gone("BBBB"),
            [Do::Close {
                peer: "BBBB".into()
            }]
        );
        assert!(a.on_answer("CCCC"));
        // Personne d'autre ne répond : D cesse de sonner, l'appel continue avec C.
        assert_eq!(
            a.no_answer(),
            [Do::Hangup {
                peer: "DDDD".into()
            }]
        );
        assert_eq!(a.open(), ["CCCC"]);
        assert_eq!(
            a.on_gone("CCCC"),
            [
                Do::Close {
                    peer: "CCCC".into()
                },
                Do::End
            ]
        );
        // Personne n'a répondu du tout.
        let (mut e, _) = Mesh::outgoing("AAAA", &codes(&["BBBB"]));
        assert_eq!(
            e.no_answer(),
            [
                Do::Hangup {
                    peer: "BBBB".into()
                },
                Do::End
            ]
        );
        // Raccrocher dit au revoir à chacun.
        let (mut f, _) = Mesh::outgoing("AAAA", &codes(&["BBBB", "CCCC"]));
        assert_eq!(
            f.hangup(),
            [
                Do::Hangup {
                    peer: "BBBB".into()
                },
                Do::Hangup {
                    peer: "CCCC".into()
                },
                Do::End
            ]
        );
    }

    #[test]
    fn a_ringing_call_ignores_links_and_joins() {
        let mut b = Mesh::incoming("BBBB", "AAAA", "x", &codes(&["AAAA", "BBBB", "CCCC"]));
        assert!(b.on_offer("CCCC", "y", &[], false).is_empty());
        assert!(b.on_join("CCCC", &[]).is_empty());
        assert_eq!(b.link("CCCC"), None);
        // Une deuxième invitation (C l'a aussi ajouté) : gardée pour la réponse.
        assert!(b.on_offer("CCCC", "z", &[], true).is_empty());
        let todo = b.accept();
        assert!(todo.contains(&Do::Answer {
            peer: "AAAA".into(),
            sdp: "x".into()
        }));
        assert!(todo.contains(&Do::Answer {
            peer: "CCCC".into(),
            sdp: "z".into()
        }));
        assert_eq!(b.accept(), []);
    }
}
