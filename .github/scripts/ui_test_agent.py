import asyncio, os, sys, json, traceback, re
import requests
import urllib.request
from playwright.async_api import async_playwright, expect

BASE_URL       = os.environ.get("TEST_BASE_URL", "https://axakorea-1996.github.io/Hackathon-FE/a.html")
TOKEN          = os.environ.get("AXA_GITHUB_TOKEN", "")
PR_NUMBER      = os.environ.get("PR_NUMBER", "")
REPO           = os.environ.get("REPO", "")
OPENROUTER_KEY = os.environ.get("OPENROUTER_API_KEY", "")

# ── 애니메이션 비활성화 (즉시 실행 방식) ──────────
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

# ── AI로 테스트 케이스 생성 (Gemma 4 31B) ────────
def generate_test_cases_with_ai(html: str) -> list:
    """HTML을 분석해서 청약 프로세스 테스트 케이스 자동 생성"""

    # style, script 태그 제거 후 핵심 구조만 추출
    html_clean = re.sub(r'<style[^>]*>[\s\S]*?</style>', '', html)
    html_clean = re.sub(r'<script[^>]*>[\s\S]*?</script>', '', html_clean)
    html_clean = re.sub(r'\s+', ' ', html_clean).strip()
    html_truncated = html_clean[:8000]

    prompt = f"""
당신은 UI 테스트 자동화 전문가입니다.
아래 AXA 자동차보험 청약 페이지의 HTML을 분석하여
Playwright Python 테스트 케이스를 JSON 형식으로 생성해주세요.

## HTML (핵심 구조)
{html_truncated}

## 분석 기준
1. 메인 페이지에서 청약 버튼 클릭으로 시작하는 전체 플로우
2. 각 Step별 핵심 UI 요소 (버튼, 체크박스, 선택 등)
3. 최종 완료 화면 검증
4. 페이지 이동 시 #progLabel로 Step 확인
5. .chip 선택 시 nth 인덱스 방식 사용 (filter 사용 금지)
6. .v-card 선택 시 first 프로퍼티 사용 (first() 메서드 사용 금지)

## action 종류
- goto: 페이지 이동
- click: 요소 클릭
- fill: 텍스트 입력
- select: select box 선택
- nth: 인덱스로 요소 선택 후 클릭 (value에 인덱스 숫자)
- first: 첫 번째 요소 클릭
- assert: 요소 텍스트 또는 가시성 확인
- clear_storage: localStorage 항목 삭제

## 출력 규칙
- 반드시 JSON만 출력 (다른 텍스트 없음, 마크다운 코드블록 없음)
- selector는 실제 HTML의 class/id 기반으로 작성

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
        }},
        {{
          "action": "click",
          "selector": "button.nav-btn-primary",
          "value": null,
          "description": "청약하기 버튼 클릭"
        }},
        {{
          "action": "assert",
          "selector": "#progLabel",
          "value": "STEP 1",
          "description": "Step1 진입 확인"
        }}
      ]
    }}
  ]
}}
"""

    body = {
        "model": "google/gemma-4-31b-it",  # ← Gemma 4 31B
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
            print(f"AI 테스트 케이스 생성 완료: {len(test_cases)}개")
            return test_cases
    except Exception as e:
        print(f"AI 테스트 케이스 생성 실패, 기본 케이스 사용: {e}")
        return get_default_test_cases()

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
            "name": "Step1 약관동의",
            "description": "약관 전체동의 후 Step2 이동 확인",
            "steps": [
                {"action": "goto",   "selector": None,                     "value": None,    "description": "페이지 이동"},
                {"action": "click",  "selector": "button.nav-btn-primary", "value": None,    "description": "청약하기 클릭"},
                {"action": "click",  "selector": ".terms-all",             "value": None,    "description": "전체동의 클릭"},
                {"action": "click",  "selector": ".bot-bar .btn-p",        "value": None,    "description": "다음 클릭"},
                {"action": "assert", "selector": "#progLabel",             "value": "STEP 2","description": "Step2 확인"}
            ]
        },
        {
            "name": "청약 전체 플로우",
            "description": "Step1~9 전체 청약 프로세스 확인",
            "steps": [
                {"action": "goto",    "selector": None,                           "value": None,        "description": "페이지 이동"},
                {"action": "click",   "selector": "button.nav-btn-primary",       "value": None,        "description": "청약하기 클릭"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 1",    "description": "Step1 확인"},
                {"action": "click",   "selector": ".terms-all",                   "value": None,        "description": "약관 전체동의"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 2",    "description": "Step2 확인"},
                {"action": "first",   "selector": ".v-card",                      "value": None,        "description": "차량 선택"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 3",    "description": "Step3 확인"},
                {"action": "select",  "selector": "select.inp",                   "value": "출퇴근용",  "description": "운행형태 선택"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 4",    "description": "Step4 확인"},
                {"action": "nth",     "selector": ".chip",                        "value": "1",         "description": "부부 선택 (index 1)"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 5",    "description": "Step5 확인"},
                {"action": "assert",  "selector": ".pr-val.big",                  "value": "1,019,640", "description": "보험료 확인"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 6",    "description": "Step6 확인"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 7",    "description": "Step7 확인"},
                {"action": "click",   "selector": ".terms-all",                   "value": None,        "description": "약관 동의"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "다음"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 8",    "description": "Step8 확인"},
                {"action": "fill",    "selector": 'input[placeholder="MM / YY"]', "value": "12/26",     "description": "유효기간 입력"},
                {"action": "fill",    "selector": 'input[placeholder="***"]',     "value": "123",       "description": "CVC 입력"},
                {"action": "click",   "selector": ".bot-bar .btn-p",              "value": None,        "description": "결제"},
                {"action": "assert",  "selector": "#progLabel",                   "value": "STEP 9",    "description": "Step9 확인"},
                {"action": "assert",  "selector": ".success-em",                  "value": None,        "description": "완료 이모지 확인"},
                {"action": "assert",  "selector": ".success-title",               "value": "감사드려요", "description": "완료 메시지 확인"}
            ]
        },
        {
            "name": "마이페이지 빈 상태",
            "description": "가입 전 마이페이지 빈 상태 확인",
            "steps": [
                {"action": "goto",          "selector": None,                    "value": None,               "description": "페이지 이동"},
                {"action": "clear_storage", "selector": None,                    "value": "axa_policies",      "description": "로컬스토리지 초기화"},
                {"action": "click",         "selector": "button.nav-btn-ghost",  "value": None,               "description": "마이페이지 클릭"},
                {"action": "assert",        "selector": ".empty-title",          "value": "가입된 보험이 없어요", "description": "빈 상태 확인"}
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

    body = {
        "model": "google/gemma-4-31b-it",  # ← Gemma 4 31B
        "max_tokens": 800,
        "messages": [{
            "role": "user",
            "content": f"""
아래 UI 테스트가 실패했습니다.
각 항목의 원인과 해결 방법을 한국어로 간결하게 분석해주세요.

## 실패한 테스트
{failed_list}

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
            # .chip nth(1) 방식 - filter() 사용 안 함
            idx = int(value)
            await page.wait_for_selector(selector, state='visible', timeout=10000)
            await page.locator(selector).nth(idx).click()

        elif action == "first":
            # .v-card first 프로퍼티 방식 - first() 메서드 사용 안 함
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
        browser = await p.chromium.launch(headless=True)
        for tc in test_cases:
            ctx  = await browser.new_context()
            page = await ctx.new_page()
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

    # 1. HTML 다운로드
    html = fetch_html(BASE_URL)

    # 2. AI로 테스트 케이스 생성 (실패 시 기본 케이스 사용)
    if html and OPENROUTER_KEY:
        test_cases = generate_test_cases_with_ai(html)
    else:
        print("AI 없이 기본 테스트 케이스 사용")
        test_cases = get_default_test_cases()

    print(f"\n총 {len(test_cases)}개 테스트 케이스 실행\n")

    # 3. 테스트 실행
    results = asyncio.run(run(test_cases))

    # 4. 실패 시 AI 원인 분석
    ai_analysis = ""
    if results["failed"]:
        print("\n🤖 AI 실패 원인 분석 중...")
        ai_analysis = analyze_failures_with_ai(results["failed"])
        if ai_analysis:
            print(f"분석 완료:\n{ai_analysis}")

    # 5. PR 코멘트 등록
    post_pr_comment(results, ai_analysis)

    sys.exit(0 if not results["failed"] else 1)