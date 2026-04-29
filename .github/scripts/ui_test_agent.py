import sys
import asyncio
import os
import json
import traceback
import re
import requests
import urllib.request
from html.parser import HTMLParser
from playwright.async_api import async_playwright, expect

print(f"Python: {sys.version}")
print(f"모든 모듈 import 완료!")

BASE_URL       = os.environ.get("TEST_BASE_URL", "https://axakorea-1996.github.io/Hackathon-FE/a.html")
TOKEN          = os.environ.get("AXA_GITHUB_TOKEN", "")
PR_NUMBER      = os.environ.get("PR_NUMBER", "")
REPO           = os.environ.get("REPO", "")
OPENROUTER_KEY = os.environ.get("OPENROUTER_API_KEY", "")

DISABLE_ANIMATION_SCRIPT = """
const style = document.createElement('style');
style.textContent = `
    *, *::before, *::after {
        animation-duration: 0s !important;
        animation-delay: 0s !important;
        transition-duration: 0s !important;
        transition-delay: 0s !important;
    }
`;
document.head.appendChild(style);
"""

KNOWN_TEXTS = {
    ".empty-title":   "가입된 보험이 없어요",
    ".success-title": "감사드려요",
    ".pr-val.big":    "1,019,640",
    ".logo-box":      "AXA"
}

INJECTION_PATTERNS = [
    re.compile(r"(?i)(ignore|forget|disregard).{0,30}(above|previous|instruction|prompt)"),
    re.compile(r"(?i)\[SYSTEM\]|\[INST\]|\[\/INST\]"),
    re.compile(r"(?i)<\|im_start\|>|<\|im_end\|>"),
    re.compile(r"(?i)you are now|act as|pretend to be"),
    re.compile(r"(?i)reveal|expose|print|output.{0,20}(key|token|secret|password|api)"),
    re.compile(r"(?i)###\s*(system|instruction|prompt)"),
]

# ── 프롬프트 인젝션 방어 ──────────────────────────
def sanitize_for_prompt(text: str, max_length: int = 10000) -> str:
    if not text:
        return ""
    for pattern in INJECTION_PATTERNS:
        text = pattern.sub("", text)
    if len(text) > max_length:
        text = text[:max_length] + "...(truncated)"
    return text.strip()

# ── HTML 다운로드 ─────────────────────────────────
def fetch_html(url: str) -> str:
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=15) as res:
            html = res.read().decode("utf-8")
        print(f"HTML 다운로드 완료: {len(html)}자")
        return html
    except Exception as e:
        print(f"HTML 다운로드 실패: {e}")
        return ""

# ── UI 구조 추출 ──────────────────────────────────
def extract_ui_structure(html: str) -> str:
    class UIStructureExtractor(HTMLParser):
        def __init__(self):
            super().__init__()
            self.structure    = []
            self.capture_text = False
            self.current_tag  = None
            self.important_classes = {
                'btn','button','nav','logo','hero','terms','chip','card',
                'prog','step','success','empty','price','shell','bot-bar',
                'inp','tgl','v-card'
            }
        def handle_starttag(self, tag, attrs):
            attrs_dict = dict(attrs)
            class_val  = attrs_dict.get('class','')
            id_val     = attrs_dict.get('id','')
            classes    = class_val.split() if class_val else []
            is_important = (
                tag in {'button','input','select'} or id_val or
                any(kw in c for c in classes for kw in self.important_classes)
            )
            if is_important:
                s = ''
                if id_val:    s += f' id="{id_val}"'
                if class_val: s += f' class="{class_val}"'
                if attrs_dict.get('placeholder'): s += f' placeholder="{attrs_dict["placeholder"]}"'
                if attrs_dict.get('type'):        s += f' type="{attrs_dict["type"]}"'
                self.structure.append(f'<{tag}{s}>')
                self.capture_text = tag in {'button','span','div','h1','h2','p','label'}
                self.current_tag  = tag
        def handle_data(self, data):
            data = data.strip()
            if self.capture_text and data and len(data) < 50:
                self.structure.append(f'  TEXT: {data}')
        def handle_endtag(self, tag):
            if tag == self.current_tag:
                self.capture_text = False

    extractor = UIStructureExtractor()
    extractor.feed(html)
    return "\n".join(extractor.structure)

