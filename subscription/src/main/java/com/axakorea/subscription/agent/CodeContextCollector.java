package com.axakorea.subscription.agent;

import com.axakorea.subscription.client.GitHubClient;
import com.axakorea.subscription.dto.impact.ChangedFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CodeContextCollector {

    private final GitHubClient gitHubClient;

    /**
     * GitHub API로 레포의 실제 파일 목록 자동 수집
     * 하드코딩 없이 새 파일 추가해도 자동으로 인식
     */
    public String collectRelatedContext(String repo, List<ChangedFile> changedFiles) {
        StringBuilder context = new StringBuilder();
        Set<String> visited = new HashSet<>();

        for (ChangedFile changed : changedFiles) {
            String base = extractBasePath(changed.getFilename());

            // GitHub API로 실제 파일 목록 가져오기
            List<String> allFiles = gitHubClient.getFilesInDirectory(repo, base);

            for (String path : allFiles) {
                if (changed.getFilename().equals(path)) continue;
                if (visited.contains(path)) continue;
                if (!path.endsWith(".java")) continue;  // java 파일만
                visited.add(path);

                String content = gitHubClient.getFileContent(repo, path);
                if (!content.isBlank()) {
                    context.append("\n\n=== 기존 파일: ").append(path).append(" ===\n");
                    context.append(content);
                    log.debug("기존 파일 수집 완료: {}", path);
                }
            }
        }
        return context.toString();
    }

    /**
     * 변경 파일 경로에서 base 자동 추출
     * 예) subscription/src/main/java/com/axakorea/subscription/service/XXX.java
     *  →  subscription/src/main/java/com/axakorea/subscription/
     */
    private String extractBasePath(String changedFile) {
        for (String layer : List.of(
                "controller/", "service/", "repository/",
                "domain/", "dto/", "agent/", "client/", "webhook/")) {
            int idx = changedFile.indexOf(layer);
            if (idx > 0) {
                return changedFile.substring(0, idx);
            }
        }
        return "subscription/src/main/java/com/axakorea/subscription/";
    }
}