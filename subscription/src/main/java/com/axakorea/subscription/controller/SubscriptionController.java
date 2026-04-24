package com.axakorea.subscription.controller;

import com.axakorea.subscription.common.response.ApiResponse;
import com.axakorea.subscription.dto.SubscriptionRequestDto;
import com.axakorea.subscription.dto.SubscriptionResponseDto;
import com.axakorea.subscription.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "AXA 청약 API")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    // POST /api/subscriptions  ← 청약 저장
    @PostMapping
    @Operation(summary = "청약 계약 저장")
    public ResponseEntity<ApiResponse<SubscriptionResponseDto>> create(
            @RequestBody @Valid SubscriptionRequestDto request) {

        SubscriptionResponseDto response = subscriptionService.create(request);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response));
    }

    // GET /api/subscriptions/my?phone=010-1234-5678  ← 내 청약 조회
    @GetMapping("/my")
    @Operation(summary = "내 청약 목록 조회")
    public ResponseEntity<ApiResponse<List<SubscriptionResponseDto>>> getMy(
            @RequestParam String phone) {

        return ResponseEntity.ok(ApiResponse.ok(subscriptionService.getMyList(phone)));
    }
}