# ── AI 테스트 케이스 검증 및 보정 ─────────────────
def validate_test_cases(test_cases: list) -> list:
    invalid_values = ["visible","hidden","true","false","enabled","disabled"]
    for tc in test_cases:
        for step in tc.get("steps", []):
            if step.get("action") == "assert":
                value    = step.get("value", "")
                selector = step.get("selector", "")
                if value and str(value).lower() in invalid_values:
                    print(f"⚠️ 유효하지 않은 assert value: '{value}' → null")
                    step["value"] = None
                if selector in KNOWN_TEXTS:
                    correct = KNOWN_TEXTS[selector]
                    if correct and value != correct:
                        print(f"⚠️ 텍스트 보정: '{selector}' '{value}' → '{correct}'")
                        step["value"] = correct
    return test_cases

# ── AI로 테스트 케이스 생성 (Gemma 4 31B) ────────
def generate_test_cases_with_ai(html: str) -> list:
    ui_structure = extract_ui_structure(html)
    print(f"UI 구조 추출: {len(ui_structure)}자 "
          f"(원본 {len(html)}자 대비 "
          f"{100 - int(len(ui_structure)/len(html)*100)}% 감소)")

    safe_ui_structure = sanitize_for_prompt(ui_structure, max_length=8000)

    prompt = f"""
당신은 UI 테스트 자동화 전문가입니다.
아래 AXA 자동차보험 청약 페이지의 UI 구조를 분석하여
Playwright Python 테스트 케이스를 JSON 형식으로 생성해주세요.

## UI 구조 (인터랙티브 요소 및 주요 컨텐츠)
{safe_ui_structure}

## 반드시 지켜야 할 규칙
1. assert의 value는 반드시 실제 UI에 있는 텍스트를 넣으세요. 'visible' 절대 사용 금지
2. assert에서 텍스트 확인이 불필요하면 value를 null로 설정하세요
3. .chip은 반드시 Step4에서만 접근하세요 (Step1~3을 거친 후)
4. Step 순서: 약관동의(1) → 차량선택(2) → 차량확인(3) → 운전자(4) → 설계(5) → 특약(6) → 확인(7) → 결제(8) → 완료(9)
5. 각 Step 이동 후 반드시 #progLabel로 Step 진입 확인
6. .chip 선택은 nth 액션으로 인덱스 1 사용 (부부 선택)
7. .v-card 선택은 first 액션 사용
8. 반드시 JSON만 출력 (마크다운 코드블록 없음)
9. .empty-title 텍스트는 반드시 '가입된 보험이 없어요' 사용
10. .success-title 텍스트는 반드시 '감사드려요' 포함

## action 종류
- goto: 페이지 이동 (selector null, value null)
- click: 요소 클릭
- fill: 텍스트 입력
- select: select box 선택
- nth: 인덱스로 요소 선택 후 클릭 (value에 인덱스 숫자 문자열)
- first: 첫 번째 요소 클릭 (selector만 지정, value null)
- assert: 요소 확인 (value는 실제 텍스트 또는 null)
- clear_storage: localStorage 항목 삭제 (value에 키 이름)

## 테스트 케이스 구성 (반드시 4개)
1. 메인 페이지 로드 (.logo-box에서 AXA 텍스트 확인)
2. 청약 페이지 진입 (청약하기 버튼 클릭 후 STEP 1 확인)
3. 청약 전체 플로우 Step1~9 (순서대로 모든 Step 통과)
4. 마이페이지 호출 테스트 (localStorage 초기화 후 .empty-title에서 '가입된 보험이 없어요' 확인)

## 청약 전체 플로우 상세 순서
Step1: .terms-all 클릭 → .bot-bar .btn-p 클릭 → #progLabel STEP 2 확인
Step2: .v-card first 클릭 → .bot-bar .btn-p 클릭 → #progLabel STEP 3 확인
Step3: select.inp 출퇴근용 선택 → .bot-bar .btn-p 클릭 → #progLabel STEP 4 확인
Step4: .chip nth(1) 클릭 → .bot-bar .btn-p 클릭 → #progLabel STEP 5 확인
Step5: .pr-val.big에서 1,019,640 확인 → .bot-bar .btn-p 클릭 → #progLabel STEP 6 확인
Step6: .bot-bar .btn-p 클릭 → #progLabel STEP 7 확인
Step7: .terms-all 클릭 → .bot-bar .btn-p 클릭 → #progLabel STEP 8 확인
Step8: input[placeholder="MM / YY"] 12/26 입력 → input[placeholder="***"] 123 입력 → .bot-bar .btn-p 클릭 → #progLabel STEP 9 확인
Step9: .success-em 가시성 확인(value null) → .success-title에서 감사드려요 확인

## 출력 형식
{{
  "test_cases": [
    {{
      "name": "테스트 이름",
      "description": "테스트 설명",
      "steps": [
        {{
          "action": "goto",
          "selector": null,
          "value": null,
          "description": "페이지 이동"
        }}
      ]
    }}
  ]
}}
"""

    body = {
        "model": "google/gemma-4-31b-it",
        "max_tokens": 3000,
        "messages": [{"role": "user", "content": prompt}]
    }

    req = urllib.request.Request(
        "https://openrouter.ai/api/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {OPENROUTER_KEY}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://axakorea.com",
            "X-Title": "AXA UI Test Agent"
        },
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            result = json.loads(res.read().decode())
            content = result["choices"][0]["message"]["content"]
            clean = content.replace("```json", "").replace("```", "").strip()
            test_cases = json.loads(clean)["test_cases"]
            test_cases = validate_test_cases(test_cases)
            with open("generated_test_cases.json", "w", encoding="utf-8") as f:
                json.dump(test_cases, f, ensure_ascii=False, indent=2)
            print(f"AI 테스트 케이스 생성 완료: {len(test_cases)}개")
            return test_cases
    except Exception as e:
        print(f"AI 테스트 케이스 생성 실패, 기본 케이스 사용: {e}")
        default = get_default_test_cases()
        with open("generated_test_cases.json", "w", encoding="utf-8") as f:
            json.dump({"fallback": True, "test_cases": default},
                      f, ensure_ascii=False, indent=2)
        return default

