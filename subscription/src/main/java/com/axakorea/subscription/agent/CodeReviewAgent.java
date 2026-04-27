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
public class CodeReviewAgent {

    private final OpenRouterClient openRouterClient;
    private final GitHubClient     gitHubClient;

    @Async
    public void reviewAndComment(String repo, int prNumber, String prTitle) {
        log.info("코드 리뷰 시작: PR #{} - {}", prNumber, prTitle);

        try {
            // Step 1. 변경된 파일 수집
            List<ChangedFile> changedFiles = gitHubClient.getPrFiles(repo, prNumber);
            if (changedFiles.isEmpty()) return;

            // Step 2. diff 수집
            String diffSummary = buildDiffSummary(changedFiles);

            // Step 3. AI 코드 리뷰 요청
            String reviewResult = openRouterClient.analyze(
                    buildSystemPrompt(),
                    buildUserPrompt(prTitle, diffSummary)
            );

            // Step 4. PR 댓글 등록 (영향도 분석과 별도 댓글)
            String comment = formatComment(reviewResult, changedFiles);
            gitHubClient.postComment(repo, prNumber, comment);

            log.info("코드 리뷰 완료: PR #{}", prNumber);

        } catch (Exception e) {
            log.error("코드 리뷰 실패: PR #{}", prNumber, e);
            gitHubClient.postComment(repo, prNumber,
                    "🧹 AI 코드 리뷰 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private String buildSystemPrompt() {
        return """
                당신은 Java Spring Boot 전문 시니어 개발자입니다.
                
                역할:
                - PR에서 변경된 코드의 품질을 리뷰합니다
                - 영향도 분석이 아닌 코드 자체의 품질에 집중합니다
                
                리뷰 기준 (우선순위 순):
                1. 코드 컨벤션 (Google Java Style Guide 기준)
                   - 네이밍 규칙 (클래스 PascalCase, 메서드/변수 camelCase, 상수 UPPER_SNAKE_CASE)
                   - 들여쓰기 및 공백
                   - 불필요한 주석 및 dead code
                
                2. 클린 코드 원칙
                   - 단일 책임 원칙 (SRP) 위반 여부
                   - 메서드 길이 (20줄 이하 권장)
                   - 중복 코드 (DRY 원칙)
                   - 매직 넘버/문자열 사용 여부
                
                3. Spring Boot 베스트 프랙티스
                   - 적절한 어노테이션 사용
                   - 예외 처리 방식
                   - 의존성 주입 방식 (생성자 주입 권장)
                   - Lombok 활용 여부
                
                4. 보안 취약점
                   - SQL Injection 가능성
                   - 민감 정보 하드코딩
                   - 입력값 검증 누락
                
                응답은 반드시 한국어 Markdown 형식으로 작성하세요.
                구체적인 코드 라인을 언급하고 개선 예시를 포함하세요.
                """;
    }

    private String buildUserPrompt(String prTitle, String diff) {
        return """
            ## PR 제목: %s

            ## 변경된 코드 (diff)
            %s

            위 코드를 아래 형식으로 리뷰해주세요.
            제목 없이 바로 항목부터 시작하세요:

            1. 🔴 즉시 수정 필요 (컨벤션 위반, 보안 취약점)
            (내용)

            2. 🟡 개선 권고 (클린 코드, 베스트 프랙티스)
            (내용)

            3. 📝 총평 (전체적인 코드 품질 점수: X / 10)
            (내용)

            4. 💡 수정 예시 코드 (필요한 경우)
            (내용)
            """.formatted(prTitle, diff);
    }

    private String buildDiffSummary(List<ChangedFile> files) {
        return files.stream()
                .map(f -> """
                        ### %s
                        ```java
                        %s
                        ```
                        """.formatted(
                        f.getFilename(),
                        f.getPatch() != null ? f.getPatch() : "(변경 없음)"
                ))
                .collect(Collectors.joining("\n"));
    }

    private String formatComment(String reviewResult, List<ChangedFile> changedFiles) {
        String fileList = changedFiles.stream()
                .map(f -> "- `" + f.getFilename() + "`")
                .collect(Collectors.joining("\n"));

        return """
                ## 🧹 AI 코드 리뷰 결과
                
                > **리뷰 대상 파일:**
                %s
                
                ---
                
                %s
                
                ---
                *Powered by OpenRouter AI Agent*
                """.formatted(fileList, reviewResult);
    }
}