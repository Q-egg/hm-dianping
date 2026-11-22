package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

public interface UploadService {

    Result uploadImage(MultipartFile image);

    Result uploadImage(MultipartFile image, UUID draftId);

    Result deleteImage(String name);

    Result deleteImage(String name, UUID draftId);

    Result startDraft();

    Result renewDraft(UUID draftId);

    Result publishDraft(UUID draftId, Blog blog);

    Result cancelDraft(UUID draftId);

    void expireDrafts();
}
