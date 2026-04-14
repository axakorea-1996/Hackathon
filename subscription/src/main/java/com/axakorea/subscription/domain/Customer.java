package com.axakorea.subscription.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "customers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;                    // 고객명

    @Column(nullable = false, length = 20, unique = true)
    private String phone;                   // 전화번호 (식별 키)

    @Column(length = 100)
    private String email;

    private LocalDate birthDate;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
