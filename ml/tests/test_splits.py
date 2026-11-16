import csv
import hashlib
from collections import Counter
from importlib import resources

import pytest

from volleystat_ml import splits
from volleystat_ml.splits import (
    TEST, TRAIN, VALIDATION, SplitError, Video, assign_splits, find_changed_splits, find_leakage,
    read_inventory, read_lock_manifest, training_violations, write_lock_manifest,
)

LOCKED_AT = "2026-11-12T00:00:00Z"


def digest(name):
    return hashlib.sha256(name.encode()).hexdigest()


def video(video_id, group, *, allowed=True, split=None, sha=None):
    return Video(video_id, group, sha or digest(video_id), training_allowed=allowed, split=split,
                 arena_id=f"arena-{group}", camera_setup_id="cam-1")


def library(groups=10, per_group=2):
    return [video(f"V{group:02d}{letter}", f"M{group:02d}")
            for group in range(groups) for letter in "abcdef"[:per_group]]


def write_inventory(path, videos, *, delimiter=",", encoding="utf-8-sig", overrides=None):
    with open(path, "w", encoding=encoding, newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=splits.INVENTORY_COLUMNS, delimiter=delimiter, lineterminator="\r\n")
        writer.writeheader()
        for item in videos:
            row = dict.fromkeys(splits.INVENTORY_COLUMNS, "")
            row.update(video_id=item.video_id, match_group_id=item.match_group_id, sha256=item.sha256,
                       training_allowed="true" if item.training_allowed else "false", split=item.split or "",
                       arena_id=item.arena_id, camera_setup_id=item.camera_setup_id)
            row.update((overrides or {}).get(item.video_id, {}))
            writer.writerow(row)
    return path


def problems_of(call):
    with pytest.raises(SplitError) as caught:
        call()
    return caught.value.problems


# --- templates -------------------------------------------------------------------------

@pytest.mark.parametrize("template, columns", [
    ("01_VIDEO_ENVANTERI.csv", splits.INVENTORY_COLUMNS),
    ("11_TEST_MANIFEST.csv", splits.MANIFEST_COLUMNS),
])
def test_column_lists_match_the_handover_templates(template, columns):
    text = resources.files("volleystat_ml.schemas").joinpath(template).read_text(encoding="utf-8-sig")
    assert tuple(text.splitlines()[0].split(",")) == columns


def test_rally_gold_template_has_the_columns_the_evaluation_reads():
    text = resources.files("volleystat_ml.schemas").joinpath("03_RALLI_GOLD.csv").read_text(encoding="utf-8-sig")
    assert {"video_id", "start_ms", "end_ms"} <= set(text.splitlines()[0].split(","))


def test_empty_template_is_refused_instead_of_producing_an_empty_split(tmp_path):
    template = resources.files("volleystat_ml.schemas").joinpath("01_VIDEO_ENVANTERI.csv")
    path = tmp_path / "inventory.csv"
    path.write_bytes(template.read_bytes())
    assert read_inventory(path) == []
    assert problems_of(lambda: assign_splits([])) == ["the inventory has no videos"]


# --- assignment ------------------------------------------------------------------------

def test_no_match_group_is_divided():
    videos = library(groups=12, per_group=3)
    assignment = assign_splits(videos, seed=5)
    assert set(assignment) == {item.video_id for item in videos}
    for group in {item.match_group_id for item in videos}:
        assert len({assignment[item.video_id] for item in videos if item.match_group_id == group}) == 1
    assert find_leakage(videos, assignment) == []


def test_shares_follow_the_ratios():
    videos = library(groups=20, per_group=1)
    assert Counter(assign_splits(videos).values()) == {TRAIN: 14, VALIDATION: 3, TEST: 3}
    custom = assign_splits(videos, ratios={TRAIN: 0.5, VALIDATION: 0.25, TEST: 0.25})
    assert Counter(custom.values()) == {TRAIN: 10, VALIDATION: 5, TEST: 5}


def test_assignment_is_reproducible_and_depends_on_the_seed():
    videos = library(groups=20, per_group=2)
    assert assign_splits(videos, seed=3) == assign_splits(list(reversed(videos)), seed=3)
    assert assign_splits(videos, seed=3) != assign_splits(videos, seed=4)


