package com.hmdp.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BlogCommentReplyVO {
    private Long id;
    private Long userId;
    // 被直接回复的评论 ID
    private Long answerId;
    private String nickName;
    private String icon;
    private String content;
    private Integer liked;
    private LocalDateTime createTime;
}
