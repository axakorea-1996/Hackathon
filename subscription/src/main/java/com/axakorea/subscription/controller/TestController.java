package com.axakorea.subscription.controller;

import com.axakorea.subscription.client.OpenRouterClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/test")
public class TestController {

    private final OpenRouterClient openRouterClient;

    @GetMapping("/ai")
    public String testAi() {
        return openRouterClient.analyze(
                "당신은 코드 리뷰어입니다.",
                "이 코드의 문제점은? public String name;"
        );
    }
}