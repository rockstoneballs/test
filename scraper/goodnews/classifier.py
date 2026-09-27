"""Optional Claude-powered good-news classifier.

Keyword filters can't tell "rescuers saved 40 people" from "40 people died
despite rescue efforts". When ANTHROPIC_API_KEY is set, each new candidate
story is sent to Claude, which decides whether it is genuinely good news and
tags its topic and region. Without a key, the scraper falls back to
keywords.py.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass

from .keywords import CATEGORIES, REGIONS

log = logging.getLogger(__name__)

DEFAULT_MODEL = "claude-opus-5"
BATCH_SIZE = 20

SYSTEM_PROMPT = f"""\
You are the editor of Sunnyside, a news app people open first thing in the morning \
instead of the usual doom-laden headlines. Every story in the app must leave a reader \
feeling better about the world while still being true and newsworthy.

You'll receive a JSON array of candidate stories (headline, snippet, outlet, and \
often the opening of the article as "text"). For each one decide:

- good_news: true only if the core of the story is positive — progress, discovery, \
recovery, kindness, conservation wins, health breakthroughs, people helping people, \
delightful animal news, uplifting culture. Mark false if the story centres on \
death, violence, war, crime, disaster, political conflict, scandal, economic pain or \
fear, even if it contains a silver lining. Mark false for adverts, listicles of \
products, opinion pieces without news, and anything mainly about a single \
controversial politician, and anything about politics, government, politicians, \
elections, markets or personal finance. Also mark false for celebrity and showbiz stories \
(film/TV/music stars, influencers, awards shows) and anything about royalty or \
monarchies, anything about sport or athletes, and anything not written in \
English. Also mark false for clickbait: teaser or hype headlines ("you won't \
believe", "melts hearts", "the internet is loving"), viral-video roundups, \
listicles, advice and how-to pieces, quizzes, deals, notices and appeals.
- uplift: 0-10, how much this would brighten a reader's morning (10 = pure joy).
- category: the best fit from {CATEGORIES}.
- region: where the story happens, from {REGIONS}. Use "Global" for worldwide or \
unclear stories, and "UK & Ireland" for anything in the UK or Ireland. Sunnyside's \
readers are mainly in the UK and Ireland, then Europe, North America and Oceania, so be a \
little stricter about uplift for stories from elsewhere unless \
they're remarkable.
- summary: two or three plain sentences (max 70 words) telling the reader what \
happened, who was involved and why it matters, from the article text when given. \
Factual and warm, in your own words, no hype, no emoji, don't start with "In a".

Return one result per input story, matching its "i" index."""

_RESULT_SCHEMA = {
    "type": "object",
    "properties": {
        "results": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "i": {"type": "integer"},
                    "good_news": {"type": "boolean"},
                    "uplift": {"type": "integer"},
                    "category": {"type": "string", "enum": CATEGORIES},
                    "region": {"type": "string", "enum": REGIONS},
                    "summary": {"type": "string"},
                },
                "required": ["i", "good_news", "uplift", "category", "region", "summary"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["results"],
    "additionalProperties": False,
}


@dataclass
class Verdict:
    good_news: bool
    uplift: int
    category: str
    region: str
    summary: str


class ClaudeClassifier:
    def __init__(self, model: str | None = None):
        import anthropic  # imported lazily so the keyword path has no hard dependency

        self._anthropic = anthropic
        self._client = anthropic.Anthropic()
        self._model = model or os.environ.get("GOODNEWS_MODEL", DEFAULT_MODEL)

    @staticmethod
    def available() -> bool:
        return bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"))

    def classify(self, stories: list[dict]) -> dict[int, Verdict]:
        """Classify ``stories`` (dicts with title/summary/source). Returns index -> Verdict.

        Batches that fail (API error, refusal, bad output) are simply missing from
        the result; the caller falls back to keyword filtering for those.
        """
        verdicts: dict[int, Verdict] = {}
        for start in range(0, len(stories), BATCH_SIZE):
            batch = stories[start:start + BATCH_SIZE]
            payload = [
                {"i": start + n, "headline": s["title"], "snippet": s["summary"][:600], "outlet": s["source"],
                 **({"text": s["body"][:2000]} if s.get("body") else {})}
                for n, s in enumerate(batch)
            ]
            verdicts.update(self._classify_batch(payload))
        return verdicts

    def _classify_batch(self, payload: list[dict]) -> dict[int, Verdict]:
        anthropic = self._anthropic
        try:
            response = self._client.beta.messages.create(
                model=self._model,
                max_tokens=16000,
                system=SYSTEM_PROMPT,
                messages=[{"role": "user", "content": json.dumps(payload, ensure_ascii=False)}],
                output_config={
                    "effort": "low",
                    "format": {"type": "json_schema", "schema": _RESULT_SCHEMA},
                },
                betas=["server-side-fallback-2026-07-01"],
                fallbacks="default",
            )
        except anthropic.RateLimitError as e:
            log.warning("Claude rate limited, falling back to keywords for this batch: %s", e.message)
            return {}
        except anthropic.APIStatusError as e:
            log.warning("Claude API error %s, falling back to keywords: %s", e.status_code, e.message)
            return {}
        except anthropic.APIConnectionError as e:
            log.warning("Could not reach Claude, falling back to keywords: %s", e)
            return {}

        if response.stop_reason != "end_turn":
            log.warning("Claude stopped with %s; falling back to keywords for this batch", response.stop_reason)
            return {}

        text = next((b.text for b in response.content if b.type == "text"), "")
        try:
            results = json.loads(text)["results"]
        except (ValueError, KeyError) as e:
            log.warning("Unparseable classifier output (%s); falling back to keywords", e)
            return {}

        valid_ids = {p["i"] for p in payload}
        out: dict[int, Verdict] = {}
        for r in results:
            if r["i"] in valid_ids:
                out[r["i"]] = Verdict(
                    good_news=r["good_news"],
                    uplift=max(0, min(10, r["uplift"])),
                    category=r["category"],
                    region=r["region"],
                    summary=r["summary"].strip(),
                )
        return out
