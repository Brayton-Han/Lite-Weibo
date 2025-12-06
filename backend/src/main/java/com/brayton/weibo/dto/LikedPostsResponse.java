package com.brayton.weibo.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LikedPostsResponse {
    List<PostResponse> posts;
    long nextCursor;
}
