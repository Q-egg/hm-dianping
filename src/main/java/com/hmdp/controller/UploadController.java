package com.hmdp.controller;


import cn.hutool.core.lang.UUID;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;


@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    @PostMapping("/blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image){
        try{
            //获取原始文件名称
            String originalFilename = image.getOriginalFilename();

            //生成新文件名
            String fileName = createNewFileName(originalFilename);

            //保存文件
            image.transferTo(new File(SystemConstants.IMAGE_UPLOAD_DIR,fileName));

            //返回结果
            log.debug("文件上传成功，{}",fileName);
            return Result.ok("/" + fileName);

        }catch (IOException e) {
            throw new RuntimeException("文件上传失败",e);
        }
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

        return UUID.randomUUID().toString(true) + suffix;
    }



}