def test_few_groups_fill_test_before_validation_and_never_split_a_group():
    one = [video("V1", "M1"), video("V2", "M1")]
    assert set(assign_splits(one).values()) == {TRAIN}
    three = [video("V1", "M1"), video("V2", "M2"), video("V3", "M3")]
    assert Counter(assign_splits(three).values()) == {TRAIN: 2, TEST: 1}


def test_video_without_training_permission_never_enters_train():
    videos = library(groups=10, per_group=2)
    blocked = {"M01", "M04", "M07"}
    # One video without permission is enough to keep its whole match out of train.
    videos = [video(item.video_id, item.match_group_id, allowed=not (item.match_group_id in blocked and item.video_id.endswith("a")))
              for item in videos]
    for seed in range(20):
        assignment = assign_splits(videos, seed=seed)
        assert training_violations(videos, assignment) == []
        assert all(assignment[item.video_id] != TRAIN for item in videos if item.match_group_id in blocked)
        assert find_leakage(videos, assignment) == []


def test_unknown_training_permission_counts_as_not_allowed():
    videos = [Video("V1", "M1", digest("V1"), training_allowed=None), video("V2", "M2"), video("V3", "M3")]
    assert assign_splits(videos)["V1"] != TRAIN
    assert training_violations([Video("V1", "M1", digest("V1"), split=TRAIN)]) == [
        "video 'V1' is in the train split but its training_allowed is not true",
    ]


def test_recorded_splits_are_kept_and_shared_with_the_rest_of_the_group():
    videos = library(groups=10, per_group=2)
    videos[0] = video("V00a", "M00", split=TEST)       # V00b has no split yet
    videos[4] = video("V02a", "M02", split=VALIDATION)
    videos[5] = video("V02b", "M02", split=VALIDATION)
    for seed in range(10):
        assignment = assign_splits(videos, seed=seed)
        assert (assignment["V00a"], assignment["V00b"]) == (TEST, TEST)
        assert (assignment["V02a"], assignment["V02b"]) == (VALIDATION, VALIDATION)


def test_recorded_conflicts_are_refused():
    divided = [video("V1", "M1", split=TRAIN), video("V2", "M1", split=TEST), video("V3", "M2")]
    assert problems_of(lambda: assign_splits(divided)) == [
        "match_group_id 'M1' is already recorded in more than one split: test, train",
    ]
    forbidden = [video("V1", "M1", split=TRAIN, allowed=False), video("V2", "M1", split=TRAIN)]
    assert problems_of(lambda: assign_splits(forbidden)) == [
        "video 'V1' is in the train split but its training_allowed is not true",
    ]


def test_same_file_under_two_match_groups_is_refused():
    videos = [video("V1", "M1", sha=digest("same")), video("V2", "M2", sha=digest("same")), video("V3", "M3")]
    assert problems_of(lambda: assign_splits(videos)) == [
        f"sha256 {digest('same')} is listed under several match groups: M1, M2",
    ]
    # Re-encoded or duplicated copies inside one match are fine: they stay together anyway.
    copies = [video("V1", "M1", sha=digest("same")), video("V2", "M1", sha=digest("same")), video("V3", "M2")]
    assignment = assign_splits(copies)
    assert assignment["V1"] == assignment["V2"]


def test_duplicate_video_id_and_bad_ratios_are_refused():
    assert problems_of(lambda: assign_splits([video("V1", "M1"), video("V1", "M2", sha=digest("other"))])) == [
        "video_id 'V1' appears more than once",
    ]
    for ratios in ({TRAIN: 0.5, VALIDATION: 0.2, TEST: 0.2}, {TRAIN: 1.2, VALIDATION: -0.2, TEST: 0}, {TRAIN: 1.0}):
        with pytest.raises(ValueError):
            assign_splits(library(), ratios=ratios)


# --- previous lock ---------------------------------------------------------------------

def as_locked(videos, assignment):
    """What a lock manifest of this assignment gives back when it is read."""
    return [Video(item.video_id, item.match_group_id, item.sha256, split=assignment[item.video_id])
            for item in videos]


def test_previous_lock_keeps_every_assignment_when_matches_are_added():
    # Without the previous lock the same seed can move a locked test match into train as
    # soon as one match is added, because the shares are worked out again from nothing.
    moved_without = 0
    for seed in range(3):
        for count in range(3, 13):
            videos = library(groups=count + 1, per_group=1)
            first = assign_splits(videos[:count], seed=seed)
            again = assign_splits(videos, seed=seed)
            moved_without += sum(again[video_id] != split for video_id, split in first.items())

            second = assign_splits(videos, seed=seed, previous=as_locked(videos[:count], first))
            assert {video_id: second[video_id] for video_id in first} == first
            assert set(second) == {item.video_id for item in videos}
            assert find_leakage(videos, second) == []
    assert moved_without > 0


