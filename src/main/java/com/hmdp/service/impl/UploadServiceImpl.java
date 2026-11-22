package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.service.UploadService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.UPLOAD_OWNER;

@Service
@EnableScheduling
@Slf4j
public class UploadServiceImpl implements UploadService {

    private static final String DEADLINES = "blog:draft:deadlines";
    private static final long TIMEOUT = TimeUnit.MINUTES.toMillis(5);

    // 进程在落库中途退出时，清理任务需要先核对笔记是否已经保存。
    private static final double PUBLISHING = -1;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redisson;

    @Resource
    private IBlogService blogService;

    @Override
    public Result uploadImage(MultipartFile image) {
        return uploadImage(image, null);
    }

    @Override
    public Result uploadImage(MultipartFile image, UUID draftId) {
        if (image == null || image.isEmpty()) {
            return Result.fail("请选择要上传的图片");
        }

        // 1. 检查文件后缀
        String originalFilename = image.getOriginalFilename();
        String extension = StringUtils.getFilenameExtension(originalFilename);
        String expectedFormat;
        if ("jpg".equalsIgnoreCase(extension) || "jpeg".equalsIgnoreCase(extension)) {
            expectedFormat = "JPEG";
        } else if ("png".equalsIgnoreCase(extension)) {
            expectedFormat = "PNG";
        } else {
            return Result.fail("只支持 JPG、JPEG、PNG 图片");
        }

        // 2. 读取图片头，确认实际格式与后缀一致
        if (!matchesImageFormat(image, expectedFormat)) {
            return Result.fail("图片格式不正确");
        }

        // 3. 生成文件名并保存
        String fileName = createNewFileName(originalFilename);
        if (draftId != null) {
            return whileEditing(draftId, member -> {
                // 先登记后落盘，响应丢失时仍能回收文件。
                stringRedisTemplate.opsForSet().add(files(member), fileName);
                return saveImage(image, fileName);
            });
        }
        return saveImage(image, fileName);
    }

    private Result saveImage(MultipartFile image, String fileName) {
        File target = new File(SystemConstants.IMAGE_UPLOAD_DIR, fileName);

        try {
            image.transferTo(target);
        } catch (IOException e) {
            log.error("保存图片失败，文件名：{}", fileName, e);
            return Result.fail("文件上传失败");
        }

        // 4. 记录上传者，供删除时校验
        String userId = UserHolder.getUser().getId().toString();
        stringRedisTemplate.opsForValue().set(UPLOAD_OWNER + fileName, userId);

        return Result.ok("/" + fileName);
    }

    @Override
    public Result deleteImage(String name) {
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            return Result.fail("文件名不合法");
        }

        String key = UPLOAD_OWNER + name;
        String ownerId = stringRedisTemplate.opsForValue().get(key);
        String currentUserId = UserHolder.getUser().getId().toString();

        if (!currentUserId.equals(ownerId)) {
            return Result.fail("无法删除");
        }

