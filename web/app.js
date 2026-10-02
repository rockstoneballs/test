/* Sunnyside web: a Reddit-style reader for feed.json. No build step, no dependencies. */
"use strict";

const FEED_URL = "feed.json";
const PAGE_SIZE = 20;
const REFRESH_MS = 5 * 60 * 1000;

// Topic flair shown on each post, like Reddit's post flair. Keys are the feed's "community" values.
const TOPICS = {
  WholesomeMemes: { label: "Meme", emoji: "😂", color: "#F2A516" },
  Aww: { label: "Cute", emoji: "🥹", color: "#E86A92" },
  MadeMeSmile: { label: "Wholesome", emoji: "😊", color: "#F28C28" },
  Science: { label: "Science", emoji: "🔭", color: "#5B6CD9" },
  Environment: { label: "Environment", emoji: "🌿", color: "#2E9D5B" },
  Health: { label: "Health", emoji: "💚", color: "#0F9D8F" },
  Animals: { label: "Animals", emoji: "🐾", color: "#E07A1F" },
  Community: { label: "Kindness", emoji: "🤝", color: "#D9477A" },
  Innovation: { label: "Innovation", emoji: "💡", color: "#8A56D6" },
  AI: { label: "AI for good", emoji: "🤖", color: "#2F80ED" },
  Pets: { label: "Cats & dogs", emoji: "😺", color: "#B7791F" },
  Culture: { label: "Culture", emoji: "🎨", color: "#C9533A" },
};

const SORTS = [
  { id: "hot", label: "Top stories", icon: "⭐" },
  { id: "new", label: "Latest", icon: "🕒" },
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
  sort: store.get("sort", "hot") === "new" ? "new" : "hot",
  view: store.get("view", "card"),
  saved: store.get("saved", {}),          // id -> post snapshot
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
  if (!url) return null;
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

function topicOf(p) {
  return TOPICS[p.community || p.category] || TOPICS.Community;
}

/** Where a post came from, in plain words: "Good News Network", "Reddit", "9GAG"… */
function platformOf(p) {
  const src = p.source || "";
  if (src.startsWith("r/")) return "Reddit";
  if (src.startsWith("Lemmy")) return "Lemmy";
  if (src.startsWith("9GAG")) return "9GAG";
  if (src.startsWith("Imgur")) return "Imgur";
  return src;
}

function sourceAvatar(p) {
  const name = platformOf(p) || "?";
  let hash = 0;
  for (const ch of name) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  const hue = name === "Reddit" ? 16 : hash % 360;
  return h("span", { class: "avatar", style: `background:hsl(${hue} 70% 45%)`, "aria-hidden": "true" }, name.charAt(0).toUpperCase());
}

function flair(p) {
  const t = topicOf(p);
  return h("span", { class: "flair", style: `background:${t.color}22;color:${t.color}` }, `${t.emoji} ${t.label}`);
}

function toast(text) {
  const el = document.getElementById("toast");
  el.textContent = text;
  el.classList.add("show");
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove("show"), 2200);
}

const ICON = {
  share: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M12 4l5 5-5 5M17 9H9a5 5 0 0 0-5 5v2" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  save: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M5 3h10v14l-5-3.5L5 17z" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/></svg>',
  saved: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M5 3h10v14l-5-3.5L5 17z" fill="currentColor"/></svg>',
  out: '<svg viewBox="0 0 20 20" width="14" height="14" aria-hidden="true"><path d="M11 3h6v6M17 3l-8 8M8 5H4v11h11v-4" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  back: '<svg viewBox="0 0 20 20" width="18" height="18" aria-hidden="true"><path d="M12 4 6 10l6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  sun: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><circle cx="12" cy="12" r="4.5" fill="currentColor"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  flag: '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M5 17V3.5M5 4h9l-2 3.5 2 3.5H5" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  moon: '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" fill="currentColor"/></svg>',
};

/* ------------------------------------------------------------------ ranking */

/** "Top stories" ranking: how uplifting a post is, decaying with age. */
function hotScore(p) {
  const ageHours = (Date.now() - Date.parse(p.publishedAt)) / 3.6e6;
  // For memes and animal photos, popularity on the source site picks the best ones (never shown).
  const popular = Math.min(3, 0.75 * Math.log10(1 + Math.max(p.score || 0, 0)));
  // Most readers are in the UK and Ireland: their stories get a small nudge up.
  const home = p.region === HOME_REGION ? HOME_BONUS : 0;
  return (p.uplift || 5) + popular + (p.imageUrl ? 0.5 : 0) + home - ageHours / 6;
}

