package com.brayton.weibo.repository;

import com.brayton.weibo.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    // 查询某个帖子下的所有评论（按时间排序）
    List<Comment> findByPostIdOrderByCreatedAtDesc(Long postId);
}