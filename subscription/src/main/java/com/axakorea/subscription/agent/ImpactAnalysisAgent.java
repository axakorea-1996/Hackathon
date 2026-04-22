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
            List<ChangedFile> changedFiles = gitHubClient.getPrFiles(repo, prNumber);
            if (changedFiles.isEmpty()) {
                log.info("PR #{} 변경 파일 없음, 분석 종료", prNumber);
                return;
            }

            String existingContext = contextCollector.collectRelatedContext(repo, changedFiles);
            String dependencyTree  = dependencyAnalyzer.analyze(changedFiles);
            String diffSummary     = buildDiffSummary(changedFiles);

            // AI 영향도 분석
            String analysisResult = openRouterClient.analyze(
                    buildSystemPrompt(),
                    buildUserPrompt(prTitle, diffSummary, existingContext, dependencyTree)
            );

            // AI Mermaid 다이어그램 생성
            String mermaidDiagram = openRouterClient.analyze(
                    buildDiagramSystemPrompt(),
                    buildDiagramUserPrompt(prTitle, changedFiles, existingContext)
            );

            // PR 댓글 등록 (분석 + 다이어그램)
            String comment = formatComment(analysisResult, mermaidDiagram, changedFiles);
            gitHubClient.postComment(repo, prNumber, comment);

            log.info("영향도 분석 + 다이어그램 완료: PR #{}", prNumber);

        } catch (Exception e) {
            log.error("영향도 분석 실패: PR #{}", prNumber, e);
            gitHubClient.postComment(repo, prNumber,
                    "🤖 AI 영향도 분석 중 오류가 발생했습니다: " + e.getMessage());
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

    // ── 다이어그램 생성 프롬프트 ─────────────────────
    private String buildDiagramSystemPrompt() {
        return """
            당신은 소프트웨어 아키텍처 다이어그램 전문가입니다.
            
            절대 지켜야 할 규칙:
            1. 반드시 ```mermaid 코드 블록으로 감싸서 반환하세요
            2. flowchart TD 방향을 사용하세요
            3. 노드 라벨에 절대 \\n 사용 금지
            4. 노드 ID는 영문자와 숫자만 사용 (특수문자 금지)
            5. style 구문에 노드 ID만 사용
               잘못된 예: style DB[(Database)] fill:#FF6B6B  ← 절대 금지
               올바른 예: style DB fill:#FF6B6B
            6. DB 노드 선언: DB[(Database)],  스타일: style DB fill:#CCCCCC,color:#000
            7. 변경된 파일 → 빨간색 fill:#FF6B6B,color:#000
            8. 영향받는 파일 → 노란색 fill:#FFE66D,color:#000
            9. 화살표 라벨 2-4자 (호출, 조회, 매핑, 사용)
            10. 다이어그램 코드만 반환, 설명 텍스트 금지
            11. 반드시 사용자가 전달한 변경된 클래스를 빨간색으로 표시하세요
                예시 클래스를 사용하지 말고 실제 변경된 클래스만 사용하세요
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
            
            ## 🔴 빨간색으로 표시할 변경된 클래스 (반드시 이 클래스를 사용하세요)
            %s
            
            ## 기존 연관 코드
            %s
            
            위 변경된 클래스를 중심으로 Mermaid 다이어그램을 생성해주세요.
            
            반드시 지킬 것:
            - 빨간색 노드는 반드시 위에 명시된 변경된 클래스여야 합니다
            - 예시 클래스(SubscriptionController 등) 절대 사용 금지
            - 노드 라벨에 \\n 사용 절대 금지
            - style 구문에 노드 ID만 사용 (괄호, 특수문자 금지)
            - DB 노드: 선언 DB[(Database)], 스타일 style DB fill:#CCCCCC,color:#000
            - ```mermaid 블록으로 감싸서 반환
            """.formatted(prTitle, changedFileNames, existingContext);
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

    // 파일 경로 → 클래스명 추출
    // 예) subscription/src/.../SubscriptionService.java → SubscriptionService
    private String extractClassName(String filePath) {
        String fileName = filePath.contains("/")
                ? filePath.substring(filePath.lastIndexOf('/') + 1)
                : filePath;
        return fileName.replace(".java", "");
    }
}