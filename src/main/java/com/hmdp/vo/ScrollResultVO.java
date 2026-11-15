package com.hmdp.vo;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResultVO {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
