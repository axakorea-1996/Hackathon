import asyncio, os, sys, traceback
import requests
from playwright.async_api import async_playwright, expect

BASE_URL  = os.environ.get("TEST_BASE_URL", "https://axakorea-1996.github.io/Hackathon-FE/a.html")
TOKEN     = os.environ.get("AXA_GITHUB_TOKEN", "")
PR_NUMBER = os.environ.get("PR_NUMBER", "")
REPO      = os.environ.get("REPO", "")

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

async def test_main_page(page):
    await page.goto(BASE_URL)
    await expect(page.locator('.logo-box')).to_contain_text('AXA', timeout=10000)
    await expect(page.locator('.hero-title')).to_be_visible(timeout=10000)
    return "✅ 메인 페이지 정상 로드"

async def test_subscription_entry(page):
    await page.goto(BASE_URL)
    await page.click('button.nav-btn-primary')
    await expect(page.locator('#progLabel')).to_contain_text('STEP 1', timeout=10000)
    return "✅ 청약 페이지 진입"

async def test_step1_terms(page):
    await page.goto(BASE_URL)
    await page.click('button.nav-btn-primary')
    await page.click('.terms-all')
    await page.click('.bot-bar .btn-p')
    await expect(page.locator('#progLabel')).to_contain_text('STEP 2', timeout=10000)
    return "✅ Step1 약관동의 완료"

async def test_full_flow(page):
    await page.goto(BASE_URL)
    await page.click('button.nav-btn-primary')

    # Step1 약관동의
    await page.wait_for_selector('.terms-all', state='visible')
    await page.click('.terms-all')
    await page.click('.bot-bar .btn-p')

    # Step2 차량선택
    await expect(page.locator('#progLabel')).to_contain_text('STEP 2', timeout=10000)
    await page.wait_for_selector('.v-card', state='visible')
    await page.locator('.v-card').first.click()  # ← first() → first

    await page.click('.bot-bar .btn-p')

    # Step3 차량확인
    await expect(page.locator('#progLabel')).to_contain_text('STEP 3', timeout=10000)
    await page.wait_for_selector('select.inp', state='visible')
    await page.select_option('select.inp', '출퇴근용')
    await page.click('.bot-bar .btn-p')

    # Step4 운전자선택 - 부부는 index 1
    await expect(page.locator('#progLabel')).to_contain_text('STEP 4', timeout=10000)
    await page.wait_for_selector('.chip', state='visible')
    chips = page.locator('.chip')
    await chips.nth(1).click()
    await page.click('.bot-bar .btn-p')

    # Step5 보험료 확인
    await expect(page.locator('#progLabel')).to_contain_text('STEP 5', timeout=10000)
    await page.wait_for_selector('.pr-val.big', state='visible')
    await expect(page.locator('.pr-val.big')).to_contain_text('1,019,640', timeout=10000)
    await page.click('.bot-bar .btn-p')

    # Step6 특약
    await expect(page.locator('#progLabel')).to_contain_text('STEP 6', timeout=10000)
    await page.wait_for_selector('.tgl', state='visible')
    await page.click('.bot-bar .btn-p')

    # Step7 약관동의
    await expect(page.locator('#progLabel')).to_contain_text('STEP 7', timeout=10000)
    await page.wait_for_selector('.terms-all', state='visible')
    await page.click('.terms-all')
    await page.click('.bot-bar .btn-p')

    # Step8 결제정보
    await expect(page.locator('#progLabel')).to_contain_text('STEP 8', timeout=10000)
    await page.wait_for_selector('input[placeholder="MM / YY"]', state='visible')
    await page.fill('input[placeholder="MM / YY"]', '12/26')
    await page.fill('input[placeholder="***"]', '123')
    await page.click('.bot-bar .btn-p')

    # Step9 완료 확인
    await expect(page.locator('#progLabel')).to_contain_text('STEP 9', timeout=10000)
    await page.wait_for_selector('.success-em', state='visible')
    await expect(page.locator('.success-em')).to_be_visible(timeout=10000)
    await expect(page.locator('.success-title')).to_contain_text('감사드려요', timeout=10000)
    return "✅ 청약 전체 플로우 (Step 1~9) 완료"

async def test_mypage_empty(page):
    await page.goto(BASE_URL)
    await page.evaluate("localStorage.removeItem('axa_policies')")
    await page.click('button.nav-btn-ghost')
    await expect(page.locator('.empty-title')).to_contain_text('가입된 보험이 없어요', timeout=10000)
    return "✅ 마이페이지 빈 상태 확인"

async def run():
    results = {"passed": [], "failed": []}
    TEST_CASES = [
        ("메인 페이지 로드",           test_main_page),
        ("청약 페이지 진입",           test_subscription_entry),
        ("Step1 약관동의",             test_step1_terms),
        ("청약 전체 플로우 (1~9단계)", test_full_flow),
        ("마이페이지 빈 상태",         test_mypage_empty),
    ]

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        for name, fn in TEST_CASES:
            ctx  = await browser.new_context()
            page = await ctx.new_page()
            await page.add_init_script(DISABLE_ANIMATION_SCRIPT)
            try:
                msg = await fn(page)
                results["passed"].append({"name": name, "message": msg})
                print(f"PASS: {name}")
            except Exception as e:
                traceback.print_exc()
                results["failed"].append({"name": name, "error": str(e)})
                print(f"FAIL: {name} → {e}")
            finally:
                await ctx.close()
        await browser.close()

    return results

def post_pr_comment(results: dict):
    if not PR_NUMBER or not REPO or not TOKEN:
        print("PR_NUMBER, REPO, TOKEN 없음 - 코멘트 스킵")
        return

    passed = results["passed"]
    failed = results["failed"]
    total  = len(passed) + len(failed)
    status = "✅ 모든 UI 테스트 통과 — Merge 가능" if not failed else "❌ 실패 항목 있음 — 확인 필요"

    passed_list = "\n".join(f"- {t['message']}" for t in passed)

    failed_section = ""
    if failed:
        failed_list = "\n".join(
            f"- **{t['name']}**: `{t['error']}`" for t in failed
        )
        failed_section = f"\n### ❌ 실패한 테스트\n{failed_list}"

    comment = f"""## 🧪 UI 자동화 테스트 결과

> **테스트 대상:** AXA 청약 플로우 (메인 → 청약 9단계 → 마이페이지)

| 항목 | 결과 |
|------|------|
| 전체 | {total}개 |
| ✅ 통과 | {len(passed)}개 |
| ❌ 실패 | {len(failed)}개 |

### 📋 최종 판정: {status}

### ✅ 통과한 테스트
{passed_list}
{failed_section}

---
*Powered by OpenRouter AI Agent*"""

    url = f"https://api.github.com/repos/{REPO}/issues/{PR_NUMBER}/comments"
    headers = {
        "Authorization": f"Bearer {TOKEN}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json"
    }
    response = requests.post(url, json={"body": comment}, headers=headers)
    print(f"PR 코멘트 등록: {response.status_code}")

if __name__ == "__main__":
    results = asyncio.run(run())
    post_pr_comment(results)
    sys.exit(0 if not results["failed"] else 1)