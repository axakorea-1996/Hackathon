# .github/scripts/testgen/post_junit_result.py
import json
import os
import xml.etree.ElementTree as ET
from pathlib import Path
import requests

TOKEN     = os.environ.get("AXA_GITHUB_TOKEN", "")
PR_NUMBER = os.environ.get("PR_NUMBER", "")
REPO      = os.environ.get("REPO", "")

def parse_junit_xml(xml_dir: str) -> dict:
    """Gradle 테스트 결과 XML 파싱"""
    results = {"passed": 0, "failed": 0, "errors": 0, "failures": []}
    xml_path = Path(xml_dir)

    if not xml_path.exists():
        return results

    for xml_file in xml_path.glob("*GeneratedTest*.xml"):
        try:
            tree = ET.parse(xml_file)
            root = tree.getroot()
            results["passed"] += int(root.get("tests", 0)) - \
                                  int(root.get("failures", 0)) - \
                                  int(root.get("errors", 0))
            results["failed"] += int(root.get("failures", 0))
            results["errors"] += int(root.get("errors", 0))

            for failure in root.iter("failure"):
                test_case = failure.getparent() if hasattr(failure, 'getparent') else None
                results["failures"].append({
                    "test": root.get("name", ""),
                    "message": failure.get("message", "")[:200]
                })
        except Exception as e:
            print(f"XML 파싱 오류: {e}")

    return results

def load_summary() -> dict:
    """생성된 테스트 요약 로드"""
    summary_path = Path("outputs/generated_direct_junit_summary.json")
    if not summary_path.exists():
        return {}
    return json.loads(summary_path.read_text())

def format_comment(results: dict, summary: dict) -> str:
    total   = results["passed"] + results["failed"] + results["errors"]
    passed  = results["passed"]
    failed  = results["failed"] + results["errors"]
    status  = "✅ 모든 JUnit 테스트 통과" if failed == 0 else "❌ 실패 항목 있음"

    # 생성된 테스트 목록
    generated = summary.get("generated_tests", [])
    test_list = "\n".join(
        f"- `{t['source_file'].split('/')[-1]}` → `{t['generated_test_file'].split('/')[-1]}`"
        for t in generated
    )

    # 실패 상세
    failed_section = ""
    if results["failures"]:
        failed_detail = "\n".join(
            f"- **{f['test']}**: `{f['message']}`"
            for f in results["failures"]
        )
        failed_section = f"\n### ❌ 실패한 테스트\n{failed_detail}"

    return f"""## 🤖 AI JUnit 테스트 자동 생성 결과

> **변경된 Java 메서드를 분석하여 JUnit 테스트를 자동 생성하고 실행했습니다**

| 항목 | 결과 |
|------|------|
| 생성된 테스트 클래스 | {len(generated)}개 |
| 전체 테스트 | {total}개 |
| ✅ 통과 | {passed}개 |
| ❌ 실패 | {failed}개 |

### 📋 최종 판정: {status}

### 🔧 생성된 테스트 파일
{test_list if test_list else "(없음)"}
{failed_section}

---
*Powered by OpenRouter AI Agent (gpt-4.1-mini)*"""

def post_pr_comment(comment: str):
    if not PR_NUMBER or not REPO or not TOKEN:
        print("PR_NUMBER 없음 - 코멘트 스킵")
        return

    url = f"https://api.github.com/repos/{REPO}/issues/{PR_NUMBER}/comments"
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json"
    }
    response = requests.post(url, json={"body": comment}, headers=headers)
    print(f"PR 코멘트 등록: {response.status_code}")

if __name__ == "__main__":
    results = parse_junit_xml(
        "subscription/build/test-results/test"
    )
    summary  = load_summary()
    comment  = format_comment(results, summary)
    print(comment)
    post_pr_comment(comment)