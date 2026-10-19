// Talka — petits comportements du site de présentation.

document.documentElement.classList.add("js");

function reveal() {
  const items = document.querySelectorAll(".reveal");
  if (!("IntersectionObserver" in window)) {
    items.forEach((el) => el.classList.add("visible"));
    return;
  }
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add("visible");
          observer.unobserve(entry.target);
        }
      }
    },
    { rootMargin: "0px 0px -8% 0px", threshold: 0.12 }
  );
  items.forEach((el) => observer.observe(el));
}

async function liveCount() {
  const target = document.getElementById("live-count");
  if (!target) return;
  try {
    const res = await fetch("/api/stats", { headers: { Accept: "application/json" } });
    if (!res.ok) return;
    const stats = await res.json();
    const online = Number(stats && stats.online);
    if (!Number.isFinite(online) || online <= 0) return;
    target.textContent =
      online === 1
        ? "1 personne connectée en ce moment"
        : `${online} personnes connectées en ce moment`;
  } catch {
    // Silencieux : le texte par défaut reste affiché.
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", () => {
    reveal();
    liveCount();
  });
} else {
  reveal();
  liveCount();
}
