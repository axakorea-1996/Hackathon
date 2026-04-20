package com.axakorea.subscription.agent;

import com.axakorea.subscription.client.GitHubClient;
import com.axakorea.subscription.client.OpenRouterClient;
import com.axakorea.subscription.dto.impact.ChangedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImpactAnalysisAgent {

    private final OpenRouterClient     openRouterClient;
    private final GitHubClient         gitHubClient;
    private final CodeContextCollector contextCollector;
    private final DependencyAnalyzer   dependencyAnalyzer;

    @Async
    public void analyzeAndComment(String repo, int prNumber, String prTitle) {
        log.info("영향도 분석 시작: PR #{} - {}", prNumber, prTitle);

        try {
            // Step 1. 변경된 파일 수집
            List<ChangedFile> changedFiles = gitHubClient.getPrFiles(repo, prNumber);
            if (changedFiles.isEmpty()) {
                log.info("PR #{} 변경 파일 없음, 분석 종료", prNumber);
                return;
            }

            // Step 2. 기존 연관 코드 수집
            String existingContext = contextCollector.collectRelatedContext(repo, changedFiles);

            // Step 3. 의존성 트리 분석
            String dependencyTree = dependencyAnalyzer.analyze(changedFiles);

            // Step 4. diff 정리
            String diffSummary = buildDiffSummary(changedFiles);

            // Step 5. AI 분석 요청
            String analysisResult = openRouterClient.analyze(
                buildSystemPrompt(),
                buildUserPrompt(prTitle, diffSummary, existingContext, dependencyTree)
            );

            // Step 6. PR 댓글 등록
            String comment = formatComment(analysisResult, changedFiles);
            gitHubClient.postComment(repo, prNumber, comment);

            log.info("영향도 분석 완료: PR #{}", prNumber);

        } catch (Exception e) {
            log.error("영향도 분석 실패: PR #{}", prNumber, e);
            gitHubClient.postComment(repo, prNumber,
                "🤖 AI 영향도 분석 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        return """
                당신은 Spring Boot MVC 프로젝트 전문 시니어 아키텍처 리뷰어입니다.
                
                역할:
                - 새로 추가/변경된 코드가 기존 코드에 미치는 영향을 분석합니다
                - 장애 발생 가능성을 사전에 탐지합니다
                - MVC 패턴(Controller → Service → Repository → Domain)의 계층 구조를 기반으로 분석합니다
                
                분석 기준:
                1. 트랜잭션 정합성 (데이터 불일치 가능성)
                2. Breaking Change (기존 API 응답 형식 변경)
                3. N+1 쿼리 문제 (성능 장애)
                4. NullPointerException 위험
                5. 순환 의존성 (Circular Dependency)
                6. DB 스키마 변경으로 인한 기존 데이터 영향
                
                응답은 반드시 한국어 Markdown 형식으로 작성하세요.
                """;
    }

    private String buildUserPrompt(String prTitle, String diff,
                                   String existingCode, String depTree) {
        return """
                ## PR 제목: %s
                
                ## 의존성 트리
                %s
                
                ## 변경된 코드 (diff)
                %s
                
                ## 기존 연관 코드
                %s
                
                위 변경사항을 기존 코드와 비교하여 아래 형식으로 영향도를 분석해주세요:
                
                1. 🔴 장애 위험 항목 (즉시 수정 필요)
                2. 🟡 영향 범위 (주의 필요한 기존 파일 목록)
                3. 🟢 안전 확인 항목
                4. 📋 최종 판정 (merge 가능 여부)
                5. 💡 수정 권고 코드 (있을 경우)
                """.formatted(prTitle, depTree, diff, existingCode);
    }

    private String buildDiffSummary(List<ChangedFile> files) {
        return files.stream()
                .map(f -> """
                        ### %s
                        - 추가: +%d줄 / 삭제: -%d줄
                        ```java
                        %s
                        ```
                        """.formatted(
                        f.getFilename(),
                        f.getAdditions(),
                        f.getDeletions(),
                        f.getPatch() != null ? f.getPatch() : "(변경 없음)"
                ))
                .collect(Collectors.joining("\n"));
    }

    private String formatComment(String aiResult, List<ChangedFile> changedFiles) {
        String fileList = changedFiles.stream()
                .map(f -> "- `" + f.getFilename() + "`")
                .collect(Collectors.joining("\n"));

        return """
                ## 🤖 AI 영향도 분석 결과
                
                > **분석 대상 파일:**
                %s
                
                ---
                
                %s
                
                ---
                *Powered by OpenRouter AI Agent*
                """.formatted(fileList, aiResult);
    }
}
