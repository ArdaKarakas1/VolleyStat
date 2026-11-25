"""One server per storage folder.

Two servers on one storage folder would both count uptime into server_clock (sweep.py), both sweep, and both
write media files. A server holds an exclusive lock on the file `server.lock` in its storage folder while its
threads run, and a second server on the same folder refuses to start. The operating system lets the lock go
when the process ends, however it ends, so a crash leaves no stale lock behind. The operator commands
(admin.py) take no lock: they may run beside the server.

The lock needs nothing beyond the standard library: flock on macOS and Linux, a byte-range lock on Windows.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

LOCK_FILE = "server.lock"


class StorageInUse(RuntimeError):
    """Another server process holds the storage folder."""


class StorageLock:
    def __init__(self, folder: Path):
        self.folder = Path(folder)
        self._fd: int | None = None

    def acquire(self) -> None:
        """Hold the folder until release() or the end of the process. Holding it twice is one hold."""
        if self._fd is not None:
            return
        fd = os.open(self.folder / LOCK_FILE, os.O_RDWR | os.O_CREAT | getattr(os, "O_BINARY", 0), 0o600)
        try:
            _lock(fd)
        except OSError:
            os.close(fd)
            raise StorageInUse(
                f"Another VolleyStat server already uses the storage folder {self.folder}. Stop that server first,"
                " or start this one on another folder (VOLLEYSTAT_STORAGE).") from None
        self._fd = fd

    def release(self) -> None:
        if self._fd is None:
            return
        fd, self._fd = self._fd, None
        try:
            _unlock(fd)
        finally:
            os.close(fd)


if sys.platform == "win32":
    import msvcrt

    def _lock(fd: int) -> None:
        # One byte at offset 0. Windows lets a process lock bytes beyond the end of the file.
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)

    def _unlock(fd: int) -> None:
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
else:
    import fcntl

    def _lock(fd: int) -> None:
        # flock belongs to the open file, not to the process: a second open in the same process is refused
        # too, which is what lets a test start two servers in one process.
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def _unlock(fd: int) -> None:
        fcntl.flock(fd, fcntl.LOCK_UN)
