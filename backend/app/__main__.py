"""Run the local development API: python -m app [--port 8000].

The server binds to the loopback interface only. It has no accounts and no TLS,
so it must not be reachable from another machine.
"""
import argparse

import uvicorn


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    uvicorn.run("app.main:create_app", factory=True, host="127.0.0.1", port=args.port)


if __name__ == "__main__":
    main()