def test_new_video_of_a_locked_match_joins_the_split_of_its_match():
    videos = library(groups=6, per_group=1)
    first = assign_splits(videos, seed=2)
    second_camera = [video(f"{item.video_id}-cam2", item.match_group_id) for item in videos]
    second = assign_splits(videos + second_camera, seed=2, previous=as_locked(videos, first))
    assert all(second[f"{item.video_id}-cam2"] == first[item.video_id] for item in videos)


def test_previous_lock_that_can_not_be_kept_is_refused():
    locked = [Video("V1", "M1", digest("V1"), split=TEST), Video("V2", "M2", digest("V2"), split=TRAIN)]
    recorded_elsewhere = [video("V1", "M1", split=TRAIN), video("V2", "M2")]
    assert problems_of(lambda: assign_splits(recorded_elsewhere, previous=locked)) == [
        "match_group_id 'M1' is test in the previous lock, but the inventory records train",
    ]
    # V1 was locked as test under M1 and now sits in M2, which is locked as train.
    regrouped = [video("V1", "M2"), video("V2", "M2")]
    assert problems_of(lambda: assign_splits(regrouped, previous=locked)) == [
        "match_group_id 'M2' can not keep the previous lock, which has its videos or its match in more than "
        "one split: train, test",
    ]
    no_longer_allowed = [video("V1", "M1"), video("V2", "M2", allowed=False)]
    assert problems_of(lambda: assign_splits(no_longer_allowed, previous=locked)) == [
        "video 'V2' is in the train split but its training_allowed is not true",
    ]


def test_video_or_match_in_different_splits_across_files_is_reported():
    first = [video("V1", "M1", split=TEST), video("V2", "M2", split=TRAIN), video("V3", "M3", split=TRAIN)]
    second = [video("V1", "M1", split=TRAIN), video("V2", "M2", split=TRAIN),
              video("V4", "M3", split=VALIDATION), video("V5", "M5")]
    assert find_changed_splits({"A.csv": first, "B.csv": second}) == [
        "video_id 'V1' is in different splits across the files (train: B.csv; test: A.csv)",
        "match_group_id 'M1' is in different splits across the files (train: B.csv; test: A.csv)",
        "match_group_id 'M3' is in different splits across the files (train: A.csv; validation: B.csv)",
    ]
    assert find_changed_splits({"A.csv": first, "copy.csv": first}) == []
    assert find_changed_splits({"A.csv": first}) == []


# --- leakage ---------------------------------------------------------------------------

def test_leakage_by_match_group_and_by_file_hash_is_reported():
    shared = digest("one file")
    videos = [
        video("V1", "M1", split=TRAIN), video("V2", "M1", split=TEST),            # match in two splits
        video("V3", "M2", split=TRAIN, sha=shared), video("V4", "M3", split=VALIDATION, sha=shared),  # file in two splits
        video("V5", "M4", split=TEST), video("V6", "M4", split=TEST),            # clean
        video("V7", "M5"),                                                         # no split yet
    ]
    assert find_leakage(videos) == [
        "match_group_id 'M1' is in 2 splits (train: V1; test: V2)",
        f"sha256 {shared} is in 2 splits (train: V3; validation: V4)",
    ]


def test_clean_split_has_no_leakage_and_an_assignment_overrides_recorded_splits():
    videos = [video("V1", "M1", split=TRAIN), video("V2", "M1", split=TRAIN), video("V3", "M2", split=TEST)]
    assert find_leakage(videos) == []
    assert find_leakage(videos, {"V1": TRAIN, "V2": VALIDATION, "V3": TEST}) == [
        "match_group_id 'M1' is in 2 splits (train: V1; validation: V2)",
    ]


# --- inventory file --------------------------------------------------------------------

def test_inventory_is_read_with_byte_order_mark_crlf_and_either_delimiter(tmp_path):
    videos = [video("V1", "M1", split=TEST), video("V2", "M2", allowed=False)]
    for delimiter in ",;":
        path = write_inventory(tmp_path / f"inventory{ord(delimiter)}.csv", videos, delimiter=delimiter)
        assert read_inventory(path) == videos


