package com.axakorea.subscription.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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

    // ✅ RestTemplateBuilder 대신 SimpleClientHttpRequestFactory로 타임아웃 설정
    private final RestTemplate restTemplate;

    public OpenRouterClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);  // 10초
        factory.setReadTimeout(60000);     // 60초
        this.restTemplate = new RestTemplate(factory);
    }

    public String analyze(String systemPrompt, String userPrompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey.trim());
        headers.set("HTTP-Referer", "https://axakorea.com");
        headers.set("X-Title", "AXA Impact Analysis Agent");

        // 프롬프트 길이 제한
        String truncatedSystem = truncate(systemPrompt, 10000);
        String truncatedUser   = truncate(userPrompt, 50000);

        Map<String, Object> body = Map.of(
                "model",    model,
                "messages", List.of(
                        Map.of("role", "system", "content", truncatedSystem),
                        Map.of("role", "user",   "content", truncatedUser)
                ),
                "max_tokens", 3000,
                "temperature", 0.1
        );

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    apiUrl,
                    new HttpEntity<>(body, headers),
                    Map.class);

            List<Map> choices = (List<Map>) response.getBody().get("choices");
            Map message = (Map) choices.get(0).get("message");
            String result = (String) message.get("content");

            log.info("OpenRouter 분석 완료 ({}자)", result.length());
            return result;

        } catch (Exception e) {
            // API 키 로그 노출 방지
            log.error("OpenRouter API 호출 실패: {}", e.getMessage());
            return "❌ AI 분석 중 오류가 발생했습니다.";
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength
                ? text.substring(0, maxLength) + "...(truncated)"
                : text;
    }
}