/* Sunnyside web: a Reddit-style reader for feed.json. No build step, no dependencies. */
"use strict";

const FEED_URL = "feed.json";
const PAGE_SIZE = 20;
const REFRESH_MS = 5 * 60 * 1000;

const COMMUNITIES = {
  WholesomeMemes: { emoji: "😂", color: "#F2A516", about: "Memes that make you feel good about the world." },
  Aww: { emoji: "🥹", color: "#E86A92", about: "Cute animals. That's it. That's the community." },
  MadeMeSmile: { emoji: "😊", color: "#F28C28", about: "Small moments of pure joy and people being lovely." },
  Science: { emoji: "🔭", color: "#5B6CD9", about: "Discoveries, space and the wonders of research." },
  Environment: { emoji: "🌿", color: "#2E9D5B", about: "Climate wins, rewilding and a greener planet." },
  Health: { emoji: "💚", color: "#0F9D8F", about: "Medical breakthroughs and healthier lives." },
  Animals: { emoji: "🐾", color: "#E07A1F", about: "Wildlife comebacks and animal news." },
  Community: { emoji: "🤝", color: "#D9477A", about: "Kindness, neighbours and people helping people." },
  Innovation: { emoji: "💡", color: "#8A56D6", about: "Clever ideas making life better." },
  Culture: { emoji: "🎨", color: "#C9533A", about: "Art, music, books and joy." },
  Sport: { emoji: "🏅", color: "#2C88C9", about: "Triumphs, comebacks and good sportsmanship." },
};
const COMMUNITY_NAMES = Object.keys(COMMUNITIES);

const SORTS = [
  { id: "hot", label: "Hot", icon: "🔥" },
  { id: "new", label: "New", icon: "✨" },
  { id: "top", label: "Top", icon: "🏆" },
];

/* ------------------------------------------------------------------ storage */

const store = {
  get(key, fallback) {
    try {
      const raw = localStorage.getItem("sunnyside." + key);
      return raw == null ? fallback : JSON.parse(raw);
    } catch (e) {
      return fallback;
    }
  },
  set(key, value) {
    try { localStorage.setItem("sunnyside." + key, JSON.stringify(value)); } catch (e) { /* private mode */ }
  },
};

const state = {
  feed: null,
  posts: [],
  byId: new Map(),
  error: null,
  sort: store.get("sort", "hot"),
  view: store.get("view", "card"),
  votes: store.get("votes", {}),          // id -> 1 | -1
  saved: store.get("saved", {}),          // id -> post snapshot
  joined: store.get("joined", null),      // array of community names, null = all
  pendingFeed: null,
  shown: PAGE_SIZE,
  list: [],
};

/* ------------------------------------------------------------------ helpers */

function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value == null || value === false) continue;
    if (key === "class") el.className = value;
    else if (key === "style") el.style.cssText = value;
    else if (key.startsWith("on")) el.addEventListener(key.slice(2), value);
    else if (key === "html") el.innerHTML = value; // only ever used with static SVG strings below
    else el.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

function safeUrl(url) {
  try {
    const u = new URL(url, location.href);
    return u.protocol === "https:" || u.protocol === "http:" ? u.href : null;
  } catch (e) {
    return null;
  }
}

function domain(url) {
  try { return new URL(url).hostname.replace(/^www\./, ""); } catch (e) { return ""; }
}

function timeAgo(iso) {
  const s = Math.max(0, (Date.now() - Date.parse(iso)) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return Math.floor(s / 60) + "m ago";
  if (s < 86400) return Math.floor(s / 3600) + "h ago";
  if (s < 7 * 86400) return Math.floor(s / 86400) + "d ago";
  return new Date(iso).toLocaleDateString(undefined, { day: "numeric", month: "short" });
}

function compact(n) {
  if (n == null) return null;
  if (Math.abs(n) >= 1e6) return (n / 1e6).toFixed(1).replace(/\.0$/, "") + "m";
  if (Math.abs(n) >= 1e4) return Math.round(n / 1e3) + "k";
  if (Math.abs(n) >= 1e3) return (n / 1e3).toFixed(1).replace(/\.0$/, "") + "k";
  return String(n);
}

function communityOf(p) {
  const name = p.community || p.category || "Community";
  return COMMUNITIES[name] ? name : "Community";
}

function meta(name) {
  return COMMUNITIES[name] || COMMUNITIES.Community;
}

function avatar(name, size) {
  const m = meta(name);
  return h("span", { class: "avatar" + (size ? " " + size : ""), style: `background:${m.color}26`, "aria-hidden": "true" }, m.emoji);
}

function toast(text) {
  const el = document.getElementById("toast");
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove("show"), 2200);
}

