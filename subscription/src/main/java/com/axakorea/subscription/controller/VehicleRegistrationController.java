package com.axakorea.subscription.controller;

import com.axakorea.subscription.domain.Vehicle;
import com.axakorea.subscription.service.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/vehicles")
public class VehicleRegistrationController {

    private final SubscriptionService subscriptionService;

    @PostMapping
    public Vehicle register(@RequestParam String phone,
                            @RequestParam String plateNumber,
                            @RequestParam String modelName) {
        return subscriptionService.registerVehicle(phone, plateNumber, modelName);
    }
}