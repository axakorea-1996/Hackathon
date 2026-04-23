'''
변경된 줄이 포함된 Java 메서드 전체 추출
'''
from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


METHOD_DECLARATION_RE = re.compile(
    r"""
    ^\s*
    (?:public|protected|private)?
    \s*
    (?:static\s+)?
    (?:final\s+)?
    (?:synchronized\s+)?
    (?:abstract\s+)?
    (?:native\s+)?
    [\w\<\>\[\],\s?\.]+
    \s+
    (?P<name>[A-Za-z_]\w*)
    \s*
    \(
    """,
    re.VERBOSE,
)

CLASS_RE   = re.compile(r"\bclass\s+([A-Za-z_]\w*)\b")
PACKAGE_RE = re.compile(r"^\s*package\s+([\w\.]+)\s*;", re.MULTILINE)


@dataclass
class JavaMethod:
    file_path:      str
    package_name:   str | None
    class_name:     str | None
    method_name:    str
    start_line:     int
    end_line:       int
    signature_line: str
    code:           str


def run_git_command(args: list[str]) -> str:
    result = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


def get_repo_root() -> Path:
    return Path(run_git_command(["rev-parse", "--show-toplevel"]))


def load_changed_files(json_path: str, repo_root: Path) -> list[dict]:
    path = Path(json_path)
    if not path.is_absolute():
        path = repo_root / path
    data = json.loads(path.read_text(encoding="utf-8"))
    return data.get("changed_files", [])


def find_package_name(content: str) -> str | None:
    match = PACKAGE_RE.search(content)
    return match.group(1) if match else None


def find_class_name(lines: list[str]) -> str | None:
    for line in lines:
        match = CLASS_RE.search(line)
        if match:
            return match.group(1)
    return None


def count_braces(line: str) -> tuple[int, int]:
    return line.count("{"), line.count("}")


def extract_methods_from_java(file_path: str, repo_root: Path) -> list[JavaMethod]:
    abs_path = repo_root / file_path
    if not abs_path.exists():
        print(f"[WARN] Java file not found: {abs_path}")
        return []

    content     = abs_path.read_text(encoding="utf-8")
    lines       = content.splitlines()
    package_name = find_package_name(content)
    class_name  = find_class_name(lines)

    methods: list[JavaMethod] = []
    i = 0

    while i < len(lines):
        # annotation block 수집
        annotation_lines: list[str] = []
        annotation_start = i

        while (
            annotation_start < len(lines)
            and lines[annotation_start].strip().startswith("@")
        ):
            annotation_lines.append(lines[annotation_start].strip())
            annotation_start += 1

        # annotation 다음 줄이 메서드 선언인지 확인
        if annotation_start < len(lines):
            match = METHOD_DECLARATION_RE.match(lines[annotation_start])
        else:
            match = None

        if match:
            method_name = match.group("name")
            start_idx   = annotation_start
            brace_balance = 0
            started     = False

            # { 는 같은 줄이 아닌 이후 줄에서도 찾도록 처리
            j = annotation_start
            while j < len(lines):
                line = lines[j]
                open_count  = line.count("{")
                close_count = line.count("}")
                brace_balance += open_count - close_count

                if open_count > 0:
                    started = True

                if started and brace_balance == 0:
                    end_idx = j

                    # annotation도 같이 method code에 포함
                    method_code_start = i if annotation_lines else start_idx
                    code = "\n".join(lines[method_code_start:end_idx + 1])

                    methods.append(
                        JavaMethod(
                            file_path=file_path,
                            package_name=package_name,
                            class_name=class_name,
                            method_name=method_name,
                            start_line=start_idx + 1,
                            end_line=end_idx + 1,
                            signature_line=lines[start_idx].strip(),
                            code=code,
                        )
                    )
                    i = end_idx
                    break

                j += 1

        i += 1

    return methods


def overlaps(
    method_start:   int,
    method_end:     int,
    changed_ranges: Iterable[tuple[int, int]],
) -> bool:
    for start, end in changed_ranges:
        if not (method_end < start or method_start > end):
            return True
    return False


def extract_changed_methods(changed_files_json: str) -> list[JavaMethod]:
    repo_root     = get_repo_root()
    changed_files = load_changed_files(changed_files_json, repo_root)
    matched_methods: list[JavaMethod] = []

    print(f"=== REPO ROOT ===\n{repo_root}")
    print("=== CHANGED FILES FROM JSON ===")
    for item in changed_files:
        print(f"- {item['file_path']} :: {item.get('changed_line_ranges', [])}")

    for item in changed_files:
        file_path      = item["file_path"]
        changed_ranges = [tuple(r) for r in item.get("changed_line_ranges", [])]

        methods = extract_methods_from_java(file_path, repo_root)

        print(f"\n=== METHODS IN FILE: {file_path} ===")
        for m in methods:
            print(f"[METHOD] {m.method_name} :: lines {m.start_line}-{m.end_line}")

        for method in methods:
            if overlaps(method.start_line, method.end_line, changed_ranges):
                print(f"[MATCH] {method.method_name} matched changed ranges {changed_ranges}")
                matched_methods.append(method)

    return matched_methods


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Extract changed Java methods from changed files JSON."
    )
    parser.add_argument(
        "--changed-files-json",
        default="outputs/changed_files.json",
        help="Path to changed files JSON.",
    )
    parser.add_argument(
        "--output",
        default="outputs/changed_methods.json",
        help="Path to save extracted changed methods.",
    )
    args = parser.parse_args()

    methods = extract_changed_methods(args.changed_files_json)

    repo_root   = get_repo_root()
    output_path = Path(args.output)
    if not output_path.is_absolute():
        output_path = repo_root / output_path
    output_path.parent.mkdir(parents=True, exist_ok=True)

    payload = {"methods": [asdict(m) for m in methods]}
    output_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"\nSaved changed methods to: {output_path}")


if __name__ == "__main__":
    main()