const HOME_REGION = "UK & Ireland";
const HOME_BONUS = 1;

// "Top stories" shows NEWS_PER_SOCIAL news stories for every meme / cute-animal post, so the
// world's good news leads the feed and the fun stuff is sprinkled through it.
const NEWS_PER_SOCIAL = 3;

// Random cat and dog photos (topic "Pets") aren't ranked: one appears after every
// POSTS_PER_PET posts, newest first, whichever sort is chosen.
const POSTS_PER_PET = 6;

function isPet(p) {
  return p.community === "Pets";
}

function isSocial(p) {
  return (p.kind || "article") !== "article";
}

function intersperse(posts, pets) {
  if (!posts.length) return pets;
  const out = [];
  let k = 0;
  posts.forEach((p, i) => {
    out.push(p);
    if ((i + 1) % POSTS_PER_PET === 0 && k < pets.length) out.push(pets[k++]);
  });
  return out;
}

function blend(news, social) {
  const out = [];
  let n = 0, s = 0;
  while (n < news.length || s < social.length) {
    for (let i = 0; i < NEWS_PER_SOCIAL && n < news.length; i++) out.push(news[n++]);
    if (s < social.length) out.push(social[s++]);
  }
  return out;
}

function sortPosts(posts, sort) {
  const newest = (a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt);
  const pets = posts.filter(isPet).sort(newest);
  const rest = posts.filter((p) => !isPet(p));
  if (sort === "new") return intersperse(rest.sort(newest), pets);
  const compare = (a, b) => hotScore(b) - hotScore(a);
  return intersperse(blend(rest.filter((p) => !isSocial(p)).sort(compare), rest.filter(isSocial).sort(compare)), pets);
}

/* ------------------------------------------------------------------ actions */

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

/* ------------------------------------------------------------------ components */

function saveButton(p) {
  const saved = !!state.saved[p.id];
  return h("button", {
    class: "pill", "data-save": p.id, "aria-pressed": String(saved), onclick: (e) => { e.preventDefault(); toggleSave(p); },
  }, h("span", { html: saved ? ICON.saved : ICON.save }), saved ? "Saved" : "Save");
}

function actions(p, withReport = false) {
  return h("div", { class: "actions" },
    h("button", { class: "pill", onclick: (e) => { e.preventDefault(); share(p); } }, h("span", { html: ICON.share }), "Share"),
    saveButton(p),
    withReport ? h("button", { class: "pill", onclick: (e) => { e.preventDefault(); openFeedback(p); } },
      h("span", { html: ICON.flag }), "Report") : null,
  );
}

/* ------------------------------------------------------------------ feedback */

const CONFIG = window.SUNNYSIDE_CONFIG || {};
const FEEDBACK_KINDS = [
  ["idea", "💡 An idea or suggestion"],
  ["bug", "🐞 Something isn't working"],
  ["source", "📰 A source we should add"],
  ["other", "💬 Something else"],
];
const REPORT_REASONS = [
  ["not-good-news", "It isn't good news"],
  ["clickbait", "It's clickbait"],
  ["wrong", "It's wrong or misleading"],
  ["broken", "Broken link, picture or video"],
  ["other", "Something else"],
];

/** Where feedback goes: the configured form service, or else a pre-filled GitHub issue. */
async function sendFeedback(data) {
  if (CONFIG.feedbackUrl) {
    const r = await fetch(CONFIG.feedbackUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify(data),
    });
    if (!r.ok) throw new Error("HTTP " + r.status);
    return "sent";
  }
  const body = [
    data.message,
    data.story ? `\n**Story:** [${data.story.title}](${data.story.url}) (${data.story.source}, id \`${data.story.id}\`)` : "",
    `\n_Sent from the Sunnyside website: ${data.page}_`,
  ].join("\n");
  const url = `https://github.com/${CONFIG.repo || "rockstoneballs/test"}/issues/new?` + new URLSearchParams({
    title: data._subject, body, labels: "feedback",
  });
  window.open(url, "_blank", "noopener");
  return "github";
}

