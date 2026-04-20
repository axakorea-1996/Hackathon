package com.axakorea.subscription.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenRouterClient {

    @Value("${openrouter.api.key}")
    private String apiKey;

    @Value("${openrouter.api.url}")
    private String apiUrl;

    @Value("${openrouter.api.model}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();

    public String analyze(String systemPrompt, String userPrompt) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("HTTP-Referer", "https://axakorea.com");  // OpenRouter 필수값
        headers.set("X-Title", "AXA Impact Analysis Agent");

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user",   "content", userPrompt)
                ),
                "max_tokens", 3000,
                "temperature", 0.1   // 일관된 분석 결과를 위해 낮게 설정
        );

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    apiUrl,
                    new HttpEntity<>(body, headers),
                    Map.class
            );

            List<Map> choices = (List<Map>) response.getBody().get("choices");
            Map message = (Map) choices.get(0).get("message");
            String result = (String) message.get("content");

            log.info("OpenRouter 분석 완료 ({}자)", result.length());
            return result;

        } catch (Exception e) {
            log.error("OpenRouter API 호출 실패", e);
            return "❌ AI 분석 중 오류가 발생했습니다: " + e.getMessage();
        }
    }
}