const ICON = {
  up: '<svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M10 3 3 11h4.5v6h5v-6H17z" fill="currentColor"/></svg>',
  down: '<svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M10 17 3 9h4.5V3h5v6H17z" fill="currentColor"/></svg>',
  comment: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M4 4h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H9l-4 3v-3H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>',
  share: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M12 4l5 5-5 5M17 9H9a5 5 0 0 0-5 5v2" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  save: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M5 3h10v14l-5-3.5L5 17z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>',
  saved: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M5 3h10v14l-5-3.5L5 17z" fill="currentColor"/></svg>',
  out: '<svg viewBox="0 0 20 20" width="14" height="14" aria-hidden="true"><path d="M11 3h6v6M17 3l-8 8M8 5H4v11h11v-4" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  back: '<svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M12 4 6 10l6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  sun: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><circle cx="12" cy="12" r="4.5" fill="currentColor"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  moon: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" fill="currentColor"/></svg>',
};

/* ------------------------------------------------------------------ ranking */

function myVote(p) { return state.votes[p.id] || 0; }
function points(p) { return (p.score || 0) + myVote(p); }

function hotScore(p) {
  const ageHours = (Date.now() - Date.parse(p.publishedAt)) / 3.6e6;
  const votes = Math.max(points(p), 0);
  // Votes help, but are capped so news (which has no Reddit votes) isn't buried under memes.
  const social = Math.min(3, 0.75 * Math.log10(1 + votes));
  return (p.uplift || 5) + social + (p.imageUrl ? 0.5 : 0) + 2 * myVote(p) - ageHours / 6;
}

function sortPosts(posts, sort) {
  const list = posts.slice();
  if (sort === "new") list.sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
  else if (sort === "top") list.sort((a, b) => points(b) - points(a) || (b.uplift || 0) - (a.uplift || 0));
  else list.sort((a, b) => hotScore(b) - hotScore(a));
  return list;
}

function joined() {
  return state.joined || COMMUNITY_NAMES;
}

/* ------------------------------------------------------------------ actions */

function vote(p, dir) {
  const current = myVote(p);
  const next = current === dir ? 0 : dir;
  if (next) state.votes[p.id] = next; else delete state.votes[p.id];
  store.set("votes", state.votes);
  document.querySelectorAll(`[data-votes="${CSS.escape(p.id)}"]`).forEach((el) => el.replaceWith(voteBox(p, el.dataset.layout)));
}

function toggleSave(p) {
  if (state.saved[p.id]) {
    delete state.saved[p.id];
    toast("Removed from saved");
  } else {
    state.saved[p.id] = p;
    toast("Saved for a rainy day 🔖");
  }
  store.set("saved", state.saved);
  document.querySelectorAll(`[data-save="${CSS.escape(p.id)}"]`).forEach((el) => el.replaceWith(saveButton(p)));
}

async function share(p) {
  const url = location.href.split("#")[0] + "#/post/" + encodeURIComponent(p.id);
  const data = { title: p.title, text: p.title + " — via Sunnyside ☀️", url };
  try {
    if (navigator.share) {
      await navigator.share(data);
      return;
    }
    await navigator.clipboard.writeText(url);
    toast("Link copied");
  } catch (e) {
    if (e && e.name !== "AbortError") toast("Couldn't share");
  }
}

function toggleJoin(name) {
  const set = new Set(joined());
  if (set.has(name)) set.delete(name); else set.add(name);
  state.joined = COMMUNITY_NAMES.filter((n) => set.has(n));
  store.set("joined", state.joined);
  toast(set.has(name) ? `Joined s/${name}` : `Left s/${name}`);
  render();
}