        File file = new File(SystemConstants.IMAGE_UPLOAD_DIR, name);
        if (!file.isFile() || !file.delete()) {
            return Result.fail("图片删除失败");
        }

        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    private String createNewFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.trim().isEmpty()) {
            throw new IllegalArgumentException("文件名不能为空");
        }

        int dotIndex = originalFilename.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == originalFilename.length() - 1) {
            throw new IllegalArgumentException("文件缺少扩展名");
        }

        String suffix = originalFilename.substring(dotIndex);
        if (suffix.contains("/") || suffix.contains("\\")) {
            throw new IllegalArgumentException("文件扩展名不合法");
        }

        return cn.hutool.core.lang.UUID.randomUUID().toString(true) + suffix;
    }

    private boolean matchesImageFormat(MultipartFile image, String expectedFormat) {
        try (InputStream raw = image.getInputStream();
             ImageInputStream input = ImageIO.createImageInputStream(raw)) {
            if (input == null) {
                return false;
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                if (!expectedFormat.equalsIgnoreCase(reader.getFormatName())) {
                    return false;
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);

                // 完整解码会占用内存，这里以 2000 万像素为上限，可按需调整
                if (width <= 0 || height <= 0 || (long) width * height > 20_000_000L) {
                    return false;
                }

                return reader.read(0) != null;
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return false;
        }
    }

    private String member(UUID id) {
        return UserHolder.getUser().getId() + ":" + id;
    }

    private String files(String member) {
        return "blog:draft:files:" + member;
    }

    @Override
    public Result startDraft() {
        UUID id = UUID.randomUUID();
        stringRedisTemplate.opsForZSet().add(DEADLINES, member(id), System.currentTimeMillis() + TIMEOUT);
        return Result.ok(id.toString());
    }

    private Result whileEditing(UUID id, Function<String, Result> action) {
        String member = member(id);
        RLock lock = redisson.getLock("lock:blog:draft:" + member);
        lock.lock();
        try {
            Double deadline = stringRedisTemplate.opsForZSet().score(DEADLINES, member);
            long now = System.currentTimeMillis();
            if (deadline == null || deadline <= now) {
                return Result.fail("草稿已结束");
            }
            stringRedisTemplate.opsForZSet().add(DEADLINES, member, now + TIMEOUT);
            return action.apply(member);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result renewDraft(UUID id) {
        return whileEditing(id, member -> Result.ok());
    }

    @Override
    public Result deleteImage(String name, UUID draftId) {
        if (draftId == null) {
            return deleteImage(name);
        }
        return whileEditing(draftId, member -> {
            String files = files(member);
            if (!Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(files, name))) {
                return Result.fail("图片不属于当前草稿");
            }
            Result result = deleteImage(name);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                stringRedisTemplate.opsForSet().remove(files, name);
            }
            return result;
        });
    }

    @Override
    public Result publishDraft(UUID draftId, Blog blog) {
        return whileEditing(draftId, member -> {
            Set<String> names = stringRedisTemplate.opsForSet().members(files(member));
            Set<String> used = new HashSet<>();
            if (blog.getImages() != null && !blog.getImages().isEmpty()) {
                for (String path : blog.getImages().split(",", -1)) {
                    if (!path.startsWith("/imgs/") || names == null || !names.contains(path.substring(6))) {
                        return Result.fail("图片不属于当前草稿");
                    }
                    used.add(path.substring(6));
                }
            }
            stringRedisTemplate.opsForZSet().add(DEADLINES, member, PUBLISHING);
            Result result = blogService.saveBlog(blog);
            if (Boolean.TRUE.equals(result.getSuccess())) {
                for (String name : used) {
                    stringRedisTemplate.delete(UPLOAD_OWNER + name);
                    stringRedisTemplate.opsForSet().remove(files(member), name);
                }
                // 包括上传成功但浏览器未收到响应的多余图片。
                stringRedisTemplate.opsForZSet().add(DEADLINES, member, 0);
            } else {
                stringRedisTemplate.opsForZSet().add(DEADLINES, member, System.currentTimeMillis() + TIMEOUT);
            }
            return result;
        });
    }

    @Override
    public Result cancelDraft(UUID id) {
        return cleanup(member(id), true);
    }

    @Override
    @Scheduled(fixedDelay = 5000)
    public void expireDrafts() {
        Set<String> due = stringRedisTemplate.opsForZSet().rangeByScore(DEADLINES, PUBLISHING, System.currentTimeMillis());
        if (due != null) {
            for (String member : due) {
                try {
                    cleanup(member, false);
                } catch (RuntimeException e) {
                    log.warn("清理过期草稿失败 {}", member, e);
                }
            }
        }
    }

    private Result cleanup(String member, boolean cancel) {
        RLock lock = redisson.getLock("lock:blog:draft:" + member);
        lock.lock();
        try {
            Double deadline = stringRedisTemplate.opsForZSet().score(DEADLINES, member);
            if (deadline == null || (!cancel && deadline > System.currentTimeMillis())) {
                return Result.ok();
            }
            boolean interruptedPublish = deadline == PUBLISHING;
            if (!interruptedPublish) {
                stringRedisTemplate.opsForZSet().add(DEADLINES, member, 0);
            }
            Set<String> names = stringRedisTemplate.opsForSet().members(files(member));
            if (names != null) {
                for (String name : names) {
                    // 发布可能已写入数据库，但在返回或移除草稿记录前中断。
                    if (!interruptedPublish || blogService.count(new QueryWrapper<Blog>()
                            .apply("FIND_IN_SET({0}, images) > 0", "/imgs/" + name)) == 0) {
                        Files.deleteIfExists(Paths.get(SystemConstants.IMAGE_UPLOAD_DIR, name));
                    }
                    stringRedisTemplate.delete(UPLOAD_OWNER + name);
                    stringRedisTemplate.opsForSet().remove(files(member), name);
                }
            }
            stringRedisTemplate.opsForZSet().remove(DEADLINES, member);
            return Result.ok();
        } catch (IOException e) {
            log.warn("删除草稿图片失败 {}", member, e);
            return Result.fail("图片清理失败，请重试");
        } finally {
            lock.unlock();
        }
    }
}
