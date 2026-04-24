async def run(test_cases: list):
    results = {"passed": [], "failed": []}

    async with async_playwright() as p:
        # ✅ 브라우저 실행 옵션 최적화
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

            # ✅ 불필요한 리소스 차단 (이미지, 폰트 등)
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