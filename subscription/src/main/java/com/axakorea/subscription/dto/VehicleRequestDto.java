package com.axakorea.subscription.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class VehicleRequestDto {

    private String customerPhone;   // 고객 전화번호 (고객 조회용)
    private String plateNumber;     // 차량번호
    private String modelName;       // 차량 모델명
    private String grade;           // 등급 (프리미엄 등)
    private String driveType;       // 운행형태 (자가용, 출퇴근 등)
}