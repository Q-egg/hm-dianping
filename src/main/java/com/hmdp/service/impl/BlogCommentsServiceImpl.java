package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.BlogCommentDTO;
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
import com.hmdp.utils.UserHolder;
import com.hmdp.vo.BlogCommentReplyVO;
import com.hmdp.vo.BlogCommentVO;
import com.hmdp.vo.LikedVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;

import javax.annotation.Resource;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_COMMENT_LIKED;
import static com.hmdp.utils.SystemConstants.DEFAULT_PAGE_SIZE;

/**
 * <p>
 *  &#x670D;&#x52A1;&#x5B9E;&#x73B0;&#x7C7B;
 * </p>
 *
 * @author &#x864E;&#x54E5;
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {
    @Resource
    private IUserService userService;

    @Resource
    private IBlogService blogService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

    @Transactional
    @Override
    public Result senComment(BlogCommentDTO blogCommentDTO) {
        //1.检查传入的评论是否为空
        Long blogId = blogCommentDTO.getBlogId();
        if (blogId == null || blogId <= 0) {
            return Result.fail("参数错误");
        }

        String content = StrUtil.trim(blogCommentDTO.getContent());
        if (StrUtil.isBlank(content)) {
            return Result.fail("评论内容不能为空");
        }
        if (content.codePointCount(0, content.length()) > 255) {
            return Result.fail("评论内容不能超过255个字符");
        }
        //2.获取当前用户
        Long userId = UserHolder.getUser().getId();

        //3.将该笔记的评论数 + 1
        boolean updated = blogService.update()
                .setSql("comments = COALESCE(comments, 0) + 1")
                .eq("id", blogCommentDTO.getBlogId())
                .update();
        if (!updated) {
            return Result.fail("笔记不存在");
        }

        //4.插入评论
        BlogComments blogComments = new BlogComments();
        blogComments.setBlogId(blogId);
        blogComments.setContent(content);
        blogComments.setUserId(userId);
        blogComments.setParentId(0L);
        blogComments.setAnswerId(0L);
        blogComments.setStatus(0);
        blogComments.setLiked(0);
        boolean save = save(blogComments);
        if(!save) {
            throw new RuntimeException();
        }

        //5.返回评论id
        return Result.ok(blogComments.getId());
    }

    @Transactional
    @Override
    public Result replyComment(Long commentId, BlogCommentDTO blogCommentDTO) {
        String content = blogCommentDTO.getContent();
        if(commentId == null || commentId <= 0){
            return Result.fail("找不到评论");
        }
        content = StrUtil.trim(content);
        if(StrUtil.isBlank(content)){
            return Result.fail("评论不能为空");
        }
        if(content.codePointCount(0, content.length()) > 255){
            return Result.fail("评论内容不能超过255字符");
        }
        //1.查询用户id
        Long userId = UserHolder.getUser().getId();
        //2.查询回复的评论
        BlogComments comment = getById(commentId);
        //3.判断评论是否为空
        if(comment == null || !Integer.valueOf(0).equals(comment.getStatus())){
            return Result.fail("评论不存在或不可见");
        }
        Long blogId = comment.getBlogId();
        //4.笔记评论数量 + 1
        boolean update = blogService.update()
                .setSql("comments = COALESCE(comments, 0) + 1")
                .eq("id", blogId)
                .update();
        if(!update){
            return Result.fail("笔记不存在");
        }

        //5.判断是一级评论，还是回复的评论
        Long parentId = comment.getParentId();
        //6.插入评论
        BlogComments blogComment = new BlogComments();
        blogComment.setUserId(userId);
        blogComment.setBlogId(blogId);
        blogComment.setContent(content);
        blogComment.setStatus(0);
        blogComment.setLiked(0);
        blogComment.setParentId(parentId == 0L ? comment.getId() : parentId);
        blogComment.setAnswerId(commentId);
        boolean save = save(blogComment);
        if(!save){
            throw new RuntimeException("保存评论出错");
        }
        //7.返回
        return Result.ok(blogComment.getId());
    }

    @Override
    public Result queryCommentReply(Long commentId, Integer current) {
        //1.判断commentId是否合法
        if(commentId == null || commentId < 1){
            return Result.fail("查询的评论不合法");
        }
        BlogComments comments = getById(commentId);
        if(comments == null || comments.getParentId() != 0 || !Integer.valueOf(0).equals(comments.getStatus())){
            return Result.fail("该评论不存在或被删除");
        }
        //2.查询评论信息
        Page<BlogComments> page = query().eq("parent_id", commentId).eq("status",0).orderByDesc("create_time").orderByDesc("id").page(new Page<>(current, DEFAULT_PAGE_SIZE));

        //3.封装结果
        List<BlogComments> blogComments = page.getRecords();
        if(blogComments == null || blogComments.isEmpty())
        {
            return Result.ok(Collections.emptyList(),page.getTotal());
        }
        List<BlogCommentReplyVO> blogCommentReply = new ArrayList<>(blogComments.size());
        List<Long> userIds = blogComments.stream().map(BlogComments::getUserId).collect(Collectors.toList());
        List<User> users = userService.query()
                .select("id", "nick_name", "icon")
                .in("id", userIds)
                .list();
        for (BlogComments blogComment : blogComments) {
            BlogCommentReplyVO blogCommentReplyVO = BeanUtil.copyProperties(blogComment, BlogCommentReplyVO.class);

            User author = users.stream()
                    .filter(user -> user.getId().equals(blogComment.getUserId()))
                    .findFirst()
                    .orElse(null);

            blogCommentReplyVO.setNickName(author == null ? "已注销用户" : author.getNickName());
            blogCommentReplyVO.setIcon(author == null || author.getIcon() == null ? "" : author.getIcon());
            blogCommentReplyVO.setLiked(blogComment.getLiked() == null ? 0 : blogComment.getLiked());
            blogCommentReply.add(blogCommentReplyVO);
        }

        return Result.ok(blogCommentReply,page.getTotal());
    }

    @Transactional
    @Override
    public Result like(Long commentId) {
        if(commentId <= 0){
            throw new RuntimeException("评论id不合法");
        }
        //1.查询对应的评论
        BlogComments comment = getById(commentId);
        //2.判断评论是否为null
        if(comment == null){
            return Result.fail("该评论不存在或已经被删除");
        }
        if(!Integer.valueOf(0).equals(comment.getStatus()))
        {
            return Result.fail("该评论违规");
        }
        //3.判断是否点赞过
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_COMMENT_LIKED + commentId;
        boolean member = Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, userId.toString()));
        if(!member) {
            Long changed = stringRedisTemplate.opsForSet().add(key, userId.toString());
            if(!Long.valueOf(1L).equals(changed)){
                return Result.fail("点赞状态已变化，请刷新");
            }
            boolean update = update().eq("id", commentId).eq("status", 0).setSql("liked = COALESCE(liked, 0) + 1").update();
            if (!update) {
                throw new RuntimeException("更新点赞数失败");
            }
        }else{
            Long changed = stringRedisTemplate.opsForSet().remove(key, userId.toString());
            if(!Long.valueOf(1L).equals(changed))
            {
                return Result.fail("点赞状态已变化，请刷新");
            }
            boolean update = update().eq("id", commentId).eq("status", 0).gt("liked",0).setSql("liked = liked - 1").update();
                if(!update){
                    throw new RuntimeException("更新点赞数失败");
                }
            }
        LikedVO likedVO = new LikedVO();
        likedVO.setIsLike(!member);
        likedVO.setLiked(getById(commentId).getLiked());

        return Result.ok(likedVO);
    }
}