/** The feedback form. With a post, it's a report about that post. */
function openFeedback(story) {
  const old = document.getElementById("feedback");
  if (old) old.remove();
  const options = story ? REPORT_REASONS : FEEDBACK_KINDS;
  const status = h("p", { class: "fb-status", role: "status" });
  const submit = h("button", { class: "btn btn-primary", type: "submit" }, "Send");
  const form = h("form", { class: "fb-form" },
    h("div", { class: "fb-head" },
      h("h2", { id: "fb-title" }, story ? "Report this post" : "Send us feedback"),
      h("button", { class: "icon-btn", type: "button", "aria-label": "Close", onclick: () => dialog.close() }, "✕"),
    ),
    story ? h("p", { class: "fb-story" }, story.title) : h("p", { class: "fb-intro" }, "Ideas, problems, sources we're missing: we read everything."),
    h("fieldset", null,
      h("legend", null, story ? "What's wrong with it?" : "What's it about?"),
      options.map(([value, label], i) => h("label", { class: "fb-option" },
        h("input", { type: "radio", name: "kind", value, required: true, checked: i === 0 }), label)),
    ),
    h("label", { class: "fb-field" }, story ? "Anything else? (optional)" : "Your message",
      h("textarea", { name: "message", rows: 4, maxlength: 2000, required: !story })),
    h("label", { class: "fb-field" }, "Your email, if you'd like a reply (optional)",
      h("input", { type: "email", name: "email", autocomplete: "email" })),
    // A field people never see: bots fill it in, so the form service can drop them.
    h("input", { class: "fb-trap", type: "text", name: "_gotcha", tabindex: "-1", autocomplete: "off", "aria-hidden": "true" }),
    h("div", { class: "fb-actions" }, status, submit),
  );
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const f = new FormData(form);
    if (f.get("_gotcha")) return dialog.close();
    const kind = f.get("kind");
    const label = options.find(([v]) => v === kind)[1];
    const data = {
      _subject: story ? `Report: ${label} — ${story.title}`.slice(0, 120) : `Feedback: ${label.replace(/^\S+ /, "")}`,
      kind: story ? "report" : kind,
      reason: story ? kind : undefined,
      message: String(f.get("message") || "").trim(),
      email: String(f.get("email") || "").trim() || undefined,
      story: story ? { id: story.id, title: story.title, url: story.url, source: story.source } : undefined,
      page: location.href,
      platform: "web",
    };
    submit.disabled = true;
    status.textContent = "Sending…";
    try {
      const how = await sendFeedback(data);
      dialog.close();
      toast(how === "sent" ? "Thanks! Your feedback was sent." : "Thanks! Finish sending it on GitHub.");
    } catch (err) {
      status.textContent = "Couldn't send that. Please try again in a moment.";
      submit.disabled = false;
    }
  });
  const dialog = h("dialog", { id: "feedback", class: "fb-dialog", "aria-labelledby": "fb-title" }, form);
  dialog.addEventListener("close", () => dialog.remove());
  dialog.addEventListener("click", (e) => { if (e.target === dialog) dialog.close(); }); // click outside
  document.body.append(dialog);
  dialog.showModal();
}

function postHead(p) {
  const platform = platformOf(p);
  const social = platform !== p.source && p.author && p.author !== p.source;
  return h("div", { class: "post-head" },
    sourceAvatar(p),
    h("span", { class: "source" }, platform),
    h("span", { class: "dot" }),
    h("time", { datetime: p.publishedAt, title: new Date(p.publishedAt).toLocaleString() }, timeAgo(p.publishedAt)),
    social ? h("span", { class: "dot" }) : null,
    social ? h("span", { class: "by" }, "posted by " + p.author) : null,
  );
}

// Clips play muted and looping while they're on screen, like GIFs; controls let people
// unmute or go full screen. Off-screen clips pause so the page stays light.
const clipObserver = "IntersectionObserver" in window
  ? new IntersectionObserver((entries) => {
    for (const e of entries) {
      if (e.isIntersecting && e.intersectionRatio >= 0.5) e.target.play().catch(() => {});
      else e.target.pause();
    }
  }, { threshold: [0, 0.5] })
  : null;

