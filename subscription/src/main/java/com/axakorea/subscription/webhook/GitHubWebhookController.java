package com.axakorea.subscription.webhook;

import com.axakorea.subscription.agent.CodeReviewAgent;
import com.axakorea.subscription.agent.ImpactAnalysisAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhook")
public class GitHubWebhookController {

    private final ImpactAnalysisAgent impactAnalysisAgent;
    private final CodeReviewAgent     codeReviewAgent;

    @PostMapping("/github")
    public void handleGitHubEvent(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestBody Map<String, Object> payload) {

        log.info("GitHub 이벤트 수신: {} - {}", event, payload.get("action"));

        if (!"pull_request".equals(event)) return;

        String action = (String) payload.get("action");
        if (!"opened".equals(action) && !"synchronize".equals(action)) return;

        Map<String, Object> pr   = (Map<String, Object>) payload.get("pull_request");
        Map<String, Object> repo = (Map<String, Object>) payload.get("repository");

        int    prNumber = (int)    pr.get("number");
        String prTitle  = (String) pr.get("title");
        String repoName = (String) repo.get("full_name");

        log.info("PR 감지: {} #{} - {}", repoName, prNumber, prTitle);

        // 순차 실행 — 별도 스레드에서 영향도 분석 후 코드 리뷰
        runAgentsSequentially(repoName, prNumber, prTitle);
    }

    @Async
    public void runAgentsSequentially(String repoName, int prNumber, String prTitle) {
        try {
            // 1. 영향도 분석 먼저
            log.info("영향도 분석 Agent 시작: PR #{}", prNumber);
            impactAnalysisAgent.analyzeAndComment(repoName, prNumber, prTitle);

            // 2. OpenRouter rate limit 방지용 딜레이 (3초)
            log.info("코드 리뷰 Agent 대기 중 (3초)...");
            Thread.sleep(3000);

            // 3. 코드 리뷰
            log.info("코드 리뷰 Agent 시작: PR #{}", prNumber);
            codeReviewAgent.reviewAndComment(repoName, prNumber, prTitle);

        } catch (Exception e) {
            log.error("Agent 실행 실패: PR #{}", prNumber, e);
        }
    }
}