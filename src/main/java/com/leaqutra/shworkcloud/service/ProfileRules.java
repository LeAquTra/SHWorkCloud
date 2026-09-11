package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.springframework.util.StringUtils;

import java.time.LocalDate;

/**
 * 个性属性校验规则。
 * <p>
 * <b>更新语义（对 API 调用方很重要，必须一致）：</b>
 * <ul>
 *   <li>{@code nickname}：为 null 表示<b>不修改</b>；传空串报错（昵称不能为空）；</li>
 *   <li>{@code signature} / {@code gender} / {@code birthday}：为 null 表示<b>清空</b>该属性。</li>
 * </ul>
 * 之所以允许传入一个字段来单独修改，是为了让后端调用方（curl / Postman / 客户端 SDK）
 * 不必每次回传全部字段。
 * <p>
 * 抽成独立类是为了可单元测试（不依赖 Spring 与数据库）。
 */
public final class ProfileRules {

    public static final int NICKNAME_MAX = 50;
    public static final int SIGNATURE_MAX = 255;
    private static final int BIRTHDAY_MIN_YEAR = 1900;

    public static final byte GENDER_UNKNOWN = 0;
    public static final byte GENDER_MALE = 1;
    public static final byte GENDER_FEMALE = 2;

    private ProfileRules() {
    }

    /**
     * 校验昵称。
     *
     * @return null 表示不修改；否则返回去掉首尾空格后的昵称
     */
    public static String normalizeNickname(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "昵称不能为空");
        }
        if (value.codePointCount(0, value.length()) > NICKNAME_MAX) {
            throw new BizException(ErrorCode.BAD_PARAM, "昵称最多 " + NICKNAME_MAX + " 个字符");
        }
        return value;
    }

    /**
     * 校验个性签名。
     *
     * @return null 表示清空
     */
    public static String normalizeSignature(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.codePointCount(0, value.length()) > SIGNATURE_MAX) {
            throw new BizException(ErrorCode.BAD_PARAM, "个性签名最多 " + SIGNATURE_MAX + " 个字符");
        }
        return value;
    }

    /**
     * 校验性别。
     *
     * @return 0/1/2；入参为 null 时返回 0（未知，即清空）
     */
    public static Byte normalizeGender(Byte gender) {
        if (gender == null) {
            return GENDER_UNKNOWN;
        }
        if (gender < GENDER_UNKNOWN || gender > GENDER_FEMALE) {
            throw new BizException(ErrorCode.BAD_PARAM, "性别只能是 0(未知) / 1(男) / 2(女)");
        }
        return gender;
    }

    /**
     * 校验生日。
     * <p>不允许未来日期，也不接受过于久远的年份（多为误填）。
     *
     * @return null 表示清空
     */
    public static LocalDate normalizeBirthday(LocalDate birthday) {
        if (birthday == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        if (birthday.isAfter(today)) {
            throw new BizException(ErrorCode.BAD_PARAM, "生日不能晚于今天");
        }
        if (birthday.getYear() < BIRTHDAY_MIN_YEAR) {
            throw new BizException(ErrorCode.BAD_PARAM, "生日年份不能早于 " + BIRTHDAY_MIN_YEAR);
        }
        return birthday;
    }
}
