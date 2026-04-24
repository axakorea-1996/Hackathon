package com.axakorea.subscription.webhook;

import com.axakorea.subscription.agent.AgentOrchestrator;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final AgentOrchestrator agentOrchestrator;

    // ⚠️ 보안 추가: webhook secret 설정
    @Value("${github.webhook.secret:}")
    private String webhookSecret;

    @PostMapping("/github")
    public ResponseEntity<String> handleGitHubEvent(
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String rawBody,
            @RequestBody Map<String, Object> payload) {

        // ⚠️ 보안 추가: webhook 서명 검증
        if (!webhookSecret.isBlank() && !verifySignature(rawBody, signature)) {
            log.warn("webhook 서명 검증 실패 - 비인가 요청 차단");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        log.info("GitHub 이벤트 수신: {} - {}", eventType,
                payload.get("action"));

        if ("pull_request".equals(eventType)) {
            String action = (String) payload.get("action");

            if ("opened".equals(action) || "synchronize".equals(action)) {
                Map<String, Object> pr   = (Map<String, Object>) payload.get("pull_request");
                Map<String, Object> repo = (Map<String, Object>) payload.get("repository");

                int    prNumber = (int) pr.get("number");
                String prTitle  = (String) pr.get("title");
                String repoName = (String) repo.get("full_name");

                // 입력값 검증
                if (repoName == null || prTitle == null) {
                    return ResponseEntity.badRequest().body("Invalid payload");
                }

                log.info("PR 감지: {} #{} - {}", repoName, prNumber, prTitle);
                agentOrchestrator.orchestrate(repoName, prNumber, prTitle);
            }
        }

        return ResponseEntity.ok("OK");
    }

    // ⚠️ 보안 추가: HMAC-SHA256 서명 검증
    private boolean verifySignature(String payload, String signature) {
        if (signature == null || !signature.startsWith("sha256=")) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(
                    payload.getBytes(StandardCharsets.UTF_8));
            String expected = "sha256=" + HexFormat.of().formatHex(hash);

            // timing-safe 비교
            return timingSafeEquals(expected, signature);
        } catch (Exception e) {
            log.error("서명 검증 오류", e);
            return false;
        }
    }

    // timing attack 방지
    private boolean timingSafeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}