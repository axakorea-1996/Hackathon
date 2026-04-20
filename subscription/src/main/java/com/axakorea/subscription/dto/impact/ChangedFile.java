package com.axakorea.subscription.dto.impact;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ChangedFile {

    private String filename;    // 파일 경로 (예: src/main/java/.../Service.java)
    private int    additions;   // 추가된 줄 수
    private int    deletions;   // 삭제된 줄 수
    private int    changes;     // 총 변경 줄 수
    private String status;      // added / modified / removed / renamed
    private String patch;       // 실제 diff 내용 (+/- 포함)
}
