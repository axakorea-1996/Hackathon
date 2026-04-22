package com.axakorea.subscription.agent;

import com.axakorea.subscription.dto.impact.ChangedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DependencyAnalyzer {

    /**
     * MVC 계층 구조 기반 의존성 트리 + Mermaid 다이어그램 생성
     * 변경된 파일 기준으로 연관 파일들과의 영향 관계를 시각화
     */
    public String analyze(List<ChangedFile> changedFiles) {
        StringBuilder result = new StringBuilder();

        // 1. Mermaid 다이어그램 (변경 파일 ↔ 연관 파일 관계)
        result.append(buildMermaidDiagram(changedFiles));
        result.append("\n\n");

        // 2. 텍스트 의존성 트리
        result.append(buildDependencyTree(changedFiles));

        return result.toString();
    }

    // ═══════════════════════════════════════════
    //  Mermaid 다이어그램 생성
    // ═══════════════════════════════════════════
    private String buildMermaidDiagram(List<ChangedFile> changedFiles) {
        StringBuilder diagram = new StringBuilder();
        diagram.append("```mermaid\n");
        diagram.append("flowchart TD\n");

        Set<String> addedNodes  = new LinkedHashSet<>();
        Set<String> addedEdges  = new LinkedHashSet<>();
        List<String> changedIds = new ArrayList<>();

        // 변경된 파일 노드 등록
        for (ChangedFile f : changedFiles) {
            String id    = toNodeId(f.getFilename());
            String label = extractSimpleName(f.getFilename());
            String layer = detectLayer(f.getFilename());

            addedNodes.add(String.format("    %s[\"%s\\n(%s)\"]", id, label, layer));
            changedIds.add(id);
        }

        // 변경된 파일 기준으로 연관 파일 + 엣지 추가
        for (ChangedFile f : changedFiles) {
            String fromId = toNodeId(f.getFilename());
            String layer  = detectLayer(f.getFilename());

            List<RelatedNode> relatedNodes = getRelatedNodes(layer);

            for (RelatedNode related : relatedNodes) {
                String toId = toNodeId(related.filename);

                // 연관 노드 추가 (중복 방지)
                if (!addedNodes.stream().anyMatch(n -> n.contains(toId + "["))) {
                    addedNodes.add(String.format("    %s[\"%s\\n(%s)\"]",
                            toId, related.simpleName, related.layer));
                }

                // 엣지 추가 (중복 방지)
                String edge = String.format("    %s -->|%s| %s", fromId, related.relation, toId);
                if (!addedEdges.contains(edge)) {
                    addedEdges.add(edge);
                }
            }

            // DB 노드 연결 (Repository, Domain 계층)
            if (layer.equals("Repository") || layer.equals("Domain")) {
                String dbId   = "DB[(Database)]";
                String dbEdge = String.format("    %s -->|쿼리 실행| DB", fromId);
                if (!addedEdges.contains(dbEdge)) {
                    addedNodes.add("    DB[(Database)]");
                    addedEdges.add(dbEdge);
                }
            }
        }

        // 노드 출력
        addedNodes.forEach(n -> diagram.append(n).append("\n"));
        diagram.append("\n");

        // 엣지 출력
        addedEdges.forEach(e -> diagram.append(e).append("\n"));
        diagram.append("\n");

        // 스타일: 변경된 파일 빨간색, 연관 파일 노란색, DB 회색
        diagram.append("    %% 변경된 파일 — 빨간색\n");
        for (String id : changedIds) {
            diagram.append(String.format("    style %s fill:#FF6B6B,color:#000,stroke:#CC0000\n", id));
        }
        diagram.append("    %% 영향받는 파일 — 노란색\n");
        for (String node : addedNodes) {
            String nodeId = node.trim().split("\\[")[0];
            if (!changedIds.contains(nodeId) && !nodeId.equals("DB")) {
                diagram.append(String.format("    style %s fill:#FFE66D,color:#000,stroke:#CCA800\n", nodeId));
            }
        }
        diagram.append("    %% DB — 회색\n");
        diagram.append("    style DB fill:#CCCCCC,color:#000,stroke:#999999\n");

        diagram.append("```");
        return diagram.toString();
    }

    // ═══════════════════════════════════════════
    //  텍스트 의존성 트리
    // ═══════════════════════════════════════════
    private String buildDependencyTree(List<ChangedFile> changedFiles) {
        StringBuilder tree = new StringBuilder();
        tree.append("```\n");
        tree.append("MVC 의존성 트리  (★ = 변경된 파일)\n\n");

        Map<String, List<String>> layerMap = classifyByLayer(changedFiles);

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

        tree.append("\n**변경 계층별 영향 범위:**\n");
        for (ChangedFile f : changedFiles) {
            String layer  = detectLayer(f.getFilename());
            String impact = getImpactDescription(layer);
            tree.append(String.format("- `%s` (%s) → %s\n",
                    f.getFilename(), layer, impact));
        }

        return tree.toString();
    }

    // ═══════════════════════════════════════════
    //  연관 노드 정의 (계층별)
    // ═══════════════════════════════════════════
    private List<RelatedNode> getRelatedNodes(String layer) {
        return switch (layer) {
            case "Controller" -> List.of(
                    new RelatedNode("SubscriptionService",    "service",    "호출"),
                    new RelatedNode("SubscriptionRequestDto", "dto",        "파라미터"),
                    new RelatedNode("SubscriptionResponseDto","dto",        "응답"),
                    new RelatedNode("ApiResponse",            "common",     "래핑")
            );
            case "Service" -> List.of(
                    new RelatedNode("SubscriptionController", "controller", "응답 반환"),
                    new RelatedNode("SubscriptionRepository", "repository", "데이터 조회"),
                    new RelatedNode("CustomerRepository",     "repository", "데이터 조회"),
                    new RelatedNode("Subscription",           "domain",     "엔티티 사용"),
                    new RelatedNode("Customer",               "domain",     "엔티티 사용")
            );
            case "Repository" -> List.of(
                    new RelatedNode("SubscriptionService",    "service",    "호출됨"),
                    new RelatedNode("Subscription",           "domain",     "엔티티 매핑"),
                    new RelatedNode("Customer",               "domain",     "엔티티 매핑")
            );
            case "Domain" -> List.of(
                    new RelatedNode("SubscriptionRepository", "repository", "매핑됨"),
                    new RelatedNode("SubscriptionService",    "service",    "사용됨")
            );
            case "DTO" -> List.of(
                    new RelatedNode("SubscriptionController", "controller", "사용됨"),
                    new RelatedNode("SubscriptionService",    "service",    "변환됨")
            );
            default -> List.of(
                    new RelatedNode("SubscriptionController", "controller", "관련"),
                    new RelatedNode("SubscriptionService",    "service",    "관련")
            );
        };
    }

    // ═══════════════════════════════════════════
    //  유틸
    // ═══════════════════════════════════════════
    private String toNodeId(String filename) {
        // 파일명에서 노드 ID 생성 (특수문자 제거)
        return extractSimpleName(filename)
                .replace(".java", "")
                .replace("-", "_")
                .replace(" ", "_");
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

    private Map<String, List<String>> classifyByLayer(List<ChangedFile> changedFiles) {
        Map<String, List<String>> map = new HashMap<>();
        String[] layers = {"controller", "service", "repository", "domain", "dto"};

        for (String layer : layers) {
            List<String> files = changedFiles.stream()
                    .filter(f -> f.getFilename().toLowerCase().contains(layer))
                    .map(f -> "★ " + extractSimpleName(f.getFilename()))
                    .collect(Collectors.toList());
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

    private List<String> getDefaultFilesForLayer(String layer) {
        return switch (layer) {
            case "controller" -> List.of("SubscriptionController.java");
            case "service"    -> List.of("SubscriptionService.java");
            case "repository" -> List.of("SubscriptionRepository.java",
                    "CustomerRepository.java",
                    "VehicleRepository.java");
            case "domain"     -> List.of("Subscription.java", "Customer.java", "Vehicle.java");
            case "dto"        -> List.of("SubscriptionRequestDto.java",
                    "SubscriptionResponseDto.java");
            default           -> List.of();
        };
    }

    // ═══════════════════════════════════════════
    //  내부 클래스
    // ═══════════════════════════════════════════
    private static class RelatedNode {
        final String filename;
        final String simpleName;
        final String layer;
        final String relation;

        RelatedNode(String simpleName, String layer, String relation) {
            this.filename   = simpleName;
            this.simpleName = simpleName;
            this.layer      = layer;
            this.relation   = relation;
        }
    }
}