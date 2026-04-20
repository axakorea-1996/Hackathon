package com.axakorea.subscription.dto.impact;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ImpactAnalysisResult {

    private String            prTitle;          // PR 제목
    private String            repo;             // 레포지토리명
    private int               prNumber;         // PR 번호
    private List<String>      changedFiles;     // 변경된 파일 목록
    private List<String>      affectedFiles;    // 영향받는 기존 파일 목록
    private RiskLevel         riskLevel;        // 전체 위험도
    private String            aiAnalysis;       // AI 분석 결과 (마크다운)
    private LocalDateTime     analyzedAt;       // 분석 시각

    public enum RiskLevel {
        HIGH,    // 🔴 즉시 수정 필요
        MEDIUM,  // 🟡 주의 필요
        LOW      // 🟢 merge 가능
    }
}