/* ------------------------------------------------------------------ components */

function voteBox(p, layout) {
  const v = myVote(p);
  const score = p.score == null && !v ? "Vote" : compact(points(p));
  return h("div", {
    class: "votes" + (v > 0 ? " upvoted" : v < 0 ? " downvoted" : ""),
    "data-votes": p.id,
    "data-layout": layout || "row",
  },
    h("button", { class: "vote up", "aria-label": "Upvote", "aria-pressed": String(v > 0), html: ICON.up, onclick: (e) => { e.preventDefault(); vote(p, 1); } }),
    h("span", { class: "score" }, score),
    h("button", { class: "vote down", "aria-label": "Downvote", "aria-pressed": String(v < 0), html: ICON.down, onclick: (e) => { e.preventDefault(); vote(p, -1); } }),
  );
}

function saveButton(p) {
  const saved = !!state.saved[p.id];
  return h("button", {
    class: "pill", "data-save": p.id, "aria-pressed": String(saved), onclick: (e) => { e.preventDefault(); toggleSave(p); },
  }, h("span", { html: saved ? ICON.saved : ICON.save }), saved ? "Saved" : "Save");
}

function commentsPill(p) {
  const url = safeUrl(p.discussionUrl);
  if (!url) return null;
  const label = p.comments != null ? compact(p.comments) : "Discuss";
  return h("a", { class: "pill", href: url, target: "_blank", rel: "noopener", title: "Open the discussion" },
    h("span", { html: ICON.comment }), label);
}

function actions(p, layout) {
  return h("div", { class: "actions" },
    layout === "compact" ? null : voteBox(p),
    commentsPill(p),
    h("button", { class: "pill", onclick: (e) => { e.preventDefault(); share(p); } }, h("span", { html: ICON.share }), "Share"),
    saveButton(p),
  );
}

function postHead(p) {
  const name = communityOf(p);
  const by = p.author && p.author !== p.source ? `${p.author} · ${p.source}` : p.source;
  return h("div", { class: "post-head" },
    avatar(name),
    h("a", { class: "community", href: "#/s/" + name }, "s/" + name),
    h("span", { class: "dot" }),
    h("time", { datetime: p.publishedAt, title: new Date(p.publishedAt).toLocaleString() }, timeAgo(p.publishedAt)),
    h("span", { class: "dot" }),
    h("span", { class: "by" }, "via " + by),
  );
}

function media(p, detail) {
  const img = safeUrl(p.imageUrl);
  const isArticle = (p.kind || "article") === "article";
  if (!img) {
    if (isArticle && !detail) return null;
    return h("div", { class: "media placeholder", style: `background:${meta(communityOf(p)).color}22` }, meta(communityOf(p)).emoji);
  }
  const attrs = { src: img, alt: "", loading: "lazy", decoding: "async", referrerpolicy: "no-referrer" };
  if (p.imageWidth && p.imageHeight) {
    attrs.width = p.imageWidth;
    attrs.height = p.imageHeight;
  }
  const image = h("img", attrs);
  image.addEventListener("error", () => image.closest(".media")?.remove());
  return h("div", { class: "media" + (isArticle ? " article" : "") },
    image,
    p.kind === "video" ? h("span", { class: "badge" }, "▶ Video — tap to watch") : null,
  );
}

function linkChip(p) {
  const url = safeUrl(p.url);
  if (!url || (p.kind || "article") !== "article") return null;
  return h("a", { class: "link-chip", href: url, target: "_blank", rel: "noopener" },
    h("span", null, domain(url)), h("span", { html: ICON.out }));
}

function postCard(p) {
  const href = "#/post/" + encodeURIComponent(p.id);
  return h("article", { class: "post" },
    postHead(p),
    h("h2", { class: "post-title" }, h("a", { href }, p.title)),
    media(p, false),
    p.summary ? h("p", { class: "post-summary" }, p.summary) : null,
    linkChip(p),
    actions(p),
  );
}

