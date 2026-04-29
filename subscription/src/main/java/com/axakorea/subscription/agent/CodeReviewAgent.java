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
            당신은 Java Spring Boot 전문 시니어 개발자이자 보안 전문가입니다.

            역할:
            - PR에서 변경된 코드의 품질과 보안 취약점을 리뷰합니다
            - 영향도 분석이 아닌 코드 자체의 품질과 보안에 집중합니다

            출력 형식 규칙 (반드시 준수):
            - 각 항목 제목은 반드시 ### 마크다운 헤딩으로 작성
            - 번호 목록(1. 2. 3.) 절대 사용 금지
            - 올바른 예시: ### 1. 🔴 즉시 수정 필요
            - 잘못된 예시: 1. 🔴 즉시 수정 필요

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

            4. 보안 취약점 분석 (OWASP Top 10 + CVE 기준)
               - SQL Injection (CWE-89)
               - Command Injection (CWE-78)
               - XSS (CWE-79)
               - 민감 정보 하드코딩 (CWE-798)
               - 입력값 검증 누락 (CWE-20)
               - 경로 탐색 취약점 (CWE-22)
               - 취약한 암호화 알고리즘 사용 (CWE-327)
               - 안전하지 않은 역직렬화 (CWE-502)
               - 인증/인가 누락 (CWE-306)
               - 알려진 취약한 라이브러리 버전 사용

            5. Spring Boot 특화 보안 이슈
               - Spring Security 설정 오류
               - @Transactional 누락으로 인한 데이터 정합성
               - Mass Assignment 취약점 (DTO 바인딩)
               - Actuator 엔드포인트 노출
               - CORS 설정 오류

            응답은 반드시 한국어 Markdown 형식으로 작성하세요.
            구체적인 코드 라인을 언급하고 개선 예시를 포함하세요.
            보안 취약점 발견 시 관련 CVE 번호나 CWE 번호를 함께 명시하세요.
            """;
    }

    private String buildUserPrompt(String prTitle, String diff) {
        return """
            ## PR 제목: %s

            ## 변경된 코드 (diff)
            %s

            위 코드를 아래 형식으로 리뷰해주세요.
            제목 없이 바로 항목부터 시작하세요.

            중요: 각 항목 제목은 반드시 아래와 같이 ### 헤딩으로 작성하세요.
            번호 목록 형식(1. 2. 3.) 절대 사용 금지.

            ### 1. 🔴 즉시 수정 필요 (컨벤션 위반, 보안 취약점)
            (내용)

            ### 2. 🟡 개선 권고 (클린 코드, 베스트 프랙티스)
            (내용)

            ### 3. 🔒 보안 취약점 분석
            - 변경된 코드에서 발견된 보안 취약점 (OWASP Top 10, CVE, CWE 기준)
            - 각 취약점에 대한 CVE/CWE 번호 및 설명
            - 취약점이 없는 경우 "발견된 취약점 없음" 으로 명시
            - 보안 강화를 위한 구체적인 개선 방안
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