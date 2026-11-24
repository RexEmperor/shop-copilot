package com.shopcopilot.business.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.junit.jupiter.api.Test;

class PageResultTest {

    @Test
    void should_mapMyBatisPage_toPageResult() {
        Page<String> page = new Page<>(2, 10);
        page.setRecords(List.of("a", "b"));
        page.setTotal(25);

        PageResult<String> result = PageResult.of(page);

        assertEquals(List.of("a", "b"), result.records());
        assertEquals(25, result.total());
        assertEquals(2, result.page());
        assertEquals(10, result.size());
    }
}
