package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComments;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentsMapper;
import com.hmdp.service.IBlogCommentsService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IUserService;
import com.hmdp.service.UploadService;
import com.hmdp.vo.BlogCommentVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {
    @Resource
    private IUserService userService;

    @Resource
    private IBlogService blogService;

    @Override
    public Result queryCommentsList(Long blogId, Integer current) {
        if(blogId == null || blogId <= 0){
            return Result.fail("参数错误");
        }
        Blog blog = blogService.getById(blogId);
        if(blog == null){
            return Result.fail("笔记不存在");
        }

        //1.查询状态正常的评论内容,并进行分页排序
        Page<BlogComments> page = query()
                .eq("blog_id", blogId)
                .eq("parent_id", 0)
                .eq("status", 0)
                .orderByDesc("create_time")
                .orderByDesc("id")
                .page(new Page<>(current, DEFAULT_PAGE_SIZE));

        List<BlogComments> blogComments = page.getRecords();
        //2.检查评论是否为空
        if(blogComments == null || blogComments.isEmpty()){
            return Result.ok(Collections.emptyList(),page.getTotal());
        }
        //3.从评论中查询用户id
        List<Long> userIds = blogComments.stream()
                .map(BlogComments::getUserId)
                .distinct()
                .collect(Collectors.toList());

        //4.根据id查询用户
        String idStr = StrUtil.join(",", userIds);
        List<User> users = userService.query()
                .select("id", "nick_name", "icon")
                .in("id", userIds)
                .last("ORDER BY FIELD(id, " + idStr + ")")
                .list();
        //5.封装结果
        List<BlogCommentVO> comments = new ArrayList<>(blogComments.size());
        for (BlogComments blogComment : blogComments) {
            BlogCommentVO comment = BeanUtil.copyProperties(blogComment, BlogCommentVO.class);
            User author = users.stream()
                    .filter(user -> user.getId().equals(blogComment.getUserId()))
                    .findFirst()
                    .orElse(null);
            comment.setNickName(author == null ? "已注销用户" : author.getNickName());
            comment.setIcon(author == null || author.getIcon() == null ? "" : author.getIcon());
            comment.setLiked(blogComment.getLiked() == null ? 0 : blogComment.getLiked());

            comments.add(comment);
        }

        //返回
        return Result.ok(comments, page.getTotal());
    }
}
