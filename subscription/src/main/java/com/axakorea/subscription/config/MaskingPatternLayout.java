package com.axakorea.subscription.config;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaskingPatternLayout extends PatternLayout {

    // 개인정보 패턴 정의
    private static final List<MaskRule> MASK_RULES = List.of(

            // 전화번호: 010-1234-5678 → 010-****-5678
            new MaskRule(
                    Pattern.compile("(\\d{3})-(\\d{3,4})-(\\d{4})"),
                    "$1-****-$3"
            ),

            // 카드번호: 1234-5678-9012-3456 → 1234-****-****-3456
            new MaskRule(
                    Pattern.compile("(\\d{4})-(\\d{4})-(\\d{4})-(\\d{4})"),
                    "$1-****-****-$4"
            ),

            // 이메일: test@example.com → te**@example.com
            new MaskRule(
                    Pattern.compile("([a-zA-Z0-9._%+-]{2})[a-zA-Z0-9._%+-]+(@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})"),
                    "$1****$2"
            ),

            // 이름 키-값 패턴: name=홍길동 → name=홍**
            new MaskRule(
                    Pattern.compile("(name|이름|고객명|customerName)[=:\\s]+([가-힣a-zA-Z]{1})([가-힣a-zA-Z]+)"),
                    "$1=$2**"
            ),

            // 생년월일: 1990-01-01 → 1990-**-**
            new MaskRule(
                    Pattern.compile("(19|20)(\\d{2})-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])"),
                    "$1$2-**-**"
            ),

            // 주민등록번호: 901231-1234567 → 901231-*******
            new MaskRule(
                    Pattern.compile("(\\d{6})-(\\d{7})"),
                    "$1-*******"
            )
    );

    @Override
    public String doLayout(ILoggingEvent event) {
        String message = super.doLayout(event);
        return maskPersonalInfo(message);
    }

    private String maskPersonalInfo(String message) {
        for (MaskRule rule : MASK_RULES) {
            Matcher matcher = rule.pattern().matcher(message);
            message = matcher.replaceAll(rule.replacement());
        }
        return message;
    }

    // 마스킹 규칙 레코드
    private record MaskRule(Pattern pattern, String replacement) {}
}