def test_training_allowed_is_true_only_for_the_word_true(tmp_path):
    videos = [video(f"V{index}", f"M{index}") for index in range(6)]
    values = ["true", "TRUE", " True ", "", "yes", "1"]
    path = write_inventory(tmp_path / "inventory.csv", videos,
                           overrides={f"V{index}": {"training_allowed": value} for index, value in enumerate(values)})
    assert [item.training_allowed for item in read_inventory(path)] == [True, True, True, False, False, False]


def test_split_names_are_normalised_and_unknown_names_refused(tmp_path):
    videos = [video("V1", "M1"), video("V2", "M2"), video("V3", "M3")]
    path = write_inventory(tmp_path / "ok.csv", videos, overrides={"V1": {"split": "Train"}, "V2": {"split": "val"}})
    assert [item.split for item in read_inventory(path)] == [TRAIN, VALIDATION, None]
    path = write_inventory(tmp_path / "bad.csv", videos, overrides={"V3": {"split": "holdout"}})
    assert problems_of(lambda: read_inventory(path)) == [
        "row 4 (video_id 'V3'): unknown split 'holdout'; use train, validation or test",
    ]


def test_unusable_inventory_rows_are_all_reported(tmp_path):
    videos = [video("V1", "M1"), video("V2", "M2"), video("V3", "M3"), video("V1", "M4", sha=digest("x"))]
    path = write_inventory(tmp_path / "inventory.csv", videos, overrides={
        "V2": {"match_group_id": ""}, "V3": {"sha256": "abc123"},
    })
    assert problems_of(lambda: read_inventory(path)) == [
        "row 3 (video_id 'V2'): match_group_id is empty; the video can not be kept with its match",
        "row 4 (video_id 'V3'): sha256 must be 64 hexadecimal characters",
        "video_id 'V1' appears more than once",
    ]


def test_inventory_row_with_more_cells_than_the_header_is_refused(tmp_path):
    path = write_inventory(tmp_path / "inventory.csv", library(groups=2, per_group=1))
    lines = path.read_text(encoding="utf-8-sig").splitlines()
    # A comma in the last cell that nobody quoted, and a stray delimiter at the end of a row.
    lines[1] += ", second camera"
    lines[2] += ","
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    assert problems_of(lambda: read_inventory(path)) == [
        "row 2: 21 cells, but the header has 20 columns",
        "row 3: 21 cells, but the header has 20 columns",
    ]


def test_file_that_is_not_utf8_is_refused_instead_of_decoded_by_guessing(tmp_path):
    path = write_inventory(tmp_path / "inventory.csv", library(groups=2, per_group=1), encoding="cp1254",
                           overrides={"V00a": {"notes": "İkinci kamera, ışık düşük"}})
    for read in (read_inventory, read_lock_manifest, splits.is_lock_manifest):
        problems = problems_of(lambda: read(path))
        assert len(problems) == 1 and problems[0].startswith("the file is not UTF-8 text (byte ")


def test_inventory_without_the_needed_columns_is_refused(tmp_path):
    path = tmp_path / "inventory.csv"
    path.write_text("video_id,sha256\nV1,abc\n", encoding="utf-8")
    assert problems_of(lambda: read_inventory(path)) == [
        "inventory is missing column(s): match_group_id, training_allowed",
    ]


# --- lock manifest ---------------------------------------------------------------------

def locked(tmp_path, videos=None):
    videos = videos or library(groups=8, per_group=2)
    assignment = assign_splits(videos, seed=1)
    path = tmp_path / "TEST_LOCK_V1.csv"
    return path, videos, assignment, write_lock_manifest(path, videos, assignment, locked_at=LOCKED_AT)


def test_lock_manifest_uses_the_template_columns_and_records_its_own_hash(tmp_path):
    path, videos, assignment, returned = locked(tmp_path)
    lines = path.read_text(encoding="utf-8").splitlines()
    assert lines[0] == ",".join(splits.MANIFEST_COLUMNS)
    assert len(lines) == 1 + len(videos) + 1
    assert [line.split(",")[0] for line in lines[1:-1]] == sorted(item.video_id for item in videos)
    # The last line is the SHA-256 of everything above it.
    content = "\n".join(lines[:-1]) + "\n"
    assert lines[-1] == f"# content_sha256={hashlib.sha256(content.encode()).hexdigest()}"
    assert returned == lines[-1].split("=")[1]

    manifest = read_lock_manifest(path)
    assert manifest.intact and manifest.stored_sha256 == returned
    assert {item.video_id: item.split for item in manifest.videos} == assignment
    by_id = {item.video_id: item for item in videos}
    assert all((item.match_group_id, item.sha256, item.arena_id) ==
               (by_id[item.video_id].match_group_id, by_id[item.video_id].sha256, by_id[item.video_id].arena_id)
               for item in manifest.videos)
    assert f",{splits.ANNOTATION_VERSION},{LOCKED_AT}," in lines[1]


