package com.brayton.weibo.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UnreadCountResponse {
    int follow;
    int like;
    int comment;
}