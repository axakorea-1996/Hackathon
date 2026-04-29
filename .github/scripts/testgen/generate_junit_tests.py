from __future__ import annotations

import argparse
import json
import re
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from llm_client import create_client


IMPORT_RE = re.compile(r"^\s*import\s+([\w\.]+);", re.MULTILINE)
FINAL_FIELD_RE = re.compile(
    r"^\s*private\s+final\s+([A-Za-z_][\w<>\., ?]+)\s+([A-Za-z_]\w*)\s*;",
    re.MULTILINE,
)
ANNOTATION_RE = re.compile(r"^\s*@(\w+)", re.MULTILINE)

INJECTION_PATTERNS = [
    re.compile(r"(?i)(ignore|forget|disregard).{0,30}(above|previous|instruction|prompt)"),
    re.compile(r"(?i)\[SYSTEM\]|\[INST\]|\[\/INST\]"),
    re.compile(r"(?i)<\|im_start\|>|<\|im_end\|>"),
    re.compile(r"(?i)you are now|act as|pretend to be"),
    re.compile(r"(?i)reveal|expose|print|output.{0,20}(key|token|secret|password|api)"),
    re.compile(r"(?i)###\s*(system|instruction|prompt)"),
]


@dataclass
class ChangedMethod:
    file_path: str
    package_name: str | None
    class_name: str | None
    method_name: str
    start_line: int
    end_line: int
    signature_line: str
    code: str


def sanitize_for_prompt(text: str, max_length: int = 25000) -> str:
    if not text:
        return ""

    for pattern in INJECTION_PATTERNS:
        text = pattern.sub("", text)

    if len(text) > max_length:
        text = text[:max_length] + "\n...(truncated for security)"

    return text.strip()


def safe_slug(text: str) -> str:
    text = re.sub(r"[^A-Za-z0-9_\\-\\.]+", "_", text)
    return text.strip("_") or "unknown"


def is_private_method(method: ChangedMethod) -> bool:
    signature = method.signature_line.strip()
    return signature.startswith("private ")


def find_public_callers_for_private_method(
    private_method: ChangedMethod,
    class_context: dict[str, Any],
) -> list[str]:
    """
    PoC용 간단 탐색:
    private 메서드명을 클래스 전체 코드에서 찾고,
    그 주변에 있는 public 메서드 선언을 caller 후보로 잡는다.
    """
    full_code = class_context.get("full_class_code", "")
    lines = full_code.splitlines()
    target_call = private_method.method_name + "("

    public_method_re = re.compile(
        r"^\s*public\s+[\w\<\>\[\],\s?\.]+\s+([A-Za-z_]\w*)\s*\([^;{}]*\)\s*(?:throws\s+[^{]+)?\{"
    )

    callers: list[str] = []
    current_public_method: str | None = None
    brace_balance = 0
    in_public_method = False

    for line in lines:
        match = public_method_re.match(line)

        if match:
            current_public_method = match.group(1)
            in_public_method = True
            brace_balance = line.count("{") - line.count("}")
        elif in_public_method:
            brace_balance += line.count("{") - line.count("}")

        if in_public_method and current_public_method and target_call in line:
            callers.append(current_public_method)

        if in_public_method and brace_balance <= 0:
            current_public_method = None
            in_public_method = False
            brace_balance = 0

    return sorted(set(callers))


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


def load_changed_methods(json_path: str, repo_root: Path) -> list[ChangedMethod]:
    path = Path(json_path)
    if not path.is_absolute():
        path = repo_root / path

    data = json.loads(path.read_text(encoding="utf-8"))
    methods = data.get("methods", [])
    return [ChangedMethod(**m) for m in methods]


def sanitize_java_code_block(text: str) -> str:
    text = text.strip()
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\n?", "", text)
        text = re.sub(r"\n?```$", "", text)
    return text.strip()


def collect_imported_types(java_code: str) -> list[str]:
    return IMPORT_RE.findall(java_code)


