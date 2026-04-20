package com.axakorea.subscription.client;

import com.axakorea.subscription.dto.impact.ChangedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GitHubClient {

    @Value("${github.token}")
    private String githubToken;

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String BASE_URL = "https://api.github.com";

    // PR 변경 파일 목록 가져오기
    public List<ChangedFile> getPrFiles(String repo, int prNumber) {
        String url = BASE_URL + "/repos/%s/pulls/%d/files".formatted(repo, prNumber);

        ResponseEntity<List<ChangedFile>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(githubHeaders()),
                new ParameterizedTypeReference<>() {}
        );

        List<ChangedFile> files = response.getBody();
        log.info("PR #{} 변경 파일 수: {}", prNumber, files != null ? files.size() : 0);
        return files != null ? files : List.of();
    }

    // 특정 파일 내용 가져오기 (Base64 디코딩)
    public String getFileContent(String repo, String filePath) {
        String url = BASE_URL + "/repos/%s/contents/%s".formatted(repo, filePath);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(githubHeaders()),
                    Map.class
            );

            String encoded = (String) response.getBody().get("content");
            if (encoded == null) return "";

            // GitHub API는 Base64 + 줄바꿈 포함해서 반환
            String cleaned = encoded.replaceAll("\\s", "");
            return new String(Base64.getDecoder().decode(cleaned));

        } catch (Exception e) {
            log.warn("파일 내용 가져오기 실패: {} - {}", filePath, e.getMessage());
            return "";
        }
    }

    // PR에 댓글 달기
    public void postComment(String repo, int prNumber, String body) {
        String url = BASE_URL + "/repos/%s/issues/%d/comments".formatted(repo, prNumber);

        try {
            restTemplate.postForEntity(
                    url,
                    new HttpEntity<>(Map.of("body", body), githubHeaders()),
                    Map.class
            );
            log.info("PR #{} 댓글 등록 완료", prNumber);

        } catch (Exception e) {
            log.error("PR 댓글 등록 실패: #{}", prNumber, e);
        }
    }

    private HttpHeaders githubHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + githubToken);
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        return headers;
    }

    /**
     * GitHub API로 특정 디렉토리의 파일 목록 재귀 조회
     * 새 파일 추가해도 자동으로 인식
     */
    public List<String> getFilesInDirectory(String repo, String dirPath) {
        List<String> result = new ArrayList<>();
        collectFiles(repo, dirPath, result);
        return result;
    }

    private void collectFiles(String repo, String dirPath, List<String> result) {
        String url = BASE_URL + "/repos/%s/contents/%s".formatted(repo, dirPath);

        try {
            ResponseEntity<List<Map>> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(githubHeaders()),
                    new ParameterizedTypeReference<>() {}
            );

            List<Map> items = response.getBody();
            if (items == null) return;

            for (Map item : items) {
                String type = (String) item.get("type");
                String path = (String) item.get("path");

                if ("file".equals(type)) {
                    result.add(path);                    // 파일이면 추가
                } else if ("dir".equals(type)) {
                    collectFiles(repo, path, result);    // 디렉토리면 재귀 탐색
                }
            }
        } catch (Exception e) {
            log.warn("디렉토리 조회 실패: {} - {}", dirPath, e.getMessage());
        }
    }
}