def test_same_input_gives_the_same_manifest_hash(tmp_path):
    (tmp_path / "a").mkdir()
    (tmp_path / "b").mkdir()
    assert locked(tmp_path / "a")[3] == locked(tmp_path / "b")[3]


def test_edit_after_locking_is_detected(tmp_path):
    path, videos, assignment, _ = locked(tmp_path)
    moved = videos[0].video_id
    other = TEST if assignment[moved] != TEST else VALIDATION
    text = path.read_text(encoding="utf-8")
    path.write_text(text.replace(f",{assignment[moved]},", f",{other},", 1), encoding="utf-8")
    manifest = read_lock_manifest(path)
    assert not manifest.intact
    assert manifest.stored_sha256 != manifest.computed_sha256

    removed = tmp_path / "removed.csv"
    lines = text.splitlines()
    removed.write_text("\n".join(lines[:1] + lines[2:]) + "\n", encoding="utf-8")
    assert not read_lock_manifest(removed).intact


def test_byte_order_mark_and_line_endings_do_not_count_as_an_edit(tmp_path):
    path, _, _, _ = locked(tmp_path)
    resaved = tmp_path / "resaved.csv"
    resaved.write_bytes(b"\xef\xbb\xbf" + path.read_bytes().replace(b"\n", b"\r\n"))
    assert read_lock_manifest(resaved).intact


def test_manifest_without_a_hash_line_is_not_intact(tmp_path):
    path, _, _, _ = locked(tmp_path)
    bare = tmp_path / "bare.csv"
    bare.write_text("\n".join(path.read_text(encoding="utf-8").splitlines()[:-1]) + "\n", encoding="utf-8")
    manifest = read_lock_manifest(bare)
    assert manifest.stored_sha256 is None and not manifest.intact


def test_manifest_row_with_more_cells_than_the_header_is_a_problem(tmp_path):
    path, _, _, _ = locked(tmp_path)
    assert read_lock_manifest(path).problems == ()
    lines = path.read_text(encoding="utf-8").splitlines()
    lines[3] += ",appended"
    extended = tmp_path / "extended.csv"
    extended.write_text("\n".join(lines) + "\n", encoding="utf-8")
    manifest = read_lock_manifest(extended)
    # The hash covers the named columns only, so it does not see the appended cell.
    assert manifest.intact
    assert manifest.problems == ("row 4: 10 cells, but the header has 9 columns",)


def test_existing_manifest_is_never_overwritten(tmp_path):
    path, videos, assignment, _ = locked(tmp_path)
    before = path.read_bytes()
    with pytest.raises(FileExistsError):
        write_lock_manifest(path, videos, assignment, locked_at=LOCKED_AT)
    assert path.read_bytes() == before


def test_manifest_refuses_leakage_missing_splits_and_forbidden_training(tmp_path):
    videos = [video("V1", "M1"), video("V2", "M1"), video("V3", "M2", allowed=False), video("V4", "M3")]
    path = tmp_path / "lock.csv"
    assert problems_of(lambda: write_lock_manifest(
        path, videos, {"V1": TRAIN, "V2": TEST, "V3": TRAIN}, locked_at=LOCKED_AT)) == [
        "video 'V4' has no split",
        "match_group_id 'M1' is in 2 splits (train: V1; test: V2)",
        "video 'V3' is in the train split but its training_allowed is not true",
    ]
    assert not path.exists()


def test_other_csv_files_are_not_taken_for_a_manifest(tmp_path):
    inventory = write_inventory(tmp_path / "inventory.csv", library(groups=2))
    assert not splits.is_lock_manifest(inventory)
    assert problems_of(lambda: read_lock_manifest(inventory))[0].startswith("not a lock manifest")
    path, _, _, _ = locked(tmp_path)
    assert splits.is_lock_manifest(path)
