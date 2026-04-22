package com.axakorea.subscription.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOrchestrator {

    private final ImpactAnalysisAgent impactAnalysisAgent;
    private final CodeReviewAgent     codeReviewAgent;

    @Async
    public void runSequentially(String repoName, int prNumber, String prTitle) {
        try {
            // 1. 영향도 분석
            log.info("영향도 분석 Agent 시작: PR #{}", prNumber);
            impactAnalysisAgent.analyzeAndComment(repoName, prNumber, prTitle);

            // 2. 딜레이 (rate limit 방지)
            log.info("코드 리뷰 대기 중 (5초)...");
            Thread.sleep(5000);

            // 3. 코드 리뷰
            log.info("코드 리뷰 Agent 시작: PR #{}", prNumber);
            codeReviewAgent.reviewAndComment(repoName, prNumber, prTitle);

            log.info("모든 Agent 완료: PR #{}", prNumber);

        } catch (Exception e) {
            log.error("Agent 실행 실패: PR #{}", prNumber, e);
        }
    }
}