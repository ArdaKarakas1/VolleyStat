"""Run the local development API: python -m app [--port 8000] [--analyzer demo].

The server binds to the loopback interface only. It has no accounts and no TLS,
so it must not be reachable from another machine.
"""
import argparse

import uvicorn

from .analyzers import REGISTRY, AnalyzerNotAvailable
from .main import create_app


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument(
        "--analyzer", choices=("none", *sorted(REGISTRY)), default=None,
        help="Analyzer that proposes rallies. Default: the VOLLEYSTAT_ANALYZER variable, or none. "
             "'demo' invents intervals for interface work and flags every job as mock.",
    )
    args = parser.parse_args()
    try:
        app = create_app(analyzer=args.analyzer)
    except AnalyzerNotAvailable as error:
        parser.error(str(error))
    uvicorn.run(app, host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