def find_file_by_fqcn(fqcn: str, repo_root: Path) -> Path | None:
    parts = fqcn.split(".")
    if not parts:
        return None

    class_name = parts[-1] + ".java"
    candidates = list(repo_root.rglob(class_name))
    if not candidates:
        return None

    package_suffix = "/".join(parts[:-1])
    for candidate in candidates:
        normalized = str(candidate).replace("\\", "/")
        if package_suffix in normalized:
            return candidate

    return candidates[0]


def read_file_safe(path: Path, max_chars: int = 10000) -> str:
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")[:max_chars]


def collect_primary_class_context(method: ChangedMethod, repo_root: Path) -> dict[str, Any]:
    abs_path = repo_root / method.file_path
    full_class_code = read_file_safe(abs_path, max_chars=25000)
    imports = collect_imported_types(full_class_code)
    dependencies = FINAL_FIELD_RE.findall(full_class_code)
    annotations = ANNOTATION_RE.findall(full_class_code)

    return {
        "absolute_path": str(abs_path),
        "full_class_code": full_class_code,
        "imports": imports,
        "dependencies": [
            {"type": dep_type.strip(), "name": dep_name.strip()}
            for dep_type, dep_name in dependencies
        ],
        "annotations": annotations,
    }


def collect_related_files(
    method: ChangedMethod,
    repo_root: Path,
    max_files: int = 8,
) -> list[dict[str, str]]:
    abs_path = repo_root / method.file_path
    full_class_code = read_file_safe(abs_path, max_chars=25000)
    imports = collect_imported_types(full_class_code)

    related: list[dict[str, str]] = []
    seen: set[str] = set()

    for fqcn in imports:
        if fqcn.startswith(("java.", "jakarta.", "org.springframework.", "lombok.")):
            continue

        path = find_file_by_fqcn(fqcn, repo_root)
        if not path:
            continue

        norm = str(path.resolve())
        if norm in seen:
            continue

        seen.add(norm)
        related.append(
            {
                "file_path": str(path.relative_to(repo_root)),
                "code": read_file_safe(path, max_chars=15000),
            }
        )

        if len(related) >= max_files:
            break

    return related


