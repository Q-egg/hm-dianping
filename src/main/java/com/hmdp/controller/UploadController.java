package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.UploadService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import java.util.UUID;

@RestController
@RequestMapping("/upload")
public class UploadController {
    @Resource
    private UploadService uploadService;

    @PostMapping("/blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image,
                              @RequestParam(required = false) UUID draftId){
        return uploadService.uploadImage(image, draftId);
    }


    @GetMapping("/blog/delete")
    public Result deleteImage(@RequestParam("name") String name, @RequestParam(required = false) UUID draftId){
        return uploadService.deleteImage(name, draftId);
    }

    @PostMapping("/draft")
    public Result startDraft() {
        return uploadService.startDraft();
    }

    @PutMapping("/draft/{id}")
    public Result renewDraft(@PathVariable UUID id) {
        return uploadService.renewDraft(id);
    }

    @DeleteMapping("/draft/{id}")
    public Result cancelDraft(@PathVariable UUID id) {
        return uploadService.cancelDraft(id);
    }
}
