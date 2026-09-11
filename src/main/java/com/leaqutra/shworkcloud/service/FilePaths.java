package com.leaqutra.shworkcloud.service;

/**
 * 物化路径工具。
 * <p>
 * 约定（文档 §4.2）：{@code path} 存<b>祖先 ID</b> 路径，只含 ID 不含名称，
 * 因此改名不影响 path。例如某文件夹 id=35 的父链是 12，则其 path 为 {@code /12/}，
 * 它自己的物化路径为 {@code /12/35/}。
 * <p>
 * {@code parent_id} 才是权威，{@code path} 只是可重算的冗余加速列。
 */
public final class FilePaths {

    public static final String ROOT = "/";

    private FilePaths() {
    }

    /** 根目录路径 */
    public static String root() {
        return ROOT;
    }

    /** 由「父目录的祖先路径 + 父目录 id」得到子项的祖先路径 */
    public static String childPath(String parentAncestorPath, long parentId) {
        String base = parentAncestorPath == null || parentAncestorPath.isBlank() ? ROOT : parentAncestorPath;
        return base + parentId + "/";
    }

    /** 路径里包含的祖先层数："/" -> 0，"/12/" -> 1，"/12/35/" -> 2 */
    public static int depthOf(String path) {
        if (path == null || path.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * candidate 是否位于 ancestorPath 这棵子树内。
     * <p>是<b>严格</b>前缀关系：{@code "/12/".isUnder("/12/") == false}。
     * 根目录不作为祖先参与判断 —— 否则任何路径都"在根之下"，
     * 会让移动校验误判成"不能移动"。
     */
    public static boolean isUnder(String candidatePath, String ancestorPath) {
        if (candidatePath == null || ancestorPath == null || ancestorPath.equals(ROOT)) {
            return false;
        }
        return candidatePath.startsWith(ancestorPath) && !candidatePath.equals(ancestorPath);
    }
}
