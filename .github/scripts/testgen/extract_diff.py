from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class ChangedFile:
    file_path: str
    changed_line_ranges: list[tuple[int, int]]
    raw_hunk_headers: list[str]


def run_git_command(args: list[str], repo_root: Path | None = None) -> str:
    cmd = ["git"]
    if repo_root is not None:
        cmd.extend(["-C", str(repo_root)])
    cmd.extend(args)

    try:
        result = subprocess.run(
            cmd,
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"Git command failed: {' '.join(cmd)}\n"
            f"stdout:\n{exc.stdout}\n"
            f"stderr:\n{exc.stderr}"
        ) from exc


def get_repo_root() -> Path:
    output = run_git_command(["rev-parse", "--show-toplevel"])
    return Path(output.strip())


def is_target_java_file(relative_path: str) -> bool:
    """
    Only include Java source files under src/main.
    Exclude:
    - src/test/java
    - generated tests
    - non-java files
    """
    normalized = relative_path.replace("\\", "/")

    if not normalized.endswith(".java"):
        return False

    if "/src/main/" not in normalized:
        return False

    if "/src/test/" in normalized:
        return False

    if normalized.endswith("GeneratedTest.java"):
        return False

    return True


def get_changed_java_files(base_ref: str, head_ref: str, repo_root: Path) -> list[str]:
    output = run_git_command(["diff", "--name-only", base_ref, head_ref], repo_root=repo_root)
    print("=== RAW git diff --name-only ===")
    print(output if output.strip() else "(empty)")

    files = [line.strip() for line in output.splitlines() if line.strip()]
    java_files: list[str] = []

    for f in files:
        abs_path = repo_root / f
        print(f"[CHECK] path={f} | abs_exists={abs_path.exists()} | suffix={Path(f).suffix}")

        if is_target_java_file(f) and abs_path.exists():
            java_files.append(f)

    print("=== FILTERED TARGET JAVA FILES (src/main only) ===")
    for f in java_files:
        print(f"- {f}")

    return java_files


def merge_ranges(ranges: list[tuple[int, int]]) -> list[tuple[int, int]]:
    if not ranges:
        return []

    ranges = sorted(ranges, key=lambda x: x[0])
    merged: list[tuple[int, int]] = [ranges[0]]

    for start, end in ranges[1:]:
        last_start, last_end = merged[-1]
        if start <= last_end + 1:
            merged[-1] = (last_start, max(last_end, end))
        else:
            merged.append((start, end))

    return merged


def get_changed_line_ranges(
    file_path: str,
    base_ref: str,
    head_ref: str,
    repo_root: Path,
) -> tuple[list[tuple[int, int]], list[str]]:
    diff_text = run_git_command(["diff", "-U0", base_ref, head_ref, "--", file_path], repo_root=repo_root)

    print(f"\n=== RAW DIFF FOR: {file_path} ===")
    print(diff_text if diff_text.strip() else "(empty)")

    changed_ranges: list[tuple[int, int]] = []
    raw_hunk_headers: list[str] = []

    for line in diff_text.splitlines():
        if not line.startswith("@@"):
            continue

        raw_hunk_headers.append(line)
        print(f"[HUNK] {line}")

        # Examples:
        # @@ -40,2 +40,4 @@
        # @@ -57 +57 @@
        match = re.match(r"^@@\s+-\d+(?:,\d+)?\s+\+(\d+)(?:,(\d+))?\s+@@", line)
        if not match:
            print(f"[WARN] Failed to parse hunk header: {line}")
            continue

        start_line = int(match.group(1))
        count = int(match.group(2) or "1")

        if count == 0:
            print(f"[INFO] count=0 for hunk, skipping: {line}")
            continue

        end_line = start_line + count - 1
        changed_ranges.append((start_line, end_line))

    merged = merge_ranges(changed_ranges)

    print(f"[RANGES] {merged if merged else '(empty)'}")
    return merged, raw_hunk_headers


def collect_changed_files(base_ref: str, head_ref: str, repo_root: Path) -> list[ChangedFile]:
    java_files = get_changed_java_files(base_ref, head_ref, repo_root)
    changed: list[ChangedFile] = []

    for file_path in java_files:
        ranges, hunk_headers = get_changed_line_ranges(file_path, base_ref, head_ref, repo_root)

        changed.append(
            ChangedFile(
                file_path=file_path,
                changed_line_ranges=ranges,
                raw_hunk_headers=hunk_headers,
            )
        )

    return changed


def main() -> None:
    parser = argparse.ArgumentParser(description="Extract changed Java files and changed line ranges.")
    parser.add_argument("--base-ref", default="HEAD~1", help="Base git ref to compare from.")
    parser.add_argument("--head-ref", default="HEAD", help="Head git ref to compare to.")
    parser.add_argument(
        "--output",
        default="outputs/changed_files.json",
        help="Path to save JSON output.",
    )
    args = parser.parse_args()

    repo_root = get_repo_root()
    print(f"=== REPO ROOT ===\n{repo_root}")

    changed_files = collect_changed_files(args.base_ref, args.head_ref, repo_root)

    output_path = repo_root / args.output
    output_path.parent.mkdir(parents=True, exist_ok=True)

    payload = {
        "repo_root": str(repo_root),
        "base_ref": args.base_ref,
        "head_ref": args.head_ref,
        "changed_files": [asdict(item) for item in changed_files],
    }

    output_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"\nSaved changed file metadata to: {output_path}")


if __name__ == "__main__":
    main()