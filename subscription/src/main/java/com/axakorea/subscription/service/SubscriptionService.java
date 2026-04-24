package com.axakorea.subscription.service;

import com.axakorea.subscription.domain.Customer;
import com.axakorea.subscription.domain.Subscription;
import com.axakorea.subscription.domain.Vehicle;
import com.axakorea.subscription.dto.SubscriptionRequestDto;
import com.axakorea.subscription.dto.SubscriptionResponseDto;
import com.axakorea.subscription.exception.NotFoundException;
import com.axakorea.subscription.repository.CustomerRepository;
import com.axakorea.subscription.repository.SubscriptionRepository;
import com.axakorea.subscription.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SubscriptionService {

    private final CustomerRepository     customerRepo;
    private final VehicleRepository      vehicleRepo;
    private final SubscriptionRepository subRepo;

    // ── API 1. 청약 저장!!! ─────────────────────────────
    public SubscriptionResponseDto create(SubscriptionRequestDto req) {

        // 1) 고객 조회 or 신규 생성 (전화번호 기준)
        Customer customer = customerRepo.findByPhone(req.getCustomerPhone())
                .orElseGet(() -> customerRepo.save(Customer.builder()
                        .name(req.getCustomerName())
                        .phone(req.getCustomerPhone())
                        .email(req.getCustomerEmail())
                        .birthDate(req.getBirthDate())
                        .build()));

        // 2) 차량 조회 or 신규 등록 (차량번호 기준)
        Vehicle vehicle = vehicleRepo.findByPlateNumber(req.getPlateNumber())
                .orElseGet(() -> vehicleRepo.save(Vehicle.builder()
                        .customer(customer)
                        .plateNumber(req.getPlateNumber())
                        .modelName(req.getModelName())
                        .grade(req.getGrade())
                        .driveType(req.getDriveType())
                        .build()));

        // 3) 증권번호 자동 생성
        String policyNo = "AXA-" + LocalDate.now().getYear()
                + "-" + String.format("%06d", (long)(Math.random() * 999999));

        // 4) 청약 저장
        Subscription subscription = subRepo.save(Subscription.builder()
                .customer(customer)
                .vehicle(vehicle)
                .policyNo(policyNo)
                .driverScope(req.getDriverScope())
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .premium(req.getPremium())
                .payMethod(req.getPayMethod())
                .status(Subscription.SubscriptionStatus.ACTIVE)
                .specialMileage(Boolean.TRUE.equals(req.getSpecialMileage()))
                .specialBlackbox(Boolean.TRUE.equals(req.getSpecialBlackbox()))
                .specialChild(Boolean.TRUE.equals(req.getSpecialChild()))
                .build());

        log.info("청약 저장 완료: {}", policyNo);

        return SubscriptionResponseDto.from(subscription);
    }

    // ── API 2. 내 청약 조회 ──────────────────────────
    @Transactional(readOnly = true)
    public List<SubscriptionResponseDto> getMyList(String phone) {
        customerRepo.findByPhone(phone)
                .orElseThrow(() -> new NotFoundException("고객을 찾을 수 없습니다: " + phone));

        return subRepo.findByCustomerPhone(phone)
                .stream()
                .map(SubscriptionResponseDto::from)
                .toList();
    }

    // ── API 3. 차량 신규 등록 ────────────────────────────
    public String registerVehicle(String phone, String plateNumber, String modelName) {

        if (plateNumber == null || plateNumber.isBlank()) {
        throw new IllegalArgumentException("차량번호는 필수입니다");
        }

        Customer customer = customerRepo.findByPhone(phone)
                .orElseThrow(() -> new NotFoundException("고객을 찾을 수 없습니다: " + phone));

        Vehicle vehicle = vehicleRepo.save(Vehicle.builder()
                .customer(customer)
                .plateNumber(plateNumber)
                .modelName(modelName)
                .build());

        return vehicle.getPlateNumber() + " 등록 완료";
}
}