"""Analyzer selection. No analyzer is the default: the server then says so (503) instead of guessing."""
from __future__ import annotations

import importlib

from .base import BASELINE, MOCK, MODEL, Analyzer

# name -> "module:class". Modules are imported only when selected, so the server starts
# without the optional dependencies of analyzers it does not use.
REGISTRY = {
    "demo": "app.analyzers.demo:DemoAnalyzer",
    "motion-baseline": "app.analyzers.motion_baseline:MotionBaselineAnalyzer",
}
KINDS = (MOCK, BASELINE, MODEL)


class AnalyzerNotAvailable(Exception):
    pass


def require_known_kind(analyzer: Analyzer) -> None:
    """Refuse an analyzer whose kind the contract does not define.

    The kind decides whether a job is flagged mock and what its analyzer may claim, so a value
    such as "mock" or "DEMO" must not slip through as "not a mock".
    """
    kind = analyzer.info.kind
    if not (isinstance(kind, str) and kind in KINDS):
        raise AnalyzerNotAvailable(
            f"Analyzer '{analyzer.info.id}' declares kind {kind!r}. The kind must be exactly one of: {', '.join(KINDS)}")


def load_analyzer(name: str | None) -> Analyzer | None:
    """Return the configured analyzer, or None when none is configured."""
    if name is None or name.strip().lower() in ("", "none"):
        return None
    target = REGISTRY.get(name.strip().lower())
    if target is None:
        raise AnalyzerNotAvailable(f"Unknown analyzer '{name}'. Known: {', '.join(sorted(REGISTRY))}")
    module_name, class_name = target.split(":")
    try:
        return getattr(importlib.import_module(module_name), class_name)()
    except ImportError as error:
        raise AnalyzerNotAvailable(f"Analyzer '{name}' is not installed: {error}") from error