function clip(p, detail) {
  const src = safeUrl(p.videoUrl);
  const attrs = {
    src, poster: safeUrl(p.imageUrl), loop: true, playsinline: true, controls: true,
    preload: detail ? "auto" : "metadata", "aria-label": p.title,
  };
  if (p.imageWidth && p.imageHeight) {
    attrs.width = p.imageWidth;
    attrs.height = p.imageHeight;
  }
  const video = h("video", attrs);
  video.muted = true; // needed for autoplay; people can unmute with the controls
  video.defaultMuted = true;
  if (clipObserver) clipObserver.observe(video);
  else video.autoplay = true;
  return h("div", { class: "media clip" }, video);
}

function media(p, detail) {
  if (p.kind === "video" && safeUrl(p.videoUrl)) return clip(p, detail);
  const img = safeUrl(p.imageUrl);
  const isArticle = (p.kind || "article") === "article";
  if (!img) {
    if (isArticle && !detail) return null;
    return h("div", { class: "media placeholder", style: `background:${topicOf(p).color}22` }, topicOf(p).emoji);
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
    p.kind === "video" ? h("span", { class: "badge" }, "▶ Video — open to watch") : null,
  );
}

// A story's opening paragraphs, as stored by the scraper ("" if none).
function paragraphs(p) {
  return (p.body || "").split(/\n\s*\n/).map((t) => t.trim()).filter(Boolean);
}

function postCard(p) {
  const href = "#/post/" + encodeURIComponent(p.id);
  return h("article", { class: "post" },
    postHead(p),
    h("h2", { class: "post-title" }, h("a", { href }, p.title), flair(p)),
    media(p, false),
    p.summary ? h("p", { class: "post-summary" }, p.summary) : null,
    // News opens on Sunnyside first; the original is one tap further, on the post page.
    p.body ? h("a", { class: "read-more", href }, "Read the story →") : null,
    actions(p),
  );
}

function postRow(p) {
  const href = "#/post/" + encodeURIComponent(p.id);
  const img = safeUrl(p.imageUrl);
  const t = topicOf(p);
  const thumb = h("div", { class: "thumb", style: img ? "" : `background:${t.color}22` },
    img ? h("img", { src: img, alt: "", loading: "lazy", referrerpolicy: "no-referrer" }) : t.emoji);
  return h("article", { class: "post row" },
    thumb,
    h("div", null,
      h("h2", { class: "post-title" }, h("a", { href }, p.title), flair(p)),
      postHead(p),
      actions(p),
    ),
  );
}

/* ------------------------------------------------------------------ chrome */

function renderRightRail() {
  const rail = document.getElementById("right-rail");
  rail.replaceChildren(
    h("section", { class: "card" },
      h("div", { class: "card-head" }, "About Sunnyside"),
      h("div", { class: "card-body" },
        h("p", null, "Only good news. Every story is picked from dedicated good-news outlets, or checked for positivity before it gets here."),
        h("p", null, "Sprinkled in: wholesome memes, cute animals and cat and dog photos, credited to where they came from."),
        h("a", { class: "btn btn-primary btn-block", href: document.getElementById("get-app").href }, "📱 Get the Android app"),
        h("button", { class: "btn btn-block rail-feedback", onclick: () => openFeedback() }, "💬 Send feedback"),
      ),
    ),
    h("div", { class: "rail-links" },
      h("a", { href: "feed.json" }, "feed.json"),
      h("a", { href: "https://github.com/rockstoneballs/test", target: "_blank", rel: "noopener" }, "Source code"),
      h("span", null, "Cat photos: The Cat API · Dog photos: Dog CEO"),
    ),
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
    }, state.view === "card" ? "☰" : "▦", h("span", { class: "view-label" }, state.view === "card" ? " Compact" : " Cards")),
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

function pageHome() {
  const posts = sortPosts(state.posts, state.sort);
  return [
    newPostsButton(), sortBar(),
    feedList(posts, empty("🌤️", "Nothing here yet", "Check back soon — new good news arrives every half hour.")),
  ];
}

function pageSaved() {
  const posts = Object.values(state.saved).sort((a, b) => Date.parse(b.publishedAt) - Date.parse(a.publishedAt));
  document.title = "Saved · Sunnyside";
  return [
    h("h1", { class: "page-title" }, "🔖 Saved"),
    feedList(posts, empty("🔖", "Nothing saved yet", "Hit Save on any post to keep it here for a rainy day.")),
  ];
}