# ── 기본 테스트 케이스 (AI 실패 시 fallback) ──────
def get_default_test_cases() -> list:
    return [
        {
            "name": "메인 페이지 로드",
            "description": "메인 페이지 정상 로드 확인",
            "steps": [
                {"action": "goto",   "selector": None,          "value": None,  "description": "페이지 이동"},
                {"action": "assert", "selector": ".logo-box",   "value": "AXA", "description": "로고 확인"},
                {"action": "assert", "selector": ".hero-title", "value": None,  "description": "히어로 타이틀 확인"}
            ]
        },
        {
            "name": "청약 페이지 진입",
            "description": "청약하기 버튼 클릭 후 Step1 진입 확인",
            "steps": [
                {"action": "goto",   "selector": None,                     "value": None,    "description": "페이지 이동"},
                {"action": "click",  "selector": "button.nav-btn-primary", "value": None,    "description": "청약하기 클릭"},
                {"action": "assert", "selector": "#progLabel",             "value": "STEP 1","description": "Step1 확인"}
            ]
        },
        {
            "name": "청약 전체 플로우",
            "description": "Step1~9 전체 청약 프로세스 확인",
            "steps": [
                {"action": "goto",   "selector": None,                           "value": None,        "description": "페이지 이동"},
                {"action": "click",  "selector": "button.nav-btn-primary",       "value": None,        "description": "청약하기 클릭"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 1",    "description": "Step1 확인"},
                {"action": "click",  "selector": ".terms-all",                   "value": None,        "description": "약관 전체동의"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 2",    "description": "Step2 확인"},
                {"action": "first",  "selector": ".v-card",                      "value": None,        "description": "차량 선택"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 3",    "description": "Step3 확인"},
                {"action": "select", "selector": "select.inp",                   "value": "출퇴근용",  "description": "운행형태 선택"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 4",    "description": "Step4 확인"},
                {"action": "nth",    "selector": ".chip",                        "value": "1",         "description": "부부 선택"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 5",    "description": "Step5 확인"},
                {"action": "assert", "selector": ".pr-val.big",                  "value": "1,019,640", "description": "보험료 확인"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 6",    "description": "Step6 확인"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 7",    "description": "Step7 확인"},
                {"action": "click",  "selector": ".terms-all",                   "value": None,        "description": "약관 동의"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 8",    "description": "Step8 확인"},
                {"action": "fill",   "selector": 'input[placeholder="MM / YY"]', "value": "12/26",     "description": "유효기간 입력"},
                {"action": "fill",   "selector": 'input[placeholder="***"]',     "value": "123",       "description": "CVC 입력"},
                {"action": "click",  "selector": ".bot-bar .btn-p",              "value": None,        "description": "결제"},
                {"action": "assert", "selector": "#progLabel",                   "value": "STEP 9",    "description": "Step9 확인"},
                {"action": "assert", "selector": ".success-em",                  "value": None,        "description": "완료 이모지 확인"},
                {"action": "assert", "selector": ".success-title",               "value": "감사드려요", "description": "완료 메시지 확인"}
            ]
        },
        {
            "name": "마이페이지 호출 테스트",
            "description": "가입 전 마이페이지 빈 상태 확인",
            "steps": [
                {"action": "goto",          "selector": None,                   "value": None,                "description": "페이지 이동"},
                {"action": "clear_storage", "selector": None,                   "value": "axa_policies",      "description": "로컬스토리지 초기화"},
                {"action": "click",         "selector": "button.nav-btn-ghost", "value": None,                "description": "마이페이지 클릭"},
                {"action": "assert",        "selector": ".empty-title",         "value": "가입된 보험이 없어요", "description": "빈 상태 확인"}
            ]
        }
    ]

# ── AI 실패 분석 (Gemma 4 31B) ────────────────────
def analyze_failures_with_ai(failed_tests: list) -> str:
    if not failed_tests or not OPENROUTER_KEY:
        return ""

    failed_list = "\n".join(
        f"- {t['name']}: {t['error']}" for t in failed_tests
    )
    safe_failed_list = sanitize_for_prompt(failed_list, max_length=3000)

    body = {
        "model": "google/gemma-4-31b-it",
        "max_tokens": 800,
        "messages": [{
            "role": "user",
            "content": f"""
아래 UI 테스트가 실패했습니다.
각 항목의 원인과 해결 방법을 한국어로 간결하게 분석해주세요.

## 실패한 테스트
{safe_failed_list}

## 출력 형식
### 원인 분석
- 각 실패 항목별 원인

### 해결 방법
- 구체적인 수정 방향
"""
        }]
    }

    req = urllib.request.Request(
        "https://openrouter.ai/api/v1/chat/completions",
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {OPENROUTER_KEY}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://axakorea.com",
            "X-Title": "AXA UI Test Agent"
        },
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            result = json.loads(res.read().decode())
            return result["choices"][0]["message"]["content"]
    except Exception as e:
        print(f"AI 실패 분석 오류: {e}")
        return ""

# ── Playwright step 실행 ──────────────────────────
async def execute_steps(page, steps: list):
    for step in steps:
        action   = step.get("action")
        selector = step.get("selector")
        value    = step.get("value")
        desc     = step.get("description", "")

        if action == "goto":
            await page.goto(BASE_URL)
        elif action == "click":
            await page.wait_for_selector(selector, state='visible', timeout=10000)
            await page.click(selector)
        elif action == "fill":
            await page.wait_for_selector(selector, state='visible', timeout=10000)
            await page.fill(selector, value)
        elif action == "select":
            await page.wait_for_selector(selector, state='visible', timeout=10000)
            await page.select_option(selector, value)
        elif action == "nth":
            idx = int(value)
            await page.wait_for_selector(selector, state='visible', timeout=10000)
            await page.locator(selector).nth(idx).click()
        elif action == "first":
            await page.wait_for_selector(selector, state='visible', timeout=10000)
            await page.locator(selector).first.click()
        elif action == "assert":
            await page.wait_for_selector(selector, state='visible', timeout=10000)
            if value:
                await expect(page.locator(selector)).to_contain_text(value, timeout=10000)
            else:
                await expect(page.locator(selector)).to_be_visible(timeout=10000)
        elif action == "clear_storage":
            await page.evaluate(f"localStorage.removeItem('{value}')")

        print(f"  ✓ {desc}")

# ── 테스트 실행 ───────────────────────────────────
async def run(test_cases: list):
    results = {"passed": [], "failed": []}

    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=True,
            args=[
                '--no-sandbox',
                '--disable-dev-shm-usage',
                '--disable-gpu',
                '--disable-extensions',
            ]
        )

        for tc in test_cases:
            ctx  = await browser.new_context()
            page = await ctx.new_page()

            await page.route(
                "**/*.{png,jpg,jpeg,gif,svg,woff,woff2,ttf,otf}",
                lambda route: route.abort()
            )

            await page.add_init_script(DISABLE_ANIMATION_SCRIPT)
            print(f"\n▶ {tc['name']}")
            try:
                await execute_steps(page, tc["steps"])
                results["passed"].append({
                    "name": tc["name"],
                    "message": f"✅ {tc['name']}"
                })
                print(f"PASS: {tc['name']}")
            except Exception as e:
                traceback.print_exc()
                results["failed"].append({
                    "name": tc["name"],
                    "error": str(e)
                })
                print(f"FAIL: {tc['name']} → {e}")
            finally:
                await ctx.close()
        await browser.close()

    return results

# ── PR 코멘트 등록 ────────────────────────────────
def post_pr_comment(results: dict, ai_analysis: str):
    if not PR_NUMBER or not REPO or not TOKEN:
        print("PR_NUMBER 없음 - 코멘트 스킵")
        return

    passed = results["passed"]
    failed = results["failed"]
    total  = len(passed) + len(failed)
    status = "✅ 모든 UI 테스트 통과 — Merge 가능" if not failed \
             else "❌ 실패 항목 있음 — 확인 필요"

    passed_list = "\n".join(f"- {t['message']}" for t in passed)

    failed_section = ""
    if failed:
        failed_list = "\n".join(
            f"- **{t['name']}**: `{t['error']}`" for t in failed
        )
        failed_section = f"\n### ❌ 실패한 테스트\n{failed_list}"

    ai_section = ""
    if ai_analysis:
        ai_section = f"\n---\n\n## 🤖 AI 실패 원인 분석\n\n{ai_analysis}"

    comment = f"""## 🧪 UI 자동화 테스트 결과

> **테스트 대상:** AXA 청약 플로우 (Gemma 4 31B Agent 자동 생성)

| 항목 | 결과 |
|------|------|
| 전체 | {total}개 |
| ✅ 통과 | {len(passed)}개 |
| ❌ 실패 | {len(failed)}개 |

### 📋 최종 판정: {status}

### ✅ 통과한 테스트
{passed_list}
{failed_section}
{ai_section}

---
*Powered by Gemma 4 31B AI Agent*"""

    url = f"https://api.github.com/repos/{REPO}/issues/{PR_NUMBER}/comments"
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json"
    }
    response = requests.post(url, json={"body": comment}, headers=headers)
    print(f"PR 코멘트 등록: {response.status_code}")

# ── 메인 ─────────────────────────────────────────
if __name__ == "__main__":
    print("🤖 Gemma 4 31B Agent로 테스트 케이스 생성 중...")

    html = fetch_html(BASE_URL)

    if html and OPENROUTER_KEY:
        test_cases = generate_test_cases_with_ai(html)
    else:
        print("AI 없이 기본 테스트 케이스 사용")
        test_cases = get_default_test_cases()
        with open("generated_test_cases.json", "w", encoding="utf-8") as f:
            json.dump({"fallback": True, "test_cases": test_cases},
                      f, ensure_ascii=False, indent=2)

    print(f"\n총 {len(test_cases)}개 테스트 케이스 실행\n")

    results = asyncio.run(run(test_cases))

    ai_analysis = ""
    if results["failed"]:
        print("\n🤖 AI 실패 원인 분석 중...")
        ai_analysis = analyze_failures_with_ai(results["failed"])
        if ai_analysis:
            print(f"분석 완료:\n{ai_analysis}")

    post_pr_comment(results, ai_analysis)

    sys.exit(0 if not results["failed"] else 1)