package com.brayton.weibo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 目前只用于点赞数的定时写回（LikeCountService#flush） */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