function postRow(p) {
  const href = "#/post/" + encodeURIComponent(p.id);
  const img = safeUrl(p.imageUrl);
  const name = communityOf(p);
  const thumb = h("div", { class: "thumb", style: img ? "" : `background:${meta(name).color}22` },
    img ? h("img", { src: img, alt: "", loading: "lazy", referrerpolicy: "no-referrer" }) : meta(name).emoji);
  return h("article", { class: "post row" },
    voteBox(p, "column"),
    thumb,
    h("div", null,
      h("h2", { class: "post-title" }, h("a", { href }, p.title)),
      postHead(p),
      actions(p, "compact"),
    ),
  );
}

function petTile(pet) {
  const img = safeUrl(pet.imageUrl);
  if (!img) return null;
  const puppy = pet.kind === "puppy";
  return h("div", { class: "pet" },
    h("img", { src: img, alt: `${pet.name}, the ${puppy ? "puppy" : "kitten"} of the day`, loading: "lazy", referrerpolicy: "no-referrer" }),
    h("span", { class: "pet-label" }, puppy ? "🐶 Puppy" : "🐱 Kitten"),
    h("div", { class: "pet-name" }, pet.name),
    pet.breed ? h("div", { class: "pet-breed" }, pet.breed) : null,
    h("div", { class: "pet-caption" }, pet.caption),
  );
}

function todaysPets() {
  const pets = (state.feed && state.feed.pets) || [];
  return ["kitten", "puppy"].map((kind) => pets.find((p) => p.kind === kind)).filter(Boolean);
}

/* ------------------------------------------------------------------ chrome */

function renderLeftNav(route) {
  const nav = document.getElementById("left-nav");
  const counts = {};
  for (const p of state.posts) counts[communityOf(p)] = (counts[communityOf(p)] || 0) + 1;
  const link = (href, emoji, label, current, count) =>
    h("a", { class: "nav-link", href, "aria-current": current ? "page" : null },
      typeof emoji === "string" ? h("span", { class: "nav-emoji" }, emoji) : emoji, label,
      count != null ? h("span", { class: "count" }, count) : null);
  nav.replaceChildren(
    link("#/", "🏠", "Home", route.name === "home"),
    link("#/all", "🌍", "Everything", route.name === "all"),
    link("#/saved", "🔖", "Saved", route.name === "saved", Object.keys(state.saved).length || null),
    h("div", { class: "nav-section" }, "Communities"),
    ...COMMUNITY_NAMES.map((name) =>
      link("#/s/" + name, avatar(name), "s/" + name, route.name === "community" && route.arg === name, counts[name] || 0)),
  );
}

function renderRightRail() {
  const rail = document.getElementById("right-rail");
  const pets = todaysPets().map(petTile).filter(Boolean);
  rail.replaceChildren(
    pets.length ? h("section", { class: "card" }, h("div", { class: "card-head" }, "Today's cuties"), h("div", { class: "pets" }, pets)) : null,
    h("section", { class: "card" },
      h("div", { class: "card-head" }, "About Sunnyside"),
      h("div", { class: "card-body" },
        h("p", null, "Only good news. Every story is picked from dedicated good-news outlets, or checked for positivity before it gets here."),
        h("p", null, "Wholesome memes and cute animals come from Reddit and Lemmy communities. Scores are their upvotes; your own votes stay on this device."),
        h("a", { class: "btn btn-primary btn-block", href: document.getElementById("get-app").href }, "📱 Get the Android app"),
      ),
    ),
    h("div", { class: "rail-links" },
      h("a", { href: "feed.json" }, "feed.json"),
      h("a", { href: "https://github.com/rockstoneballs/test", target: "_blank", rel: "noopener" }, "Source code"),
      h("span", null, "Kittens: The Cat API · Puppies: Dog CEO"),
    ),
  );
}

function chips(route) {
  const chip = (href, label, current) => h("a", { class: "chip", href, "aria-current": current ? "page" : null }, label);
  return h("div", { class: "chips" },
    chip("#/", "🏠 Home", route.name === "home"),
    chip("#/saved", "🔖 Saved", route.name === "saved"),
    ...COMMUNITY_NAMES.map((n) => chip("#/s/" + n, `${meta(n).emoji} ${n}`, route.name === "community" && route.arg === n)),
  );
}

