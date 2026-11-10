package com.brayton.weibo.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class R2AvatarUrlValidatorTest {

    private final R2AvatarUrlValidator validator = new R2AvatarUrlValidator("https://pub-abc.r2.dev");

    @Test
    void acceptsEmptyAndSiteUploads() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("https://pub-abc.r2.dev/avatars/1.png", null)).isTrue();
    }

    @Test
    void rejectsExternalAndLookalikeHosts() {
        assertThat(validator.isValid("https://evil.com/a.png", null)).isFalse();
        // 只比较前缀不带 "/" 的话，这种子域名伪装会通过
        assertThat(validator.isValid("https://pub-abc.r2.dev.evil.com/a.png", null)).isFalse();
        assertThat(validator.isValid("http://pub-abc.r2.dev/a.png", null)).isFalse();
        assertThat(validator.isValid("https://pub-abc.r2.dev/", null)).isFalse();
        assertThat(validator.isValid("javascript:alert(1)", null)).isFalse();
    }

    @Test
    void toleratesTrailingSlashInConfig() {
        R2AvatarUrlValidator v = new R2AvatarUrlValidator("https://pub-abc.r2.dev/");
        assertThat(v.isValid("https://pub-abc.r2.dev/a.png", null)).isTrue();
    }
}
