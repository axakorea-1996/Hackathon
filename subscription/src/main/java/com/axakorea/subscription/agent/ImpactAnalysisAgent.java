package com.axakorea.subscription.agent;

import com.axakorea.subscription.client.GitHubClient;
import com.axakorea.subscription.client.OpenRouterClient;
import com.axakorea.subscription.dto.impact.ChangedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImpactAnalysisAgent {

    private final OpenRouterClient     openRouterClient;
    private final GitHubClient         gitHubClient;
    private final CodeContextCollector contextCollector;
    private final DependencyAnalyzer   dependencyAnalyzer;

    // ── 프롬프트 인젝션 패턴 ─────────────────────────
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("(?i)(ignore|forget|disregard).{0,30}(above|previous|instruction|prompt)"),
            Pattern.compile("(?i)\\[SYSTEM\\]|\\[INST\\]|\\[/INST\\]"),
            Pattern.compile("(?i)<\\|im_start\\|>|<\\|im_end\\|>"),
            Pattern.compile("(?i)you are now|act as|pretend to be"),
            Pattern.compile("(?i)reveal|expose|print|output.{0,20}(key|token|secret|password|api)"),
            Pattern.compile("(?i)###\\s*(system|instruction|prompt)")
    );

    // ✅ 프롬프트 인젝션 방어
    private String sanitizeForPrompt(String input, int maxLength) {
        if (input == null) return "";

        String sanitized = input;
        for (Pattern pattern : INJECTION_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("");
        }

        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength) + "\n...(truncated for security)";
        }

        return sanitized.trim();
    }

    @Async
    public void analyzeAndComment(String repo, int prNumber, String prTitle) {
        log.info("영향도 분석 시작: PR #{} - {}", prNumber, prTitle);

        try {
            List<ChangedFile> changedFiles = gitHubClient.getPrFiles(repo, prNumber);
            if (changedFiles.isEmpty()) {
                log.info("PR #{} 변경 파일 없음, 분석 종료", prNumber);
                return;
            }

            String existingContext = contextCollector.collectRelatedContext(repo, changedFiles);
            String dependencyTree  = dependencyAnalyzer.analyze(changedFiles);
            String diffSummary     = buildDiffSummary(changedFiles);

            // AI 분석 병렬 실행
            CompletableFuture<String> analysisFuture = CompletableFuture.supplyAsync(() ->
                    openRouterClient.analyze(
                            buildSystemPrompt(),
                            buildUserPrompt(prTitle, diffSummary, existingContext, dependencyTree)
                    )
            );

            CompletableFuture<String> diagramFuture = CompletableFuture.supplyAsync(() ->
                    openRouterClient.analyze(
                            buildDiagramSystemPrompt(),
                            buildDiagramUserPrompt(prTitle, changedFiles, existingContext)
                    )
            );

            String analysisResult = analysisFuture.get();
            String mermaidDiagram = diagramFuture.get();

            String comment = formatComment(analysisResult, mermaidDiagram, changedFiles);
            gitHubClient.postComment(repo, prNumber, comment);

            log.info("영향도 분석 + 다이어그램 완료: PR #{}", prNumber);

        } catch (Exception e) {
            log.error("영향도 분석 실패: PR #{}", prNumber, e);
            gitHubClient.postComment(repo, prNumber,
                    "🤖 AI 영향도 분석 중 오류가 발생했습니다.");
        }
    }

    // ── 영향도 분석 프롬프트 ─────────────────────────
    private String buildSystemPrompt() {
        return """
            당신은 Spring Boot MVC 프로젝트 전문 시니어 아키텍처 리뷰어입니다.

            역할:
            - 새로 추가/변경된 코드가 기존 코드에 미치는 영향을 분석합니다
            - 장애 발생 가능성을 사전에 탐지합니다
            - MVC 패턴(Controller → Service → Repository → Domain)의 계층 구조를 기반으로 분석합니다

            출력 형식 규칙 (반드시 준수):
            - 각 항목 제목은 반드시 ### 마크다운 헤딩으로 작성
            - 번호 목록(1. 2. 3.) 절대 사용 금지
            - 올바른 예시: ### 1. 🔴 장애 위험 항목
            - 잘못된 예시: 1. 🔴 장애 위험 항목

            분석 기준:
            1. 트랜잭션 정합성 (데이터 불일치 가능성)
            2. Breaking Change (기존 API 응답 형식 변경)
            3. N+1 쿼리 문제 (성능 장애)
            4. NullPointerException 위험
            5. 순환 의존성 (Circular Dependency)
            6. DB 스키마 변경으로 인한 기존 데이터 영향

            응답은 반드시 한국어 Markdown 형식으로 작성하세요.
            응답 시작 부분에 제목(PR 영향도 분석, PR 분석 결과 등)을 포함하지 마세요.
            """;
    }

    private String buildUserPrompt(String prTitle, String diff,
                                   String existingCode, String depTree) {
        String safeTitle        = sanitizeForPrompt(prTitle,       500);
        String safeDiff         = sanitizeForPrompt(diff,          50000);
        String safeExistingCode = sanitizeForPrompt(existingCode,  30000);
        String safeDepTree      = sanitizeForPrompt(depTree,       5000);

        return """
            ## PR 제목: %s

            ## 의존성 트리
            %s

            ## 변경된 코드 (diff)
            %s

            ## 기존 연관 코드
            %s

            위 변경사항을 기존 코드와 비교하여 아래 형식으로만 응답하세요.
            제목 없이 바로 항목부터 시작하세요.

            중요: 각 항목 제목은 반드시 아래와 같이 ### 헤딩으로 작성하세요.
            번호 목록 형식(1. 2. 3.) 절대 사용 금지.

            ### 1. 🔴 장애 위험 항목 (즉시 수정 필요)
            (내용)

            ### 2. 🟡 영향 범위 (주의 필요한 기존 파일 목록)
            (내용)

            ### 3. 🟢 안전 확인 항목
            (내용)
            """.formatted(safeTitle, safeDepTree, safeDiff, safeExistingCode);
    }

    // ── 다이어그램 생성 프롬프트 ─────────────────────
    private String buildDiagramSystemPrompt() {
        return """
                당신은 소프트웨어 아키텍처 다이어그램 전문가입니다.

                역할:
                - 변경된 코드와 기존 코드의 관계를 Mermaid 다이어그램으로 표현합니다
                - GitHub PR 댓글에 렌더링될 수 있는 유효한 Mermaid 문법을 사용합니다

                규칙:
                1. 반드시 ```mermaid 코드 블록으로 감싸서 반환하세요
                2. flowchart TD 방향을 사용하세요
                3. 변경된 파일은 빨간색(style fill:#FF6B6B,color:#000)으로 표시하세요
                4. 영향받는 기존 파일은 노란색(style fill:#FFE66D,color:#000)으로 표시하세요
                5. 영향없는 파일은 기본색으로 표시하세요
                6. 클래스 간 의존 관계를 화살표로 표현하세요
                7. 다이어그램만 반환하고 설명 텍스트는 포함하지 마세요
                8. 노드 이름에 특수문자(괄호, 슬래시, 점 등)를 사용하지 마세요
                9. style 명령어에는 반드시 노드 ID만 사용하세요
                10. DB 노드는 노드 정의와 style을 반드시 분리하세요
                11. 모든 노드는 반드시 대괄호[] 형식만 사용하세요
                                올바른 예시: A[ClassName]
                                절대 사용 금지: A((ClassName)), A{ClassName}, A[(ClassName)]
                """;
    }

    private String buildDiagramUserPrompt(String prTitle,
                                          List<ChangedFile> changedFiles,
                                          String existingContext) {
        String changedFileNames = changedFiles.stream()
                .map(f -> extractClassName(f.getFilename()))
                .collect(Collectors.joining(", "));

        // ✅ 사용자 입력값 sanitize
        String safeTitle        = sanitizeForPrompt(prTitle,          500);
        String safeFileNames    = sanitizeForPrompt(changedFileNames,  1000);
        String safeContext      = sanitizeForPrompt(existingContext,   30000);

        return """
                ## PR 제목: %s

                ## 변경된 클래스 목록
                %s

                ## 기존 연관 코드
                %s

                위 정보를 기반으로 아래 조건에 맞는 Mermaid 다이어그램을 생성해주세요:

                1. MVC 계층 구조를 표현하세요 (Controller → Service → Repository → Domain)
                2. 변경된 클래스는 빨간색으로 강조하세요
                3. 영향받는 기존 클래스는 노란색으로 표시하세요
                4. 클래스 간 호출 관계를 화살표로 연결하세요
                5. DB 테이블과의 연관관계도 포함하세요
                6. DB 노드는 반드시 노드 정의와 style을 분리하여 작성하세요

                반드시 ```mermaid 블록으로 감싸서 반환하세요.
                """.formatted(safeTitle, safeFileNames, safeContext);
    }

    // ── PR 댓글 포맷 ─────────────────────────────────
    private String formatComment(String aiResult,
                                 String mermaidDiagram,
                                 List<ChangedFile> changedFiles) {
        String fileList = changedFiles.stream()
                .map(f -> "- `" + f.getFilename() + "`")
                .collect(Collectors.joining("\n"));

        return """
                ## 🤖 AI 영향도 분석 결과

                > **분석 대상 파일:**
                %s

                ---

                ## 📊 코드 의존성 다이어그램

                %s

                ---

                ## 🔍 영향도 분석

                %s

                ---
                *Powered by OpenRouter AI Agent*
                """.formatted(fileList, mermaidDiagram, aiResult);
    }

    // ── 유틸 ─────────────────────────────────────────
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

    private String extractClassName(String filePath) {
        String fileName = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        return fileName.replace(".java", "");
    }
}