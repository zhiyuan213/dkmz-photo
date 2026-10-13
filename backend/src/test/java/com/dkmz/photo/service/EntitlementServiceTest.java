package com.dkmz.photo.service;

import com.dkmz.photo.entity.PhotoEntitlement;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitlementServiceTest {

    @Test
    void membershipIsActiveOnlyBeforeExactExpiry() {
        PhotoEntitlement entitlement = new PhotoEntitlement();
        LocalDateTime expiry = LocalDateTime.of(2027, 10, 13, 12, 30);
        entitlement.setVipExpireAt(expiry);

        assertTrue(EntitlementService.vipActiveAt(entitlement, expiry.minusNanos(1)));
        assertFalse(EntitlementService.vipActiveAt(entitlement, expiry));
        assertFalse(EntitlementService.vipActiveAt(entitlement, expiry.plusSeconds(1)));
    }

    @Test
    void annualMembershipUsesCalendarYearsAcrossLeapYear() {
        String add = EntitlementService.shiftVipSql("NOW()", 365, true);
        String renewTwoYears = EntitlementService.shiftVipSql("vip_expire_at", 730, true);
        String refund = EntitlementService.shiftVipSql("vip_expire_at", 365, false);

        assertTrue(add.contains("INTERVAL 1 YEAR"));
        assertTrue(renewTwoYears.contains("INTERVAL 2 YEAR"));
        assertTrue(refund.contains("INTERVAL 1 YEAR"));
    }

    @Test
    void nonAnnualMembershipStillUsesConfiguredDays() {
        String add = EntitlementService.shiftVipSql("NOW()", 30, true);
        assertTrue(add.contains("INTERVAL 30 DAY"));
    }
}
