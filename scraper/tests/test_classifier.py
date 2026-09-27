import json

import anthropic
import httpx2 as httpx

from goodnews.classifier import ClaudeClassifier


def _classifier_with(handler):
    clf = ClaudeClassifier.__new__(ClaudeClassifier)
    clf._anthropic = anthropic
    clf._model = "claude-opus-5"
    clf._client = anthropic.Anthropic(api_key="test", http_client=httpx.Client(transport=httpx.MockTransport(handler)))
    return clf


def _message(results, stop_reason="end_turn"):
    return {
        "id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5",
        "content": [{"type": "text", "text": json.dumps({"results": results})}],
        "stop_reason": stop_reason, "stop_sequence": None,
        "usage": {"input_tokens": 10, "output_tokens": 10},
    }


def test_classify_sends_structured_request_and_parses_verdicts():
    seen = {}

    def handler(request):
        seen["body"] = json.loads(request.content)
        seen["beta"] = request.headers.get("anthropic-beta")
        return httpx.Response(200, json=_message([
            {"i": 0, "good_news": True, "uplift": 9, "category": "Animals", "region": "Asia", "summary": "Tigers are back."},
            {"i": 1, "good_news": False, "uplift": 1, "category": "Community", "region": "Global", "summary": "Bad."},
        ]))

    verdicts = _classifier_with(handler).classify([
        {"title": "Tigers triple", "summary": "Nepal", "source": "BBC"},
        {"title": "Crash", "summary": "", "source": "BBC"},
    ])
    assert verdicts[0].good_news and verdicts[0].uplift == 9 and verdicts[0].region == "Asia"
    assert not verdicts[1].good_news
    assert seen["body"]["model"] == "claude-opus-5"
    assert seen["body"]["output_config"]["format"]["type"] == "json_schema"
    assert seen["body"]["fallbacks"] == "default"
    assert "server-side-fallback-2026-07-01" in seen["beta"]


def test_refusal_or_error_falls_back_to_keywords():
    refused = _classifier_with(lambda r: httpx.Response(200, json=_message([], stop_reason="refusal")))
    assert refused.classify([{"title": "x", "summary": "", "source": "s"}]) == {}

    broken = _classifier_with(lambda r: httpx.Response(400, json={"type": "error", "error": {"type": "invalid_request_error", "message": "nope"}}))
    assert broken.classify([{"title": "x", "summary": "", "source": "s"}]) == {}
