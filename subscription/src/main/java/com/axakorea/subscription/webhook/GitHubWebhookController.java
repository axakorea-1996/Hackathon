package com.axakorea.subscription.webhook;

import com.axakorea.subscription.agent.ImpactAnalysisAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class GitHubWebhookController {

    private final ImpactAnalysisAgent impactAnalysisAgent;

    @PostMapping("/github")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody Map<String, Object> payload,
            @RequestHeader("X-GitHub-Event") String eventType) {

        // PR 이벤트만 처리
        if (!"pull_request".equals(eventType)) {
            return ResponseEntity.ok().build();
        }

        String action = (String) payload.get("action");
        log.info("GitHub 이벤트 수신: {} - {}", eventType, action);

        // PR 오픈 또는 새 커밋 push 시 분석 실행
        if ("opened".equals(action) || "synchronize".equals(action)) {

            Map<String, Object> pr   = (Map<String, Object>) payload.get("pull_request");
            Map<String, Object> repo = (Map<String, Object>) payload.get("repository");

            String repoFullName = (String) repo.get("full_name");
            int    prNumber     = (int)    pr.get("number");
            String prTitle      = (String) pr.get("title");

            log.info("PR 감지: {} #{} - {}", repoFullName, prNumber, prTitle);

            // 비동기 분석 실행 (webhook은 10초 내 응답해야 해서 즉시 200 반환)
            impactAnalysisAgent.analyzeAndComment(repoFullName, prNumber, prTitle);
        }

        return ResponseEntity.ok().build();
    }
}
