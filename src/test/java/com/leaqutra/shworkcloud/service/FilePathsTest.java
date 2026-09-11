package com.leaqutra.shworkcloud.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物化路径工具测试。
 * <p>路径正确性直接决定「移动文件夹」与「回收站清理」是否会漏掉子孙记录。
 */
class FilePathsTest {

    @Test
    @DisplayName("祖先层数计算")
    void depthOf() {
        assertEquals(0, FilePaths.depthOf("/"));
        assertEquals(0, FilePaths.depthOf(null));
        assertEquals(0, FilePaths.depthOf(""));
        assertEquals(1, FilePaths.depthOf("/12/"));
        assertEquals(2, FilePaths.depthOf("/12/35/"));
        assertEquals(3, FilePaths.depthOf("/12/35/78/"));
    }

    @Test
    @DisplayName("子项祖先路径 = 父的祖先路径 + 父ID")
    void childPath() {
        assertEquals("/12/", FilePaths.childPath("/", 12L));
        assertEquals("/12/35/", FilePaths.childPath("/12/", 35L));
        // 父路径为空时按根处理
        assertEquals("/7/", FilePaths.childPath(null, 7L));
    }

    @Test
    @DisplayName("子树判断")
    void isUnder() {
        assertTrue(FilePaths.isUnder("/12/35/", "/12/"));
        assertTrue(FilePaths.isUnder("/12/35/78/", "/12/"));
        // 同一层级不算子孙
        assertFalse(FilePaths.isUnder("/12/", "/12/"));
        // 根目录不作为祖先判断（所有东西都在根下，会导致"不能移动"误判）
        assertFalse(FilePaths.isUnder("/12/", "/"));
        assertFalse(FilePaths.isUnder(null, "/12/"));
        assertFalse(FilePaths.isUnder("/12/", null));
    }

    @Test
    @DisplayName("移动场景：把 /12/ 子树挪到 /30/ 下，前缀替换结果正确")
    void movePrefixRewrite() {
        String oldSelfPath = "/12/";
        String newSelfPath = "/30/12/";
        // 子孙原始 path -> 期望的新 path
        assertEquals("/30/12/", rewrite("/12/", oldSelfPath, newSelfPath));
        assertEquals("/30/12/78/", rewrite("/12/78/", oldSelfPath, newSelfPath));
        assertEquals("/30/12/78/99/", rewrite("/12/78/99/", oldSelfPath, newSelfPath));
    }

    /** 复刻 FileEntryMapper.updatePathPrefix 里的 SUBSTRING 语义，验证前缀替换正确 */
    private String rewrite(String path, String oldSelfPath, String newSelfPath) {
        String suffix = path.substring(oldSelfPath.length());
        return newSelfPath + suffix;
    }
}
