package com.brayton.weibo.common;

import java.time.LocalDateTime;
import java.time.ZoneId;

public class TimeUtil {

    public static long toTs(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}
