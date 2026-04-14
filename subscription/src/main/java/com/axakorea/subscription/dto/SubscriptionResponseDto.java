package com.axakorea.subscription.dto;

import com.axakorea.subscription.domain.Subscription;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class SubscriptionResponseDto {

    private Long          subscriptionId;
    private String        policyNo;
    private String        customerName;
    private String        customerPhone;
    private String        vehicleModel;
    private String        plateNumber;
    private String        driverScope;
    private LocalDate     startDate;
    private LocalDate     endDate;
    private Long          premium;
    private String        payMethod;
    private String        status;
    private Boolean       specialMileage;
    private Boolean       specialBlackbox;
    private Boolean       specialChild;
    private LocalDateTime createdAt;

    public static SubscriptionResponseDto from(Subscription s) {
        return SubscriptionResponseDto.builder()
                .subscriptionId(s.getId())
                .policyNo(s.getPolicyNo())
                .customerName(s.getCustomer().getName())
                .customerPhone(s.getCustomer().getPhone())
                .vehicleModel(s.getVehicle().getModelName())
                .plateNumber(s.getVehicle().getPlateNumber())
                .driverScope(s.getDriverScope())
                .startDate(s.getStartDate())
                .endDate(s.getEndDate())
                .premium(s.getPremium())
                .payMethod(s.getPayMethod())
                .status(s.getStatus().name())
                .specialMileage(s.getSpecialMileage())
                .specialBlackbox(s.getSpecialBlackbox())
                .specialChild(s.getSpecialChild())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
