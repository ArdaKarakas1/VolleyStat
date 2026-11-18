"""Run the local development API: python -m app [--port 8000] [--analyzer demo].

The server binds to the loopback interface only. It has no TLS, so neither a session token nor a
video may travel to it over a network, and it must not be reachable from another machine. It writes no access log, so no
address or path of a request reaches the disk, and it ignores X-Forwarded-For.
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
    uvicorn.run(app, host="127.0.0.1", port=args.port, access_log=False, forwarded_allow_ips="")


if __name__ == "__main__":
    main()
