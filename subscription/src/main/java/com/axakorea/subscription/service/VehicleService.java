package com.axakorea.subscription.service;

import com.axakorea.subscription.domain.Customer;
import com.axakorea.subscription.domain.Vehicle;
import com.axakorea.subscription.dto.VehicleRequestDto;
import com.axakorea.subscription.dto.VehicleResponseDto;
import com.axakorea.subscription.exception.NotFoundException;
import com.axakorea.subscription.repository.CustomerRepository;
import com.axakorea.subscription.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class VehicleService {

    private final VehicleRepository  vehicleRepo;
    private final CustomerRepository customerRepo;

    // ── API 1. 차량 신규 등록 ─────────────────────────
    public VehicleResponseDto register(VehicleRequestDto req) {

        // 1) 고객 조회
        Customer customer = customerRepo.findByPhone(req.getCustomerPhone())
                .orElseThrow(() -> new NotFoundException(
                        "고객을 찾을 수 없습니다: " + req.getCustomerPhone()));

        // 2) 중복 차량 체크
        if (vehicleRepo.findByPlateNumber(req.getPlateNumber()).isPresent()) {
            throw new IllegalArgumentException(
                    "이미 등록된 차량번호입니다: " + req.getPlateNumber());
        }

        // 3) 차량 저장
        Vehicle vehicle = vehicleRepo.save(Vehicle.builder()
                .customer(customer)
                .plateNumber(req.getPlateNumber())
                .modelName(req.getModelName())
                .grade(req.getGrade())
                .driveType(req.getDriveType())
                .build());

        log.info("차량 신규 등록 완료: {} - {}", req.getCustomerPhone(), vehicle.getPlateNumber());

        return VehicleResponseDto.from(vehicle);
    }

    // ── API 2. 내 차량 목록 조회 ──────────────────────
    @Transactional(readOnly = true)
    public List<VehicleResponseDto> getMyVehicles(String phone) {
        customerRepo.findByPhone(phone)
                .orElseThrow(() -> new NotFoundException(
                        "고객을 찾을 수 없습니다: " + phone));

        return vehicleRepo.findByCustomerPhone(phone)
                .stream()
                .map(VehicleResponseDto::from)
                .toList();
    }
}