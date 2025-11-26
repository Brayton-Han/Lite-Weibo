package com.brayton.weibo.entity;

import com.brayton.weibo.common.ImagesConverter;
import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.*;
import org.hibernate.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "posts")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@SQLDelete(sql = "UPDATE posts SET deleted = true WHERE id = ?")
@Where(clause = "deleted = false")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 作者
    @Column(nullable = false)
    private Long userId;

    // 文本内容（支持富文本的话可以改成 TEXT 类型）
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    // 多图（JSON 字符串存 List<String>）
    @Convert(converter = ImagesConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> images;

    // 可见性：public / followers / private / friends
    @Column(nullable = false)
    private PostVisibility visibility = PostVisibility.PUBLIC;

    // 点赞数（异步更新）
    @Column(nullable = false)
    private Long likeCount = 0L;

    // 评论数
    @Column(nullable = false)
    private Long commentCount = 0L;

    // 审核状态 normal / reviewing / blocked
    @Column(nullable = false)
    private PostStatus status = PostStatus.NORMAL;

    // 软删标记
    @Column(nullable = false)
    private boolean deleted = false;

    // 发布时间
    @CreationTimestamp
    private LocalDateTime createdAt;

    // 更新时间
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
