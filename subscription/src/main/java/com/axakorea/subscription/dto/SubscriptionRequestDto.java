package com.axakorea.subscription.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
    @Pattern(regexp = "^\\d{3}-\\d{3,4}-\\d{4}$", message = "전화번호 형식이 올바르지 않습니다")
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

    // ── 마스킹 메서드 ─────────────────────────────────
    private String maskName(String name) {
        if (name == null || name.isEmpty()) return null;
        return name.charAt(0) + "**";
    }

    private String maskPhone(String phone) {
        if (phone == null) return null;
        return phone.replaceAll("(\\d{3})-(\\d{3,4})-(\\d{4})", "$1-****-$3");
    }

    private String maskEmail(String email) {
        if (email == null) return null;
        return email.replaceAll(
                "([a-zA-Z0-9._%+-]{2})[a-zA-Z0-9._%+-]+(@[a-zA-Z0-9.-]+)",
                "$1****$2"
        );
    }

    private String maskBirthDate(LocalDate date) {
        if (date == null) return null;
        return date.getYear() + "-**-**";
    }

    // ── toString 개인정보 마스킹 적용 ─────────────────
    // Lombok @ToString 대신 직접 구현
    @Override
    public String toString() {
        return "SubscriptionRequestDto{" +
                "customerName='"  + maskName(customerName)       + "'" +
                ", customerPhone='" + maskPhone(customerPhone)   + "'" +
                ", customerEmail='" + maskEmail(customerEmail)   + "'" +
                ", birthDate='"     + maskBirthDate(birthDate)   + "'" +
                ", plateNumber='"   + plateNumber                + "'" +
                ", modelName='"     + modelName                  + "'" +
                ", grade='"         + grade                      + "'" +
                ", driveType='"     + driveType                  + "'" +
                ", driverScope='"   + driverScope                + "'" +
                ", startDate='"     + startDate                  + "'" +
                ", endDate='"       + endDate                    + "'" +
                ", premium="        + premium                    +
                ", payMethod='"     + payMethod                  + "'" +
                ", specialMileage=" + specialMileage             +
                ", specialBlackbox=" + specialBlackbox           +
                ", specialChild="   + specialChild               +
                "}";
    }
}