def build_direct_junit_prompt(
    method: ChangedMethod,
    class_context: dict[str, Any],
    related_files: list[dict[str, str]],
    private_callers: list[str] | None = None,
) -> str:
    private_callers = private_callers or []
    safe_class_name = method.class_name or "UnknownClass"

    safe_method_code = sanitize_for_prompt(method.code, 10000)
    safe_class_code = sanitize_for_prompt(class_context["full_class_code"], 25000)
    safe_signature_line = sanitize_for_prompt(method.signature_line, 500)
    safe_related_text = sanitize_for_prompt(
        "\n\n".join(
            f"[Related File] {item['file_path']}\n{item['code']}"
            for item in related_files
        ),
        30000,
    )

    return f"""
You are an expert Java backend test generation assistant.

Generate ONE compilable Java JUnit 5 test class for the changed Java method below.

Primary goal:
- Produce a test class that can compile and run in the target project.
- Focus on the changed method only.
- Use only classes, methods, and fields that actually exist in the provided code context.

Output format requirements:
- Return ONLY raw Java source code.
- Do NOT return markdown fences.
- Do NOT return explanations.
- The class name MUST be "{safe_class_name}GeneratedTest".

Required test style rules:
1. Use JUnit 5.
2. Use AssertJ for normal result assertions.
3. Use assertThrows for exception scenarios.
4. If constructor-injected dependencies exist, prefer Mockito-based service tests:
   - @ExtendWith(MockitoExtension.class)
   - @Mock
   - @InjectMocks
   - when(...).thenReturn(...)
5. If the changed class is clearly a controller, prefer MockMvc tests.
6. Import every referenced class explicitly if needed.
7. Do not invent repository methods, DTO fields, builders, or constructors that are not visible in the provided code.
8. If object construction is needed:
   - prefer builder() only if clearly present in the provided class
   - otherwise use constructor/setter style only if clearly supported by the provided class
9. Create 2 to 4 meaningful test methods maximum.
10. Prefer deterministic assertions over weak assertions.
11. Keep the test tightly scoped to the changed method and nearby logic.
12. Never directly call private methods from the generated test class.
13. If the changed method is private:
   - Do NOT generate tests that call the private method directly.
   - Use one of the provided public_caller_candidates_for_private_method as the test target.
   - The generated test must call the public caller, not the private method.
   - Validate the externally observable behavior caused by the private method change.
   - If public_caller_candidates_for_private_method is empty, do not generate test methods.
14. Only generate test methods that call methods accessible from the generated test class.

Very important traceability requirement:
At the top of the generated Java class, include a comment block exactly like this shape:

/*
 * AUTO-GENERATED TEST
 * SOURCE_FILE: {method.file_path}
 * SOURCE_METHOD: {method.method_name}
 * CHANGED_LINES: {method.start_line}-{method.end_line}
 */

If the changed method appears to be a Spring service with repository dependencies:
- generate Mockito-based service unit tests
- do NOT instantiate the service with a zero-arg constructor unless that constructor actually exists

If the changed method throws or references a project exception class:
- ensure the exception type is imported correctly

If you are unsure about a complex positive-path fixture:
- prefer one strong negative/exception case and one minimal positive-path case
- do not hallucinate deep fixture state

[Changed Method Metadata]
class_name: {safe_class_name}
method_name: {method.method_name}
file_path: {method.file_path}
signature_line: {safe_signature_line}
is_private_method: {str(is_private_method(method)).lower()}
public_caller_candidates_for_private_method: {json.dumps(private_callers, ensure_ascii=False)}
line_range: {method.start_line}-{method.end_line}

[Changed Method Code]
{safe_method_code}

[Changed Class Full Code]
{safe_class_code}

[Detected Dependencies]
{json.dumps(class_context["dependencies"], ensure_ascii=False, indent=2)}

[Detected Class Annotations]
{json.dumps(class_context["annotations"], ensure_ascii=False, indent=2)}

[Related Files]
{safe_related_text if safe_related_text else "(none)"}

Return ONLY the Java source code of the generated JUnit test class.
""".strip()


def save_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def build_prompt_log_paths(
    repo_root: Path,
    prompt_log_dir: str,
    method: ChangedMethod,
) -> dict[str, Path]:
    class_name = safe_slug(method.class_name or "UnknownClass")
    method_name = safe_slug(method.method_name)
    line_range = f"{method.start_line}_{method.end_line}"
    base_name = f"{class_name}__{method_name}__{line_range}"
    base_dir = repo_root / prompt_log_dir

    return {
        "prompt_txt": base_dir / f"{base_name}__prompt.txt",
        "response_txt": base_dir / f"{base_name}__response.txt",
        "meta_json": base_dir / f"{base_name}__meta.json",
    }


def generate_direct_junit_code(prompt: str) -> tuple[str, str, str]:
    client = create_client()
    selected_model = os.getenv("OPENAI_MODEL", "openai/gpt-4.1-mini").strip()

    response = client.chat.completions.create(
        model=selected_model,
        messages=[{"role": "user", "content": prompt}],
        temperature=0,
    )

    raw_text = response.choices[0].message.content or ""
    cleaned_java = sanitize_java_code_block(raw_text)
    return cleaned_java, raw_text, selected_model


def package_to_path(package_name: str | None) -> Path:
    if not package_name:
        return Path()
    return Path(*package_name.split("."))


