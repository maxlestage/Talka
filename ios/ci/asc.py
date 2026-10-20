"""App Store Connect, depuis GitHub Actions (aucun ordinateur nécessaire).

    python asc.py prepare   # l'identifiant de l'app, l'équipe, l'app existe-t-elle ?
    python asc.py testers   # le groupe de testeurs internes (toute l'équipe)

La clé d'API vient de l'environnement : ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY
(le contenu du fichier .p8). Rien n'est affiché de la clé.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = "https://api.appstoreconnect.apple.com/v1"
BUNDLE_ID = os.environ.get("BUNDLE_ID", "com.maxlestage.talka")
APP_NAME = "Talka"
GROUP = "Talka"


def token():
    now = int(time.time())
    return jwt.encode(
        {"iss": os.environ["ASC_ISSUER_ID"], "iat": now, "exp": now + 15 * 60, "aud": "appstoreconnect-v1"},
        os.environ["ASC_KEY"].replace("\\n", "\n"),
        algorithm="ES256",
        headers={"kid": os.environ["ASC_KEY_ID"], "typ": "JWT"},
    )


def call(method, path, body=None, query=None, soft=False):
    url = (API.replace("/v1", "") + path) if path.startswith("/v2") else API + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    req = urllib.request.Request(url, method=method)
    req.add_header("Authorization", "Bearer " + token())
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")
        message = f"App Store Connect a refusé {method} {path} ({e.code}) : {detail[:600]}"
        if soft:
            print(f"::warning::{message}")
            return None
        raise SystemExit(message)


def output(name, value):
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a") as f:
            f.write(f"{name}={value}\n")
    print(f"{name} = {value}")


def bundle(identifier=BUNDLE_ID, name=APP_NAME, soft=False):
    """L'identifiant d'une app, enregistré s'il ne l'est pas encore."""
    found = call("GET", "/bundleIds", query={"filter[identifier]": identifier, "limit": 5})["data"]
    found = [b for b in found if b["attributes"]["identifier"] == identifier]
    if found:
        return found[0]
    print(f"Enregistrement de l'identifiant {identifier}…")
    created = call(
        "POST",
        "/bundleIds",
        {"data": {"type": "bundleIds", "attributes": {"identifier": identifier, "name": name, "platform": "IOS"}}},
        soft=soft,
    )
    return created["data"] if created else None


def app():
    found = call("GET", "/apps", query={"filter[bundleId]": BUNDLE_ID, "limit": 5})["data"]
    return found[0] if found else None


def prepare():
    b = bundle()
    output("team_id", b["attributes"]["seedId"])
    # L'app de la montre, livrée avec celle de l'iPhone (Xcode l'enregistre
    # aussi de lui-même à l'envoi ; ici, c'est fait d'avance).
    bundle(BUNDLE_ID + ".watchkitapp", APP_NAME + " Watch", soft=True)
    a = app()
    if not a:
        # Ce que la clé voit : une app créée avec un autre identifiant, par exemple.
        apps = call("GET", "/apps", query={"limit": 50})["data"]
        print("Apps visibles par la clé d'API :")
        for x in apps:
            print(f"  - {x['attributes']['name']} : {x['attributes']['bundleId']}")
        if not apps:
            print("  (aucune)")
        print(
            "\n::error title=Créez l'app dans App Store Connect::"
            f"L'identifiant {BUNDLE_ID} est prêt, mais l'app n'existe pas encore dans App Store Connect. "
            "Sur appstoreconnect.apple.com : Apps → + → Nouvelle app → iOS, nom « Talka », "
            f"langue Français, identifiant de bundle {BUNDLE_ID}, SKU « talka ». Puis relancez ce workflow."
        )
        sys.exit(1)
    output("app_id", a["id"])


def testers():
    """Un groupe de testeurs internes qui reçoit chaque version : toute l'équipe."""
    a = app()
    if not a:
        raise SystemExit("App introuvable dans App Store Connect.")
    groups = call("GET", f"/apps/{a['id']}/betaGroups", query={"limit": 50})["data"]
    group = next((g for g in groups if g["attributes"].get("isInternalGroup") and g["attributes"]["name"] == GROUP), None)
    if not group:
        print(f"Création du groupe de testeurs internes « {GROUP} »…")
        group = call(
            "POST",
            "/betaGroups",
            {
                "data": {
                    "type": "betaGroups",
                    "attributes": {"name": GROUP, "isInternalGroup": True, "hasAccessToAllBuilds": True},
                    "relationships": {"app": {"data": {"type": "apps", "id": a["id"]}}},
                }
            },
        )["data"]
    users = call("GET", "/users", query={"limit": 50})["data"]
    present = {
        t["attributes"].get("email")
        for t in call("GET", f"/betaGroups/{group['id']}/betaTesters", query={"limit": 200})["data"]
    }
    for u in users:
        email = u["attributes"]["username"]
        if email in present:
            continue
        print(f"Ajout de {email} aux testeurs internes…")
        call(
            "POST",
            "/betaTesters",
            {
                "data": {
                    "type": "betaTesters",
                    "attributes": {
                        "email": email,
                        "firstName": u["attributes"].get("firstName") or "Talka",
                        "lastName": u["attributes"].get("lastName") or "Testeur",
                    },
                    "relationships": {"betaGroups": {"data": [{"type": "betaGroups", "id": group["id"]}]}},
                }
            },
        )
    print("Testeurs internes prêts : la version arrivera dans l'app TestFlight une fois traitée par Apple.")


if __name__ == "__main__":
    {"prepare": prepare, "testers": testers}[sys.argv[1]]()
