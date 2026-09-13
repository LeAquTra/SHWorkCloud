package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 账号字段校验规则：登录名、真实姓名、学号、班级、邮箱。
 * <p>
 * 抽成独立类有两个目的：
 * <ol>
 *   <li><b>单一来源</b>：自助注册与后台代改资料共用同一套规则，
 *       避免两边各写一份迟早不一致；</li>
 *   <li><b>可单元测试</b>：纯静态方法，不依赖 Spring 与数据库。</li>
 * </ol>
 * <p>
 * <b>更新语义</b>（与 {@link ProfileRules} 刻意保持一致）：
 * 传 {@code null} 表示<b>不修改</b>；传空串表示<b>清空</b>该字段。
 * 是否需要修改由调用方按「请求体里有没有这个键」决定。
 */
public final class AccountRules {

    /**
     * 登录名：<b>只允许 ASCII 数字与大小写字母</b>。
     * <p>不含下划线、连字符、空格、汉字、全角字符。
     */
    public static final String USERNAME_PATTERN = "^[0-9A-Za-z]+$";

    /** 登录名长度上限（数据库列是 VARCHAR(50)，这里收紧到更合理的 20） */
    public static final int USERNAME_MAX = 20;

    private static final Pattern USERNAME = Pattern.compile(USERNAME_PATTERN);

    /**
     * 学号格式。<b>必须与名单导入保持一致</b>（{@code StudentImportService} 也用这个常量），
     * 否则「CSV 能导进来、后台改不动」这种不一致很难查。
     */
    public static final Pattern STUDENT_NO = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    public static final int REAL_NAME_MAX = 50;
    public static final int CLASS_NAME_MAX = 100;
    public static final int EMAIL_MAX = 100;

    /**
     * 邮箱：只做「格式是否合理」的校验。
     * <p>不在这里限制「仅 QQ 邮箱」—— 那条规则属于<b>注册通道</b>
     * （{@code app.register.email-pattern}），后台代填的邮箱只需格式正确。
     */
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$");

    private AccountRules() {
    }

    // ------------------------------------------------------------ 登录名

    /**
     * 校验<b>用户自填</b>的登录名。
     * <p>
     * ⚠️ 这里的选择是「<b>直接报错</b>」，而不是像以前那样用
     * {@code replaceAll} 静默抹掉非法字符。静默改写会让用户以为注册成功，
     * 实际登录名已经被改掉（填「张三」会被抹成空串、再退化成 {@code u12345}），
     * 事后根本找不到自己的账号 —— 这是必须避免的体验。
     *
     * @return 去掉首尾空格后的合法登录名
     */
    public static String requireValidUsername(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ErrorCode.BAD_PARAM, "登录名不能为空");
        }
        String value = raw.trim();
        if (value.length() > USERNAME_MAX) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "登录名最多 " + USERNAME_MAX + " 个字符");
        }
        if (!USERNAME.matcher(value).matches()) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "登录名只能使用数字或大小写字母（不能含空格、下划线、连字符、汉字等）");
        }
        return value;
    }

    /**
     * 可选的登录名：留空返回 {@code null}（表示交给服务端按邮箱自动生成），
     * 非空则必须合法。
     */
    public static String optionalUsername(String raw) {
        return StringUtils.hasText(raw) ? requireValidUsername(raw) : null;
    }

    // ------------------------------------------------------------ 身份字段

    /** 真实姓名：空串 = 清空 */
    public static String normalizeRealName(String raw) {
        return trimToNull(raw, REAL_NAME_MAX, "真实姓名");
    }

    /** 班级：空串 = 清空 */
    public static String normalizeClassName(String raw) {
        return trimToNull(raw, CLASS_NAME_MAX, "班级");
    }

    /** 邮箱：空串 = 清空（名单导入的学生可能本来就没有邮箱） */
    public static String normalizeEmail(String raw) {
        String value = trimToNull(raw, EMAIL_MAX, "邮箱");
        if (value != null && !EMAIL.matcher(value).matches()) {
            throw new BizException(ErrorCode.BAD_PARAM, "邮箱格式不正确");
        }
        return value;
    }

    /** 学号：空串 = 清空；格式与名单导入一致 */
    public static String normalizeStudentNo(String raw) {
        String value = trimToNull(raw, 32, "学号");
        if (value != null && !STUDENT_NO.matcher(value).matches()) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "学号只能包含数字、字母、下划线或连字符，长度 3~32");
        }
        return value;
    }

    /** 去空格；空串归一成 null；超长报错 */
    private static String trimToNull(String raw, int max, String label) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.codePointCount(0, value.length()) > max) {
            throw new BizException(ErrorCode.BAD_PARAM, label + "最多 " + max + " 个字符");
        }
        return value;
    }
}