function sortBar() {
  return h("div", { class: "sortbar", role: "toolbar", "aria-label": "Sort posts" },
    ...SORTS.map((s) => h("button", {
      class: "sort-btn", "aria-pressed": String(state.sort === s.id),
      onclick: () => { state.sort = s.id; store.set("sort", s.id); render(); },
    }, s.icon + " " + s.label)),
    h("span", { class: "spacer" }),
    state.feed ? h("span", { class: "updated" }, "Updated " + timeAgo(state.feed.generatedAt)) : null,
    h("button", {
      class: "sort-btn", "aria-label": state.view === "card" ? "Switch to compact view" : "Switch to card view",
      title: state.view === "card" ? "Compact view" : "Card view",
      onclick: () => { state.view = state.view === "card" ? "compact" : "card"; store.set("view", state.view); render(); },
    }, state.view === "card" ? "☰ Compact" : "▦ Cards"),
  );
}

function newPostsButton() {
  if (!state.pendingFeed) return null;
  const known = new Set(state.posts.map((p) => p.id));
  const fresh = state.pendingFeed.stories.filter((p) => !known.has(p.id)).length;
  if (!fresh) return null;
  return h("div", { class: "new-posts" },
    h("button", { onclick: () => { applyFeed(state.pendingFeed); state.pendingFeed = null; render(); scrollTo({ top: 0 }); } },
      `↑ ${fresh} new ${fresh === 1 ? "post" : "posts"}`));
}

/* ------------------------------------------------------------------ pages */

function feedList(posts, emptyEl) {
  state.list = posts;
  const wrap = h("div", { class: "feed" + (state.view === "compact" ? " compact" : "") });
  if (!posts.length) return emptyEl;
  const renderMore = () => {
    const slice = posts.slice(wrap.childElementCount, state.shown);
    for (const p of slice) wrap.append(state.view === "compact" ? postRow(p) : postCard(p));
  };
  renderMore();
  const sentinel = h("div", { class: "sentinel" });
  const end = h("div", { class: "end", hidden: posts.length > state.shown }, "You're all caught up ☀️");
  const io = new IntersectionObserver((entries) => {
    if (entries.some((e) => e.isIntersecting) && state.shown < posts.length) {
      state.shown += PAGE_SIZE;
      renderMore();
      if (state.shown >= posts.length) end.hidden = false;
    }
  }, { rootMargin: "800px" });
  io.observe(sentinel);
  return [wrap, sentinel, end];
}

function empty(emoji, title, body) {
  return h("div", { class: "empty" }, h("div", { class: "big" }, emoji), h("h2", null, title), h("p", null, body));
}

function pinnedPets() {
  const pets = todaysPets().map(petTile).filter(Boolean);
  if (!pets.length) return null;
  return h("section", { class: "post pinned pinned-pets" },
    h("div", { class: "post-head" }, h("span", null, "📌 Pinned"), h("span", { class: "dot" }), h("span", null, "Fresh every morning")),
    h("h2", { class: "post-title" }, "Today's Kitten & Puppy of the Day"),
    h("div", { class: "pets wide" }, pets),
  );
}

function pageHome(route) {
  const all = route.name === "all";
  const allowed = new Set(all ? COMMUNITY_NAMES : joined());
  const posts = sortPosts(state.posts.filter((p) => allowed.has(communityOf(p))), state.sort);
  return [
    chips(route), newPostsButton(), sortBar(), pinnedPets(),
    feedList(posts, empty("🌤️", "Nothing here yet", all ? "Check back soon — new good news arrives every half hour." : "Join some communities from the menu to fill your feed.")),
  ];
}

