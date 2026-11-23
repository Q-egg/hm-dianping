package com.hmdp.service;

import com.hmdp.dto.BlogCommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    Result queryCommentsList(Long blogId, Integer current);

    Result senComment(BlogCommentDTO blogCommentDTO);

    Result replyComment(Long commentId, BlogCommentDTO blogCommentDTO);

    Result queryCommentReply(Long commentId, Integer current);

    Result like(Long commentId);

    Result deleteComment(Long commentId);
}
