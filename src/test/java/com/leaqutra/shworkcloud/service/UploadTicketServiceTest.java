package com.leaqutra.shworkcloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片大小选择的边界校验。
 * <p>OSS 硬约束：单次分片上传最多 10000 个分片，且除最后一片外每片不小于 100KB。
 * 选错分片大小会让大文件直接传不上去，所以这里按不变量测试。
 */
class UploadTicketServiceTest {

    private static final long MB = 1024L * 1024;

    @Test
    @DisplayName("小文件用默认分片，不切碎")
    void smallFilesUseDefaultPartSize() {
        assertEquals(5 * MB, UploadTicketService.choosePartSize(1 * MB));
        assertEquals(5 * MB, UploadTicketService.choosePartSize(100 * MB));
        assertEquals(5 * MB, UploadTicketService.choosePartSize(499 * MB));
    }

    @Test
    @DisplayName("大文件用 10MB 分片")
    void largeFilesUseBiggerPartSize() {
        assertEquals(10 * MB, UploadTicketService.choosePartSize(500 * MB));
        assertEquals(10 * MB, UploadTicketService.choosePartSize(2L * 1024 * MB));
        // OSS 理论上限约 48.8GB（5MB × 10000），10MB 分片下依然远小于 10000 片
        assertEquals(10 * MB, UploadTicketService.choosePartSize(40L * 1024 * MB));
    }

    @Test
    @DisplayName("关键不变量：分片数永不超过 10000")
    void partCountNeverExceedsOssLimit() {
        long[] sizes = {
                1 * MB, 100 * MB, 500 * MB, 2L * 1024 * MB, 10L * 1024 * MB,
                49L * 1024 * MB, 100L * 1024 * MB, 200L * 1024 * MB, 500L * 1024 * MB,
        };
        for (long size : sizes) {
            long partSize = UploadTicketService.choosePartSize(size);
            long partCount = (size + partSize - 1) / partSize;
            assertTrue(partCount <= 10000,
                    "size=%d partSize=%d partCount=%d 超过 OSS 上限".formatted(size, partSize, partCount));
            // 除最后一片外都要满足 OSS 的最小分片限制
            assertTrue(partSize >= 100 * 1024, "分片小于 OSS 下限: " + partSize);
        }
    }

    @Test
    @DisplayName("超大文件：分片大小按需放大到 1MB 的整数倍")
    void hugeFileGrowsPartSize() {
        long size = 500L * 1024 * MB; // 500GB
        long partSize = UploadTicketService.choosePartSize(size);
        assertTrue(partSize > 10 * MB, "超大文件应放大分片：" + partSize);
        // 必须是 1MB 的整数倍（便于前端切片对齐）
        assertEquals(0, partSize % MB);
        long partCount = (size + partSize - 1) / partSize;
        assertTrue(partCount <= 10000, "分片数 " + partCount);
    }

    @Test
    @DisplayName("退化输入不会抛异常")
    void degenerateInputs() {
        assertTrue(UploadTicketService.choosePartSize(0L) >= 100 * 1024);
        assertTrue(UploadTicketService.choosePartSize(1L) >= 100 * 1024);
    }
}