function pageCommunity(route) {
  const name = COMMUNITIES[route.arg] ? route.arg : "Community";
  const m = meta(name);
  const isJoined = joined().includes(name);
  const posts = sortPosts(state.posts.filter((p) => communityOf(p) === name), state.sort);
  document.title = `s/${name} · Sunnyside`;
  return [
    h("section", { class: "community-hero" },
      h("div", { class: "community-banner", style: `background:linear-gradient(120deg, ${m.color}, ${m.color}88)` }),
      h("div", { class: "community-head" },
        avatar(name, "lg"),
        h("div", { class: "meta" }, h("h1", null, "s/" + name), h("p", null, m.about)),
        h("button", { class: "btn" + (isJoined ? "" : " btn-primary"), onclick: () => toggleJoin(name) }, isJoined ? "Joined" : "Join"),
      ),
    ),
    chips(route), newPostsButton(), sortBar(),
    feedList(posts, empty(m.emoji, "Nothing here right now", "New posts arrive every half hour.")),
  ];
}

function pageSaved(route) {
  const posts = Object.values(state.saved).sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
  document.title = "Saved · Sunnyside";
  return [
    chips(route),
    h("h1", { class: "page-title" }, "🔖 Saved"),
    feedList(posts, empty("🔖", "Nothing saved yet", "Hit Save on any post to keep it here for a rainy day.")),
  ];
}

