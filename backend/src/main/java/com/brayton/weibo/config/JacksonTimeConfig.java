package com.brayton.weibo.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 实体里的时间都是 {@link LocalDateTime}（JVM 默认时区下的墙上时间，容器里是 UTC），
 * 默认序列化不带时区，浏览器 {@code new Date(...)} 会把它当成本地时间，导致显示偏移。
 * 这里统一在输出时补上偏移（如 {@code 2026-11-08T06:20:35Z}），前端即可按当地时区显示。
 * 反序列化同时兼容带偏移和不带偏移（Redis 里的旧缓存）两种格式。
 */
@Configuration
public class JacksonTimeConfig {

    @Bean
    public Module localDateTimeWithOffsetModule() {
        SimpleModule module = new SimpleModule("LocalDateTimeWithOffset");
        module.addSerializer(LocalDateTime.class, new JsonSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.atZone(ZoneId.systemDefault())
                        .toOffsetDateTime()
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            }
        });
        module.addDeserializer(LocalDateTime.class, new JsonDeserializer<>() {
            @Override
            public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String text = p.getValueAsString();
                if (text == null || text.isBlank()) {
                    return null;
                }
                try {
                    return OffsetDateTime.parse(text)
                            .atZoneSameInstant(ZoneId.systemDefault())
                            .toLocalDateTime();
                } catch (DateTimeParseException e) {
                    return LocalDateTime.parse(text);
                }
            }
        });
        return module;
    }
}
