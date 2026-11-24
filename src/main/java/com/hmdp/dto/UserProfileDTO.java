package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UserProfileDTO {
    private String nickName;
    private String icon;
    private String introduce;
    private String city;
    private Boolean gender;

    @JsonFormat(pattern = "uuuu-MM-dd", lenient = OptBoolean.FALSE)
    private LocalDate birthday;
}