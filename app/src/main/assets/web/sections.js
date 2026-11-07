// Browser-only view preferences. Collapsing never sends player/queue/download commands.
(() => {
    const key = "parentcast.section-state.v1";
    const panels = [...document.querySelectorAll("details[data-section]")];
    let saved = {};
    try {
        const value = JSON.parse(localStorage.getItem(key));
        if (value && typeof value === "object" && !Array.isArray(value)) saved = value;
    } catch (_) { /* Storage may be unavailable; native details still work. */ }

    const persist = () => {
        const state = Object.fromEntries(panels.map(panel => [panel.dataset.section, panel.open]));
        try { localStorage.setItem(key, JSON.stringify(state)); } catch (_) { }
    };
    const close = panel => {
        panel.open = false;
        persist();
        const heading = panel.querySelector("summary");
        heading.focus({preventScroll: true});
        heading.scrollIntoView({block: "start", behavior: "smooth"});
    };
    panels.forEach(panel => {
        if (typeof saved[panel.dataset.section] === "boolean") panel.open = saved[panel.dataset.section];
        panel.addEventListener("toggle", persist);
        panel.querySelector("[data-section-close]").addEventListener("click", () => close(panel));
    });
    const setAll = open => { panels.forEach(panel => { panel.open = open; }); persist(); };
    document.getElementById("collapseSections").addEventListener("click", () => setAll(false));
    document.getElementById("expandSections").addEventListener("click", () => setAll(true));

    const reveal = hash => {
        let target;
        try { target = document.getElementById(decodeURIComponent(hash.slice(1))); } catch (_) { return; }
        if (!target) return;
        const panel = target.closest("details[data-section]") || target.querySelector("details[data-section]");
        if (panel) { panel.open = true; persist(); }
    };
    document.querySelectorAll(".section-nav a[href^='#']").forEach(link => {
        link.addEventListener("click", () => reveal(link.hash));
    });
    window.addEventListener("hashchange", () => reveal(location.hash));
    // Reload preserves the saved view, including a manually closed section at the current hash.
    // Clicking a navigation link (even the same hash) still opens its destination.

})();
