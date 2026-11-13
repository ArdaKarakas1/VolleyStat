"""Analyzer selection. No analyzer is the default: the server then says so (503) instead of guessing."""
from __future__ import annotations

import importlib

from .base import Analyzer

# name -> "module:class". Modules are imported only when selected, so the server starts
# without the optional dependencies of analyzers it does not use.
REGISTRY = {
    "demo": "app.analyzers.demo:DemoAnalyzer",
    "motion-baseline": "app.analyzers.motion_baseline:MotionBaselineAnalyzer",
}


class AnalyzerNotAvailable(Exception):
    pass


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
