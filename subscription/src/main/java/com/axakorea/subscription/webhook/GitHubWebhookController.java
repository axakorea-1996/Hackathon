package com.axakorea.subscription.webhook;

import com.axakorea.subscription.agent.CodeReviewAgent;
import com.axakorea.subscription.agent.ImpactAnalysisAgent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/webhook")
public class GitHubWebhookController {

    private final ImpactAnalysisAgent impactAnalysisAgent;
    private final CodeReviewAgent     codeReviewAgent;      // ← 추가

    @PostMapping("/github")
    public void handleGitHubEvent(
            @RequestHeader("X-GitHub-Event") String event,
            @RequestBody Map<String, Object> payload) {

        log.info("GitHub 이벤트 수신: {}", event);

        if (!"pull_request".equals(event)) return;

        String action = (String) payload.get("action");
        if (!"opened".equals(action) && !"synchronize".equals(action)) return;

        Map<String, Object> pr   = (Map<String, Object>) payload.get("pull_request");
        Map<String, Object> repo = (Map<String, Object>) payload.get("repository");

        int    prNumber  = (int)    pr.get("number");
        String prTitle   = (String) pr.get("title");
        String repoName  = (String) repo.get("full_name");

        log.info("PR 감지: {} #{} - {}", repoName, prNumber, prTitle);

        // 두 Agent 동시 실행 (@Async 덕분에 병렬 처리)
        impactAnalysisAgent.analyzeAndComment(repoName, prNumber, prTitle);  // 댓글 1
        codeReviewAgent.reviewAndComment(repoName, prNumber, prTitle);       // 댓글 2
    }
}