package com.axakorea.subscription.controller;

import com.axakorea.subscription.common.response.ApiResponse;
import com.axakorea.subscription.dto.VehicleRequestDto;
import com.axakorea.subscription.dto.VehicleResponseDto;
import com.axakorea.subscription.service.VehicleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehicleService vehicleService;

    // ── API 1. 차량 신규 등록 ─────────────────────────
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<VehicleResponseDto> register(
            @RequestBody VehicleRequestDto req) {
        return ApiResponse.ok(vehicleService.register(req));
    }

    // ── API 2. 내 차량 목록 조회 ──────────────────────
    @GetMapping("/myList")
    public ApiResponse<List<VehicleResponseDto>> getMyVehicles(
            @RequestParam String phone) {
        return ApiResponse.ok(vehicleService.getMyVehicles(phone));
    }
}