function pageSearch(route) {
  const q = route.arg.trim().toLowerCase();
  const words = q.split(/\s+/).filter(Boolean);
  const posts = sortPosts(state.posts.filter((p) => {
    const hay = `${p.title} ${p.summary || ""} ${p.source} ${topicOf(p).label} ${p.region || ""}`.toLowerCase();
    return words.every((w) => hay.includes(w));
  }), state.sort);
  document.title = `${route.arg} · Sunnyside search`;
  return [
    h("h1", { class: "page-title" }, `Results for “${route.arg}”`),
    sortBar(),
    feedList(posts, empty("🔍", "No matches", "Try a different word — puppies, solar, Kenya…")),
  ];
}

// The story itself: Claude's summary (when there is one) and the article's opening
// paragraphs, credited to the outlet. Without an excerpt, just the summary.
function storyText(p) {
  const paras = paragraphs(p);
  if (!paras.length) return p.summary ? h("p", { class: "post-summary" }, p.summary) : null;
  const written = p.checkedBy === "claude" && p.summary;
  return h("div", { class: "story" },
    written ? h("p", { class: "story-lede" }, h("strong", null, "In short: "), p.summary) : null,
    h("div", { class: "story-body" }, paras.map((t) => h("p", null, t))),
    h("p", { class: "story-credit" }, `The opening of the story, from ${p.source}.`),
  );
}

function pagePost(route) {
  const p = state.byId.get(route.arg) || state.saved[route.arg];
  if (!p) return empty("🍃", "Post not found", "It may have drifted out of the feed. Posts stay for about a week.");
  document.title = p.title + " · Sunnyside";
  const article = (p.kind || "article") === "article";
  const url = safeUrl(p.url);
  const discussion = safeUrl(p.discussionUrl);
  const related = sortPosts(state.posts.filter((o) => topicOf(o) === topicOf(p) && o.id !== p.id), "hot").slice(0, 5);
  return [
    h("button", { class: "back", onclick: () => (history.length > 1 ? history.back() : (location.hash = "#/")), html: ICON.back + " Back" }),
    h("article", { class: "post detail" },
      postHead(p),
      h("h1", { class: "post-title" }, p.title, flair(p),
        p.region && p.region !== "Global" ? h("span", { class: "flair", style: "background:var(--surface-2)" }, "📍 " + p.region) : null),
      media(p, true),
      storyText(p),
      h("div", { class: "detail-cta" },
        article && url ? h("a", { class: "btn btn-primary btn-block", href: url, target: "_blank", rel: "noopener" },
          `${p.body ? "Continue reading" : "Read the full story"} on ${domain(url)} `, h("span", { html: ICON.out })) : null,
        !article && discussion ? h("a", { class: "btn btn-primary btn-block", href: discussion, target: "_blank", rel: "noopener" },
          p.kind === "video" ? "▶ Watch it on " : "View the original post on ", domain(discussion)) : null,
      ),
      actions(p, true),
    ),
    related.length ? h("h2", { class: "section-title" }, "More good news like this") : null,
    related.length ? h("div", { class: "feed compact" }, related.map(postRow)) : null,
  ];
}

/* ------------------------------------------------------------------ routing */

function parseRoute() {
  const hash = decodeURIComponent(location.hash.replace(/^#\/?/, ""));
  const [head, ...rest] = hash.split("/");
  const arg = rest.join("/");
  if (!head) return { name: "home" };
  if (head === "saved") return { name: "saved" };
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

  if (clipObserver) clipObserver.disconnect(); // clips from the previous page are gone
  renderRightRail();
  document.getElementById("saved-link").setAttribute("aria-current", route.name === "saved" ? "page" : "false");

  const main = document.getElementById("main");
  if (!state.feed) {
    main.replaceChildren(state.error
      ? empty("⛅", "Couldn't load the good news", "Check your connection and try again in a moment.")
      : h("div", { class: "feed" }, h("div", { class: "skeleton" }), h("div", { class: "skeleton" }), h("div", { class: "skeleton" })));
    return;
  }

  document.title = "Sunnyside — only good news";
  const pages = { home: pageHome, saved: pageSaved, search: pageSearch, post: pagePost };
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

function setupChrome() {
  document.getElementById("feedback-btn").addEventListener("click", () => openFeedback());
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
  document.addEventListener("visibilitychange", () => { if (!document.hidden) loadFeed(true); });
}

setupChrome();
render();
loadFeed(false);
setInterval(() => loadFeed(true), REFRESH_MS);
