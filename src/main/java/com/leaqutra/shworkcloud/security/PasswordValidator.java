package com.leaqutra.shworkcloud.security;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import org.springframework.util.StringUtils;

/**
 * 密码强度校验。
 * <p>
 * 本环境未引入 spring-boot-starter-validation（本地仓库无 hibernate-validator），
 * 因此所有入参校验都在 Service 层手写，这里集中处理密码规则。
 * <p>
 * 校验失败必须给出**具体原因**，而不是笼统的「密码不符合要求」——
 * 机房课上学生看提示自助修正，比老师逐个排查高效得多。
 */
public final class PasswordValidator {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 32;

    private PasswordValidator() {
    }

    /**
     * @param raw      明文密码
     * @param username 登录名（学号），用于禁止「密码等于学号」
     */
    public static void validate(String raw, String username) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ErrorCode.BAD_PARAM, "密码不能为空");
        }
        String pwd = raw.trim();
        if (pwd.length() < MIN_LENGTH) {
            throw new BizException(ErrorCode.BAD_PARAM, "密码长度不能少于 " + MIN_LENGTH + " 位");
        }
        if (pwd.length() > MAX_LENGTH) {
            throw new BizException(ErrorCode.BAD_PARAM, "密码长度不能超过 " + MAX_LENGTH + " 位");
        }
        boolean hasLetter = pwd.chars().anyMatch(Character::isLetter);
        boolean hasDigit = pwd.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.BAD_PARAM, "密码必须同时包含字母和数字");
        }
        if (pwd.chars().anyMatch(Character::isWhitespace)) {
            throw new BizException(ErrorCode.BAD_PARAM, "密码不能包含空格");
        }
        if (StringUtils.hasText(username) && pwd.equalsIgnoreCase(username.trim())) {
            throw new BizException(ErrorCode.BAD_PARAM, "密码不能与学号相同");
        }
    }
}
