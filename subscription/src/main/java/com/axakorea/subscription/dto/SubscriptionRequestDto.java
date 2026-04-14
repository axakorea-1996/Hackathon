package com.axakorea.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionRequestDto {

    // 고객 정보
    @NotBlank(message = "고객명을 입력해주세요")
    private String customerName;

    @NotBlank(message = "전화번호를 입력해주세요")
    private String customerPhone;

    private String    customerEmail;
    private LocalDate birthDate;

    // 차량 정보
    @NotBlank(message = "차량번호를 입력해주세요")
    private String plateNumber;

    @NotBlank(message = "차량모델을 입력해주세요")
    private String modelName;

    private String grade;
    private String driveType;

    // 청약 정보
    @NotBlank(message = "운전자 범위를 선택해주세요")
    private String driverScope;

    private LocalDate startDate;
    private LocalDate endDate;

    @NotNull(message = "보험료를 입력해주세요")
    private Long premium;

    @NotBlank(message = "결제수단을 선택해주세요")
    private String payMethod;

    // 특약
    @Builder.Default private Boolean specialMileage  = false;
    @Builder.Default private Boolean specialBlackbox = false;
    @Builder.Default private Boolean specialChild    = false;
}
