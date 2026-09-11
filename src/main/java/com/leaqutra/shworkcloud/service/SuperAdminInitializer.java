package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.PasswordHasher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 超级管理员启动初始化（幂等）。
 * <p>
 * 安全要求：{@code app.admin.init-password} 为空时<b>直接启动失败</b>，
 * 绝不用诸如 {@code admin123} 的默认密码兜底 —— 那等于给生产系统留了一个公开后门。
 * <p>
 * 初始化出的账号 {@code pwd_changed = 0}，首次登录强制改密。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final AppProperties appProperties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        if (userMapper.countSuperAdmin() > 0) {
            log.info("已存在超级管理员，跳过初始化");
            return;
        }
        AppProperties.Admin admin = appProperties.getAdmin();
        if (!StringUtils.hasText(admin.getInitPassword())) {
            throw new IllegalStateException("""
                    未配置 app.admin.init-password，拒绝以空密码初始化超级管理员。
                    请在 application-local.yaml 或环境变量 ADMIN_INIT_PASSWORD 中设置一个强密码。""");
        }
        if (!StringUtils.hasText(admin.getInitUsername())) {
            throw new IllegalStateException("未配置 app.admin.init-username");
        }

        SysUser superAdmin = new SysUser();
        superAdmin.setUsername(admin.getInitUsername().trim());
        superAdmin.setStudentNo(null);
        superAdmin.setRealName("超级管理员");
        superAdmin.setEmail(StringUtils.hasText(admin.getInitEmail()) ? admin.getInitEmail().trim() : null);
        superAdmin.setPassword(PasswordHasher.encode(admin.getInitPassword()));
        superAdmin.setNickname("超级管理员");
        superAdmin.setRole((byte) SysUser.ROLE_SUPER_ADMIN);
        superAdmin.setStatus((byte) 1);
        superAdmin.setDeleted((byte) 0);
        // 首次登录强制改密
        superAdmin.setPwdChanged((byte) 0);
        superAdmin.setStorageQuota(1_099_511_627_776L);   // 1TB
        superAdmin.setUsedStorage(0L);
        superAdmin.setLoginFailCount(0);
        userMapper.insert(superAdmin);

        log.warn("已初始化超级管理员 username={}，请首次登录后立即修改密码", superAdmin.getUsername());
    }
}
