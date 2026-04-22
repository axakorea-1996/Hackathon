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

            // Step 5. AI 분석 병렬 실행
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

            // Step 6. 둘 다 완료될 때까지 대기
            String analysisResult = analysisFuture.get();
            String mermaidDiagram = diagramFuture.get();

            // Step 7. PR 댓글 등록
            String comment = formatComment(analysisResult, mermaidDiagram, changedFiles);
            gitHubClient.postComment(repo, prNumber, comment);

            log.info("영향도 분석 + 다이어그램 완료: PR #{}", prNumber);

        } catch (Exception e) {
            log.error("영향도 분석 실패: PR #{}", prNumber, e);
            gitHubClient.postComment(repo, prNumber,
                    "🤖 AI 영향도 분석 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    // ── 영향도 분석 프롬프트 ──────────────────────────
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

    // ── 다이어그램 생성 프롬프트 ──────────────────────
    private String buildDiagramSystemPrompt() {
        return """
                당신은 소프트웨어 아키텍처 다이어그램 전문가입니다.
                
                역할:
                - 변경된 코드와 기존 코드의 관계를 Mermaid 다이어그램으로 표현합니다
                - GitHub PR 댓글에 렌더링될 수 있는 유효한 Mermaid 문법을 사용합니다
                
                절대 지켜야 할 규칙:
                1. 반드시 ```mermaid 코드 블록으로 감싸서 반환하세요
                2. flowchart TD 방향을 사용하세요
                3. 변경된 파일은 빨간색(style fill:#FF6B6B,color:#000)으로 표시하세요
                4. 영향받는 기존 파일은 노란색(style fill:#FFE66D,color:#000)으로 표시하세요
                5. 영향없는 파일은 기본색으로 표시하세요
                6. 클래스 간 의존 관계를 화살표로 표현하세요
                7. 다이어그램만 반환하고 설명 텍스트는 포함하지 마세요
                8. 노드 이름에 특수문자(괄호, 슬래시, 점 등)를 사용하지 마세요
                9. 노드 이름은 영문 알파벳과 숫자만 사용하세요
                10. 노드 레이블에 한글을 사용할 경우 큰따옴표로 감싸세요
                    예시: A["구독서비스"] --> B["컨트롤러"]
                11. 화살표 레이블에 특수문자를 사용하지 마세요
                    올바른 예시: A -->|calls| B
                    잘못된 예시: A -->|calls()| B
                12. style 명령어에는 반드시 노드 ID만 사용하세요
                    올바른 예시: style DB fill:#CCCCCC,color:#000
                    잘못된 예시: style DB[(Database)] fill:#CCCCCC,color:#000
                13. DB 노드는 노드 정의와 style을 반드시 분리하세요
                    올바른 예시:
                    DB[(Database)]
                    style DB fill:#CCCCCC,color:#000,stroke:#999999
                """;
    }

    private String buildDiagramUserPrompt(String prTitle,
                                          List<ChangedFile> changedFiles,
                                          String existingContext) {
        String changedFileNames = changedFiles.stream()
                .map(f -> extractClassName(f.getFilename()))
                .collect(Collectors.joining(", "));

        return """
                ## PR 제목: %s
                
                ## 변경된 클래스 목록
                %s
                
                ## 기존 연관 코드
                %s
                
                위 정보를 기반으로 Mermaid 다이어그램을 생성해주세요.
                
                1. MVC 계층 구조를 표현하세요 (Controller → Service → Repository → Domain)
                2. 변경된 클래스는 빨간색으로 강조하세요
                3. 영향받는 기존 클래스는 노란색으로 표시하세요
                4. 클래스 간 호출 관계를 화살표로 연결하세요
                5. DB 테이블과의 연관관계도 포함하세요
                6. DB 노드는 반드시 노드 정의와 style을 분리하여 작성하세요
                   올바른 예시:
                   DB[(Database)]
                   style DB fill:#CCCCCC,color:#000,stroke:#999999
                
                반드시 ```mermaid 블록으로 감싸서 반환하세요.
                반드시 지킬 것:
                - 노드 라벨에 \\n 사용 절대 금지
                - style 구문에 노드 ID만 사용 (괄호, 특수문자 금지)
                - DB 노드는 선언: DB[(Database)], 스타일: style DB fill:#CCCCCC,color:#000
                - ```mermaid 블록으로 감싸서 반환

                """.formatted(prTitle, changedFileNames, existingContext);
    }

    // ── PR 댓글 포맷 ──────────────────────────────────
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

    // ── 유틸 ──────────────────────────────────────────
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