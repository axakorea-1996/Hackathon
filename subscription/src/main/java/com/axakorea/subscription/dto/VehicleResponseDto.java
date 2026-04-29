package com.axakorea.subscription.dto;

import com.axakorea.subscription.domain.Vehicle;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class VehicleResponseDto {

    private Long   vehicleId;
    private String plateNumber;
    private String modelName;
    private String grade;
    private String driveType;
    private String customerName;
    private String customerPhone;

    public static VehicleResponseDto from(Vehicle v) {
        return VehicleResponseDto.builder()
                .vehicleId(v.getId())
                .plateNumber(v.getPlateNumber())
                .modelName(v.getModelName())
                .grade(v.getGrade())
                .driveType(v.getDriveType())
                .customerName(v.getCustomer().getName())
                .customerPhone(v.getCustomer().getPhone())
                .build();
    }
}