package com.axakorea.subscription.client;

import com.axakorea.subscription.dto.impact.ChangedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GitHubClient {

    @Value("${github.token}")
    private String githubToken;

    private static final String BASE_URL   = "https://api.github.com";
    // ⚠️ 보안 추가: 재귀 깊이 제한
    private static final int    MAX_DEPTH  = 3;
    // ⚠️ 보안 추가: 타임아웃 설정
    private final RestTemplate  restTemplate;

    public GitHubClient(RestTemplateBuilder builder) {
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    public List<ChangedFile> getPrFiles(String repo, int prNumber) {
        // ⚠️ 보안 추가: 입력값 검증
        validateRepo(repo);

        String url = BASE_URL + "/repos/%s/pulls/%d/files"
                .formatted(repo, prNumber);

        ResponseEntity<List<ChangedFile>> response = restTemplate.exchange(
                url, HttpMethod.GET,
                new HttpEntity<>(githubHeaders()),
                new ParameterizedTypeReference<>() {});

        List<ChangedFile> files = response.getBody();
        log.info("PR #{} 변경 파일 수: {}", prNumber,
                files != null ? files.size() : 0);
        return files != null ? files : List.of();
    }

    public String getFileContent(String repo, String filePath) {
        validateRepo(repo);
        // ⚠️ 보안 추가: path traversal 방지
        validateFilePath(filePath);

        String url = BASE_URL + "/repos/%s/contents/%s"
                .formatted(repo, filePath);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(githubHeaders()), Map.class);

            String encoded = (String) response.getBody().get("content");
            if (encoded == null) return "";

            String cleaned = encoded.replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(cleaned));

        } catch (Exception e) {
            log.warn("파일 내용 가져오기 실패: {} - {}", filePath, e.getMessage());
            return "";
        }
    }

    public void postComment(String repo, int prNumber, String body) {
        validateRepo(repo);
        // ⚠️ 보안 추가: 댓글 길이 제한
        if (body != null && body.length() > 65536) {
            body = body.substring(0, 65536) + "\n\n...(내용 생략)";
        }

        String url = BASE_URL + "/repos/%s/issues/%d/comments"
                .formatted(repo, prNumber);

        try {
            restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(Map.of("body", body), githubHeaders()),
                    Map.class);
            log.info("PR #{} 댓글 등록 완료", prNumber);

        } catch (Exception e) {
            log.error("PR 댓글 등록 실패: #{}", prNumber, e);
        }
    }

    public List<String> getFilesInDirectory(String repo, String dirPath) {
        validateRepo(repo);
        validateFilePath(dirPath);
        List<String> result = new ArrayList<>();
        collectFiles(repo, dirPath, result, 0);
        return result;
    }

    private void collectFiles(String repo, String dirPath,
                              List<String> result, int depth) {
        // ⚠️ 보안 추가: 재귀 깊이 제한
        if (depth >= MAX_DEPTH) {
            log.warn("재귀 깊이 제한 도달: {}", dirPath);
            return;
        }

        String url = BASE_URL + "/repos/%s/contents/%s"
                .formatted(repo, dirPath);

        try {
            ResponseEntity<List<Map>> response = restTemplate.exchange(
                    url, HttpMethod.GET,
                    new HttpEntity<>(githubHeaders()),
                    new ParameterizedTypeReference<>() {});

            List<Map> items = response.getBody();
            if (items == null) return;

            for (Map item : items) {
                String type = (String) item.get("type");
                String path = (String) item.get("path");

                if ("file".equals(type)) {
                    result.add(path);
                } else if ("dir".equals(type)) {
                    collectFiles(repo, path, result, depth + 1);
                }
            }
        } catch (Exception e) {
            log.warn("디렉토리 조회 실패: {} - {}", dirPath, e.getMessage());
        }
    }

    private HttpHeaders githubHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // ⚠️ 보안 추가: 토큰 앞뒤 공백 제거
        headers.set("Authorization", "Bearer " + githubToken.trim());
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    // ⚠️ 보안 추가: repo 형식 검증
    private void validateRepo(String repo) {
        if (repo == null || !repo.matches("^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$")) {
            throw new IllegalArgumentException("Invalid repo format: " + repo);
        }
    }

    // ⚠️ 보안 추가: path traversal 방지
    private void validateFilePath(String path) {
        if (path == null || path.contains("..") || path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid file path: " + path);
        }
    }
}