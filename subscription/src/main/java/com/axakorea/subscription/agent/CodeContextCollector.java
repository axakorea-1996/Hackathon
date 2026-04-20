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

    public String collectRelatedContext(String repo, List<ChangedFile> changedFiles) {
        StringBuilder context = new StringBuilder();
        Set<String> visited = new HashSet<>();

        for (ChangedFile changed : changedFiles) {
            List<String> relatedPaths = inferRelatedFiles(changed.getFilename());

            for (String path : relatedPaths) {
                if (changed.getFilename().equals(path)) continue;
                if (visited.contains(path)) continue;
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

    private List<String> inferRelatedFiles(String changedFile) {
        String base = extractBasePath(changedFile);

        if (changedFile.contains("Controller")) {
            return List.of(
                    base + "service/SubscriptionService.java",
                    base + "dto/SubscriptionRequestDto.java",
                    base + "dto/SubscriptionResponseDto.java",
                    base + "common/response/ApiResponse.java"
            );
        }
        if (changedFile.contains("Service")) {
            return List.of(
                    base + "controller/SubscriptionController.java",
                    base + "repository/SubscriptionRepository.java",
                    base + "repository/CustomerRepository.java",
                    base + "domain/Subscription.java",
                    base + "domain/Customer.java",
                    base + "dto/SubscriptionRequestDto.java",
                    base + "dto/SubscriptionResponseDto.java"
            );
        }
        if (changedFile.contains("Repository")) {
            return List.of(
                    base + "service/SubscriptionService.java",
                    base + "domain/Subscription.java",
                    base + "domain/Customer.java",
                    base + "domain/Vehicle.java"
            );
        }
        if (changedFile.contains("domain/")) {
            return List.of(
                    base + "service/SubscriptionService.java",
                    base + "repository/SubscriptionRepository.java"
            );
        }
        if (changedFile.contains("dto/")) {
            return List.of(
                    base + "controller/SubscriptionController.java",
                    base + "service/SubscriptionService.java"
            );
        }
        return List.of(
                base + "controller/SubscriptionController.java",
                base + "service/SubscriptionService.java",
                base + "repository/SubscriptionRepository.java"
        );
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