def save_generated_test(
    java_code: str,
    package_name: str | None,
    class_name: str | None,
    output_root: Path,
) -> Path:
    safe_class_name = class_name or "UnknownClass"
    output_dir = output_root / package_to_path(package_name)
    output_dir.mkdir(parents=True, exist_ok=True)

    output_file = output_dir / f"{safe_class_name}GeneratedTest.java"
    output_file.write_text(java_code, encoding="utf-8")
    return output_file


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate JUnit test code directly from changed Java methods using LLM."
    )
    parser.add_argument(
        "--changed-methods-json",
        default="outputs/changed_methods.json",
        help="Path to changed_methods.json",
    )
    parser.add_argument(
        "--output-dir",
        default="subscription/src/test/java",
        help="Directory to save generated JUnit files",
    )
    parser.add_argument(
        "--max-related-files",
        type=int,
        default=8,
        help="Maximum number of related files to include in prompt",
    )
    parser.add_argument(
        "--prompt-log-dir",
        default="outputs/prompts",
        help="Directory to save prompt / raw LLM response logs",
    )
    args = parser.parse_args()

    repo_root = get_repo_root()
    methods = load_changed_methods(args.changed_methods_json, repo_root)
    output_root = (
        repo_root / args.output_dir
        if not Path(args.output_dir).is_absolute()
        else Path(args.output_dir)
    )

    if not methods:
        print("No changed methods found.")
        return

    summary: list[dict[str, str]] = []

    for method in methods:
        class_context = collect_primary_class_context(method, repo_root)

        private_callers: list[str] = []
        if is_private_method(method):
            private_callers = find_public_callers_for_private_method(method, class_context)

            if not private_callers:
                print(
                    f"[SKIP] Private method detected, but no public caller found. "
                    f"Skipping direct test generation: {method.class_name}.{method.method_name}"
                )

                summary.append(
                    {
                        "method_name": method.method_name,
                        "source_file": method.file_path,
                        "generated_test_file": "",
                        "prompt_file": "",
                        "response_file": "",
                        "meta_file": "",
                        "skip_reason": "private_method_no_public_caller",
                    }
                )
                continue

            print(
                f"[INFO] Private method detected: {method.class_name}.{method.method_name}. "
                f"Public caller candidates: {private_callers}"
            )

        related_files = collect_related_files(
            method,
            repo_root,
            max_files=args.max_related_files,
        )

        prompt = build_direct_junit_prompt(
            method,
            class_context,
            related_files,
            private_callers=private_callers,
        )
        java_code, raw_response, selected_model = generate_direct_junit_code(prompt)

        log_paths = build_prompt_log_paths(
            repo_root=repo_root,
            prompt_log_dir=args.prompt_log_dir,
            method=method,
        )

        save_text(log_paths["prompt_txt"], prompt)
        save_text(log_paths["response_txt"], raw_response)
        save_text(
            log_paths["meta_json"],
            json.dumps(
                {
                    "source_file": method.file_path,
                    "source_method": method.method_name,
                    "class_name": method.class_name,
                    "start_line": method.start_line,
                    "end_line": method.end_line,
                    "model": selected_model,
                    "package_name": method.package_name,
                    "is_private_method": is_private_method(method),
                },
                indent=2,
                ensure_ascii=False,
            ),
        )

        output_file = save_generated_test(
            java_code=java_code,
            package_name=method.package_name,
            class_name=method.class_name,
            output_root=output_root,
        )

        summary.append(
            {
                "method_name": method.method_name,
                "source_file": method.file_path,
                "generated_test_file": str(output_file.relative_to(repo_root)),
                "prompt_file": str(log_paths["prompt_txt"].relative_to(repo_root)),
                "response_file": str(log_paths["response_txt"].relative_to(repo_root)),
                "meta_file": str(log_paths["meta_json"].relative_to(repo_root)),
                "skip_reason": "",
            }
        )

        print(f"Generated direct JUnit test: {output_file}")

    summary_path = repo_root / "outputs/generated_direct_junit_summary.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(
        json.dumps({"generated_tests": summary}, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"Saved summary to: {summary_path}")


if __name__ == "__main__":
    main()