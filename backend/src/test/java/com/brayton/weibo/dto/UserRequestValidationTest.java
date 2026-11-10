package com.brayton.weibo.dto;

import com.brayton.weibo.validation.R2AvatarUrlValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class UserRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        // 线上由 Spring 注入 r2.publicBaseUrl；这里手动构造带配置的校验器，其余走默认工厂
        ConstraintValidatorFactory defaults = Validation.byDefaultProvider().configure()
                .getDefaultConstraintValidatorFactory();
        factory = Validation.byDefaultProvider().configure()
                .constraintValidatorFactory(new ConstraintValidatorFactory() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                        if (key == R2AvatarUrlValidator.class) {
                            return (T) new R2AvatarUrlValidator("https://pub-abc.r2.dev");
                        }
                        return defaults.getInstance(key);
                    }

                    @Override
                    public void releaseInstance(ConstraintValidator<?, ?> instance) {
                    }
                })
                .buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static RegisterRequest register(String username, String password, String email) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setPassword(password);
        r.setEmail(email);
        return r;
    }

    @Test
    void acceptsValidRegistrationIncludingChineseUsername() {
        assertThat(validator.validate(register("alice_01", "password1", "a@b.com"))).isEmpty();
        assertThat(validator.validate(register("西贡区青瓜一号", "password1", "a@b.com"))).isEmpty();
    }

    @Test
    void rejectsWeakOrEmptyPassword() {
        assertThat(validator.validate(register("alice", "", "a@b.com"))).isNotEmpty();
        assertThat(validator.validate(register("alice", "1234567", "a@b.com"))).isNotEmpty();
        assertThat(validator.validate(register("alice", "x".repeat(73), "a@b.com"))).isNotEmpty();
    }

    @Test
    void rejectsBadUsernames() {
        assertThat(validator.validate(register("a", "password1", "a@b.com"))).isNotEmpty();
        assertThat(validator.validate(register("x".repeat(21), "password1", "a@b.com"))).isNotEmpty();
        assertThat(validator.validate(register("bob smith", "password1", "a@b.com"))).isNotEmpty();
        assertThat(validator.validate(register("<script>", "password1", "a@b.com"))).isNotEmpty();
    }

    @Test
    void rejectsOverlongEmail() {
        String email = "x".repeat(250) + "@b.com";
        assertThat(validator.validate(register("alice", "password1", email))).isNotEmpty();
    }

    @Test
    void updateAllowsNullFieldsAsNoChange() {
        assertThat(validator.validate(new UpdateUserRequest())).isEmpty();
    }

    @Test
    void updateRejectsLongBioExternalAvatarAndFutureBirthday() {
        UpdateUserRequest longBio = new UpdateUserRequest();
        longBio.setBio("x".repeat(161));
        assertThat(validator.validate(longBio)).isNotEmpty();

        UpdateUserRequest externalAvatar = new UpdateUserRequest();
        externalAvatar.setAvatarUrl("https://evil.com/a.png");
        assertThat(validator.validate(externalAvatar)).isNotEmpty();

        UpdateUserRequest future = new UpdateUserRequest();
        future.setBirthday(LocalDate.now().plusDays(1));
        assertThat(validator.validate(future)).isNotEmpty();
    }

    @Test
    void updateAcceptsSiteAvatarAndBioAtLimit() {
        UpdateUserRequest ok = new UpdateUserRequest();
        ok.setBio("x".repeat(160));
        ok.setAvatarUrl("https://pub-abc.r2.dev/avatars/1.png");
        ok.setUsername("新名字");
        assertThat(validator.validate(ok)).isEmpty();
    }
}
