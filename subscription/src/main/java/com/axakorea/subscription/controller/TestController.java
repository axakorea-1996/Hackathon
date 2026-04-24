package com.axakorea.subscription.controller;

import com.axakorea.subscription.client.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/test")
// ⚠️ 보안 추가: 운영 환경에서 비활성화
@Profile("!prod")
public class TestController {

    private final OpenRouterClient openRouterClient;

    @GetMapping("/ai")
    public String testAi() {
        // ⚠️ 보안 추가: 호출 로그
        log.warn("AI 테스트 엔드포인트 호출됨 - 운영 환경 노출 주의");
        return openRouterClient.analyze(
                "당신은 코드 리뷰어입니다.",
                "이 코드의 문제점은? public String name;"
        );
    }
}