function pageSearch(route) {
  const q = route.arg.trim().toLowerCase();
  const words = q.split(/\s+/).filter(Boolean);
  const posts = sortPosts(state.posts.filter((p) => {
    const hay = `${p.title} ${p.summary || ""} ${p.source} ${communityOf(p)} ${p.region || ""}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  }), state.sort);
  document.title = `${route.arg} · Sunnyside search`;
  return [
    h("h1", { class: "page-title" }, `Results for “${route.arg}”`),
    sortBar(),
    feedList(posts, empty("🔍", "No matches", "Try a different word — puppies, solar, Kenya…")),
  ];
}

function pagePost(route) {
  const p = state.byId.get(route.arg) || state.saved[route.arg];
  if (!p) return empty("🍃", "Post not found", "It may have drifted out of the feed. Posts stay for about a week.");
  document.title = p.title + " · Sunnyside";
  const article = (p.kind || "article") === "article";
  const url = safeUrl(p.url);
  const discussion = safeUrl(p.discussionUrl);
  const related = sortPosts(state.posts.filter((o) => communityOf(o) === communityOf(p) && o.id !== p.id), "hot").slice(0, 5);
  return [
    h("button", { class: "back", onclick: () => (history.length > 1 ? history.back() : (location.hash = "#/")), html: ICON.back + " Back" }),
    h("article", { class: "post detail" },
      postHead(p),
      h("h1", { class: "post-title" }, p.title,
        p.region && p.region !== "Global" ? h("span", { class: "flair", style: "background:var(--surface-2)" }, "📍 " + p.region) : null),
      media(p, true),
      p.summary ? h("p", { class: "post-summary" }, p.summary) : null,
      h("div", { class: "detail-cta" },
        article && url ? h("a", { class: "btn btn-primary btn-block", href: url, target: "_blank", rel: "noopener" }, `Read the full story on ${domain(url)} `, h("span", { html: ICON.out })) : null,
        discussion ? h("a", { class: "btn btn-block" + (article ? "" : " btn-primary"), href: discussion, target: "_blank", rel: "noopener" },
          p.kind === "video" ? "▶ Watch it" : "💬 Join the discussion",
          p.comments != null ? ` (${compact(p.comments)} comments)` : "", " on ", domain(discussion)) : null,
      ),
      actions(p),
    ),
    related.length ? h("h2", { class: "section-title" }, `More from s/${communityOf(p)}`) : null,
    related.length ? h("div", { class: "feed compact" }, related.map(postRow)) : null,
  ];
}

/* ------------------------------------------------------------------ routing */

function parseRoute() {
  const hash = decodeURIComponent(location.hash.replace(/^#\/?/, ""));
  const [head, ...rest] = hash.split("/");
  const arg = rest.join("/");
  if (!head) return { name: "home" };
  if (head === "all") return { name: "all" };
  if (head === "saved") return { name: "saved" };
  if (head === "s" && arg) return { name: "community", arg };
  if (head === "post" && arg) return { name: "post", arg };
  if (head === "search") return { name: "search", arg };
  return { name: "home" };
}

let lastRouteKey = "";

function render() {
  const route = parseRoute();
  const key = route.name + ":" + (route.arg || "");
  const routeChanged = key !== lastRouteKey;
  if (routeChanged) state.shown = PAGE_SIZE;
  lastRouteKey = key;

  renderLeftNav(route);
  renderRightRail();
  closeMenu();

  const main = document.getElementById("main");
  if (!state.feed) {
    main.replaceChildren(state.error
      ? empty("⛅", "Couldn't load the good news", "Check your connection and try again in a moment.")
      : h("div", { class: "feed" }, h("div", { class: "skeleton" }), h("div", { class: "skeleton" }), h("div", { class: "skeleton" })));
    return;
  }

  document.title = "Sunnyside — only good news";
  const pages = { home: pageHome, all: pageHome, community: pageCommunity, saved: pageSaved, search: pageSearch, post: pagePost };
  main.replaceChildren(...[pages[route.name](route)].flat(3).filter(Boolean));
  if (route.name === "search") document.getElementById("search-input").value = route.arg;
  if (routeChanged) scrollTo({ top: 0 });
}

/* ------------------------------------------------------------------ data */

function applyFeed(feed) {
  state.feed = feed;
  state.posts = (feed.stories || []).filter((p) => p && p.id && p.title);
  state.byId = new Map(state.posts.map((p) => [p.id, p]));
  // Keep saved snapshots fresh (scores, comment counts) while they're still in the feed.
  let changed = false;
  for (const id of Object.keys(state.saved)) {
    if (state.byId.has(id)) { state.saved[id] = state.byId.get(id); changed = true; }
  }
  if (changed) store.set("saved", state.saved);
}

async function loadFeed(background) {
  try {
    const res = await fetch(FEED_URL, { cache: "no-cache" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const feed = await res.json();
    if (!background || !state.feed) {
      applyFeed(feed);
      state.error = null;
      render();
    } else if (feed.generatedAt !== state.feed.generatedAt) {
      state.pendingFeed = feed;
      const route = parseRoute();
      if (route.name !== "post") render();
    }
  } catch (e) {
    if (!state.feed) {
      state.error = e;
      render();
    }
  }
}

/* ------------------------------------------------------------------ boot */

function closeMenu() {
  document.getElementById("left-nav").classList.remove("open");
  document.getElementById("scrim").hidden = true;
  document.getElementById("menu-btn").setAttribute("aria-expanded", "false");
}

function setupChrome() {
  const menuBtn = document.getElementById("menu-btn");
  menuBtn.addEventListener("click", () => {
    const nav = document.getElementById("left-nav");
    const open = !nav.classList.contains("open");
    nav.classList.toggle("open", open);
    document.getElementById("scrim").hidden = !open;
    menuBtn.setAttribute("aria-expanded", String(open));
  });
  document.getElementById("scrim").addEventListener("click", closeMenu);

  const themeBtn = document.getElementById("theme-btn");
  const isDark = () => document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === "dark"
    : matchMedia("(prefers-color-scheme: dark)").matches;
  const paintThemeBtn = () => {
    themeBtn.innerHTML = isDark() ? ICON.sun : ICON.moon;
    themeBtn.setAttribute("aria-label", isDark() ? "Switch to light mode" : "Switch to dark mode");
  };
  themeBtn.addEventListener("click", () => {
    const next = isDark() ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    store.set("theme", next);
    paintThemeBtn();
  });
  paintThemeBtn();

  document.getElementById("search-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const q = document.getElementById("search-input").value.trim();
    location.hash = q ? "#/search/" + encodeURIComponent(q) : "#/";
  });

  addEventListener("hashchange", render);
  addEventListener("keydown", (e) => { if (e.key === "Escape") closeMenu(); });
  document.addEventListener("visibilitychange", () => { if (!document.hidden) loadFeed(true); });
}

setupChrome();
render();
loadFeed(false);
setInterval(() => loadFeed(true), REFRESH_MS);
