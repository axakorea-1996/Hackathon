'''
추출한 Java 메서드를 LLM에 보내고, 테스트 시나리오 JSON을 받아오는 역할
'''
from __future__ import annotations

import json
import os
from typing import Any

from openai import OpenAI


# def build_prompt(class_name: str | None, method_name: str, code: str) -> str:
#     safe_class_name = class_name or "UnknownClass"

#     return f"""
# You are a software test generation assistant.

# Your task is to generate test scenarios for a modified Java method.

# Rules:
# 1. Return ONLY valid JSON.
# 2. Do not include markdown fences.
# 3. Focus only on scenarios that can be inferred from the provided code.
# 4. Include normal cases, failure cases, and edge cases when visible in the code.
# 5. Keep output concise but useful for later conversion into automated tests.

# Return JSON in this exact shape:
# {{
#   "class_name": "{safe_class_name}",
#   "method_name": "{method_name}",
#   "test_cases": [
#     {{
#       "name": "short_snake_case_name",
#       "description": "what this test verifies",
#       "inputs": {{}},
#       "expected": {{}},
#       "priority": "high|medium|low"
#     }}
#   ]
# }}

# Java class: {safe_class_name}
# Method name: {method_name}

# Code:
# {code}
# """.strip()


def create_client() -> OpenAI:
    api_key = os.getenv("OPENAI_API_KEY")
    base_url = os.getenv("OPENAI_BASE_URL")
    if not api_key:
        raise RuntimeError("OPENAI_API_KEY is not set.")

    if base_url:
        return OpenAI(api_key=api_key, base_url=base_url)
    return OpenAI(api_key=api_key)


# def generate_test_scenarios(class_name: str | None, method_name: str, code: str) -> dict[str, Any]:
#     client = create_client()
#     model = os.getenv("OPENAI_MODEL", "openai/gpt-4.1-mini")
#     prompt = build_prompt(class_name=class_name, method_name=method_name, code=code)

#     response = client.responses.create(
#         model=model,
#         input=prompt,
#         temperature=0,
#     )

#     text = response.output_text.strip()
#     try:
#         return json.loads(text)
#     except json.JSONDecodeError as exc:
#         raise RuntimeError(
#             "LLM response was not valid JSON.\n"
#             f"Raw response:\n{text}"
#         ) from exc