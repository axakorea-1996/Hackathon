package com.axakorea.subscription.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor  // ✅ Builder와 함께 필요
public class VehicleRequestDto {

    private String customerPhone;
    private String plateNumber;
    private String modelName;
    private String grade;
    private String driveType;
}