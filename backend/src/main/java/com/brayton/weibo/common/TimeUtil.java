package com.brayton.weibo.common;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class TimeUtil {

    public static long toTs(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    public static LocalDateTime fromTs(long ts) {
        return Instant.ofEpochMilli(ts)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
    }
}
