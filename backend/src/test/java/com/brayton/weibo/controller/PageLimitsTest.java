package com.brayton.weibo.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageLimitsTest {

    @Test
    void keepsSizeWithinLimit() {
        assertThat(PageLimits.clamp(10)).isEqualTo(10);
        assertThat(PageLimits.clamp(20)).isEqualTo(20);
    }

    @Test
    void truncatesOversizedRequests() {
        assertThat(PageLimits.clamp(21)).isEqualTo(20);
        assertThat(PageLimits.clamp(100_000)).isEqualTo(20);
        assertThat(PageLimits.clamp(Integer.MAX_VALUE)).isEqualTo(20);
    }

    @Test
    void treatsNonPositiveAsOne() {
        assertThat(PageLimits.clamp(0)).isEqualTo(1);
        assertThat(PageLimits.clamp(-5)).isEqualTo(1);
        assertThat(PageLimits.clamp(Integer.MIN_VALUE)).isEqualTo(1);
    }
}
