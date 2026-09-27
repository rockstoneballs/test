// Site settings. The publish workflow rewrites this file from the repository's
// Actions variables (Settings → Secrets and variables → Actions → Variables).
//
// feedbackUrl: where the feedback form POSTs (JSON). Any form service that accepts JSON
//   works, e.g. a free Formspree form ("https://formspree.io/f/xxxxxxx"). While it's
//   empty, the form opens a pre-filled GitHub issue instead.
window.SUNNYSIDE_CONFIG = {
  feedbackUrl: "",
  repo: "rockstoneballs/test",
};
