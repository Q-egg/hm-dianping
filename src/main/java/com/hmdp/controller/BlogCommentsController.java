package com.hmdp.controller;


import com.hmdp.dto.BlogCommentDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComments;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {
    @Resource
    private IBlogCommentsService blogCommentsService;

    @GetMapping
    public Result queryCommentsList(@RequestParam("blogId") Long blogId,@RequestParam(value = "current",defaultValue = "1") Integer current){
        return blogCommentsService.queryCommentsList(blogId,current);
    }

    @PostMapping
    public Result sendComment(@RequestBody BlogCommentDTO blogCommentDTO){
        return blogCommentsService.senComment(blogCommentDTO);
    }

    @PostMapping("/{commentId}/replies")
    public Result replyComment(@PathVariable("commentId") Long commentId,@RequestBody BlogCommentDTO blogCommentDTO)
    {
        return blogCommentsService.replyComment(commentId,blogCommentDTO);
    }

    @GetMapping("/{commentId}/replies")
    public Result queryCommentReply(@PathVariable("commentId") Long commentId,@RequestParam(value = "current",defaultValue = "1") Integer current)
    {
        return blogCommentsService.queryCommentReply(commentId,current);
    }

    @PutMapping("/like/{commentId}")
    public Result like(@PathVariable Long commentId)
    {
        return blogCommentsService.like(commentId);
    }

}
