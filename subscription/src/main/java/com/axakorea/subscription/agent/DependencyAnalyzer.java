package com.axakorea.subscription.agent;

import com.axakorea.subscription.dto.impact.ChangedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DependencyAnalyzer {

    /**
     * MVC 계층 구조 기반 의존성 트리 생성
     * 변경된 파일이 어느 계층에 속하는지 파악하고
     * 위/아래로 영향을 받는 파일들을 트리로 표현
     */
    public String analyze(List<ChangedFile> changedFiles) {
        StringBuilder tree = new StringBuilder();
        tree.append("```\n");
        tree.append("MVC 의존성 트리\n");
        tree.append("(변경된 파일: ★)\n\n");

        // 계층별 파일 분류
        Map<String, List<String>> layerMap = classifyByLayer(changedFiles);

        // 트리 출력
        tree.append("Controller 계층\n");
        appendLayer(tree, layerMap.get("controller"), "  ");

        tree.append("    ↓ 호출\n");
        tree.append("Service 계층\n");
        appendLayer(tree, layerMap.get("service"), "  ");

        tree.append("    ↓ 호출\n");
        tree.append("Repository 계층\n");
        appendLayer(tree, layerMap.get("repository"), "  ");

        tree.append("    ↓ 매핑\n");
        tree.append("Domain 계층\n");
        appendLayer(tree, layerMap.get("domain"), "  ");

        tree.append("\nDTO\n");
        appendLayer(tree, layerMap.get("dto"), "  ");

        tree.append("```\n");

        // 영향도 요약
        tree.append("\n**변경 계층별 영향 범위:**\n");
        for (ChangedFile f : changedFiles) {
            String layer = detectLayer(f.getFilename());
            String impact = getImpactDescription(layer);
            tree.append(String.format("- `%s` (%s) → %s\n",
                    f.getFilename(), layer, impact));
        }

        return tree.toString();
    }

    private Map<String, List<String>> classifyByLayer(List<ChangedFile> changedFiles) {
        Map<String, List<String>> map = new HashMap<>();

        String[] layers = {"controller", "service", "repository", "domain", "dto"};
        for (String layer : layers) {
            final String l = layer;
            List<String> files = changedFiles.stream()
                    .filter(f -> f.getFilename().toLowerCase().contains(l))
                    .map(f -> "★ " + extractSimpleName(f.getFilename()))
                    .collect(Collectors.toList());

            // 변경 없는 계층도 기본 파일 표시
            if (files.isEmpty()) {
                files = getDefaultFilesForLayer(layer);
            }
            map.put(layer, files);
        }
        return map;
    }

    private void appendLayer(StringBuilder tree, List<String> files, String indent) {
        if (files == null || files.isEmpty()) {
            tree.append(indent).append("(해당 없음)\n");
            return;
        }
        for (String file : files) {
            tree.append(indent).append("└─ ").append(file).append("\n");
        }
    }

    private String detectLayer(String filename) {
        if (filename.contains("Controller")) return "Controller";
        if (filename.contains("Service"))    return "Service";
        if (filename.contains("Repository")) return "Repository";
        if (filename.contains("domain/"))    return "Domain";
        if (filename.contains("dto/"))       return "DTO";
        return "기타";
    }

    private String getImpactDescription(String layer) {
        return switch (layer) {
            case "Controller" -> "API 응답 형식 변경 → 프론트엔드 영향 가능";
            case "Service"    -> "비즈니스 로직 변경 → Controller/Repository 양방향 영향";
            case "Repository" -> "쿼리 변경 → Service 로직, DB 성능 영향";
            case "Domain"     -> "엔티티 변경 → DB 스키마, 전체 계층 영향";
            case "DTO"        -> "요청/응답 형식 변경 → Controller/Service 영향";
            default           -> "영향 범위 확인 필요";
        };
    }

    private String extractSimpleName(String fullPath) {
        String[] parts = fullPath.split("/");
        return parts[parts.length - 1];
    }

    private List<String> getDefaultFilesForLayer(String layer) {
        return switch (layer) {
            case "controller"  -> List.of("SubscriptionController.java");
            case "service"     -> List.of("SubscriptionService.java");
            case "repository"  -> List.of("SubscriptionRepository.java",
                                          "CustomerRepository.java",
                                          "VehicleRepository.java");
            case "domain"      -> List.of("Subscription.java",
                                          "Customer.java",
                                          "Vehicle.java");
            case "dto"         -> List.of("SubscriptionRequestDto.java",
                                          "SubscriptionResponseDto.java");
            default            -> List.of();
        };
    }
}
