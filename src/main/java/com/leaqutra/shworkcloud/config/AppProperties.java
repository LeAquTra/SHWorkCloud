package com.leaqutra.shworkcloud.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用级业务配置（对应 application.yaml 的 app.*）。
 * <p>
 * 说明：文档中的 {@code app.import.*} 在 Java 里无法映射到字段名
 * （{@code import} 是保留字，且 Spring Boot 的 {@code @Name} 对 JavaBean 字段绑定
 * 并不可靠），因此改用 {@code app.student-import.*}。文档 §6.2 / §14.2 同步为此键名。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Register register = new Register();
    private StudentImport studentImport = new StudentImport();
    private Quota quota = new Quota();
    private Upload upload = new Upload();
    private Captcha captcha = new Captcha();
    private Security security = new Security();
    private Recycle recycle = new Recycle();
    private Storage storage = new Storage();
    private Classroom classroom = new Classroom();
    private Admin admin = new Admin();

    // ------------------------------------------------------------------ 注册

    @Data
    public static class Register {
        /** 自助注册开关；默认开启，用户可自行注册登录 */
        private boolean enabled = true;

        /**
         * 注册时是否强制先过图片验证码。
         * <p>默认 false：图片验证码依赖后台先上传题库，而题库为空时注册会被整体挡住。
         * 需要更强防机刷能力时再开启（并要求管理员先把题库维护起来）。
         */
        private boolean requireImageCaptcha = false;

        /**
         * 是否真正发送邮件。
         * <p>false 时只把验证码打到日志（便于后端联调），<b>生产环境禁止关闭</b>：
         * 应用启动时会校验 profile 包含 prod 的情况并直接失败。
         */
        private boolean mailEnabled = true;

        /** 允许注册的邮箱正则，默认仅 QQ 邮箱 */
        private String emailPattern = "^\\d{5,11}@qq\\.com$";
    }

    // ------------------------------------------------------------ 学生名单导入

    @Data
    public static class StudentImport {
        /** skip | update | fail */
        private String strategy = "skip";
        /** 统一初始密码；留空则由系统随机生成 */
        private String defaultPassword;
        private int maxRows = 2000;
        private long maxFileSizeBytes = 5L * 1024 * 1024;
    }

    // ------------------------------------------------------------------ 配额

    @Data
    public static class Quota {
        /** 默认 2GB */
        private long defaultBytes = 2L * 1024 * 1024 * 1024;
    }

    // ------------------------------------------------------------------ 上传

    @Data
    public static class Upload {
        /** 单文件上限，默认 2GB */
        private long maxFileSizeBytes = 2L * 1024 * 1024 * 1024;
        /** 剩余空间低于该值拒绝签发上传凭证 */
        private long minFreeBytes = 1024 * 1024;
        /**
         * uploadToken 有效期（秒）。
         * <p>必须是「一个大文件在机房里传完」的量级，而不是几分钟：
         * 500MB 在共享出口带宽下可能要十几分钟，凭证中途失效会导致上传白做
         * （分片已上传，但拿不到新 URL 继续）。默认 2 小时。
         */
        private long uploadTokenSeconds = 7200;
        /** 超过该大小的文件用抽样指纹而不是全量 MD5 */
        private long instantThresholdBytes = 200L * 1024 * 1024;

        /**
         * 分类传输上限（字节），<b>覆盖</b> {@link #maxFileSizeBytes}。
         * <p>键取 {@link com.leaqutra.shworkcloud.service.FileViewType#transferClass(String)}
         * 的返回值，目前是 {@code video} 与 {@code archive}。
         * <p>用途：压缩包与视频往往是"整包搬运"，在机房共享出口带宽下会拖垮全班，
         * 所以单独压到 100MB；其余类型仍走全局的 2GB。
         */
        private Map<String, Long> transferLimits = new LinkedHashMap<>(Map.of(
                "video", 100L * 1024 * 1024,
                "archive", 100L * 1024 * 1024));

        /**
         * 上传整个文件夹时，所有文件的<b>总大小</b>上限（字节），默认 50MB。
         * <p>文件夹上传是"批量搬运"，单个文件都不大但加一起很容易把带宽吃满，
         * 所以在客户端<b>开始传之前</b>就要按总量拦一次。
         * <p>注意这是客户端预检（服务端看不到"这是一次文件夹上传"），
         * 用于快速失败与提示，不构成安全边界 —— 单文件上限仍然由服务端强制。
         */
        private long folderMaxTotalBytes = 50L * 1024 * 1024;
    }

    // ---------------------------------------------------------------- 验证码

    @Data
    public static class Captcha {
        private long imageUrlExpireSeconds = 300;
        private long sessionExpireSeconds = 300;
        private int maxFail = 3;
        /** 点选容差半径（按原图坐标计算） */
        private int clickTolerancePx = 30;
        private long idCacheSeconds = 60;
    }

    // ------------------------------------------------------------------ 安全

    @Data
    public static class Security {
        /** 内网网段：这些来源不做 IP 维度限流（避免机房同出口 IP 封杀全班） */
        private List<String> internalNetworks = new ArrayList<>(List.of(
                "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.1/32"));
        /** 是否位于可信反向代理之后；直连部署必须为 false，否则 XFF 可被伪造 */
        private boolean trustProxy = true;
        private int loginMaxFail = 5;
        private int loginLockMinutes = 10;
        private long publicIpHourLimit = 200;
        /** 允许跨域的来源列表；留空表示仅同源（生产推荐留空，由 Nginx 同源托管） */
        private List<String> corsAllowedOrigins = new ArrayList<>();
    }

    // ---------------------------------------------------------------- 回收站

    @Data
    public static class Recycle {
        /**
         * 是否启用回收站。
         * <p>关闭后「删除文件」= 立即删除索引<b>并删除 OSS 对象</b>（不可还原），
         * 适合希望 OSS 里不留任何过渡对象的场景。默认开启。
         */
        private boolean enabled = true;

        /** 回收站保留天数；到期由定时任务彻底删除并回收 OSS 对象 */
        private int retentionDays = 30;
    }

    // ---------------------------------------------------------- OSS 整洁性

    @Data
    public static class Storage {
        private Reconcile reconcile = new Reconcile();

        /**
         * OSS 与数据库的对账（保证 Bucket 里没有无人引用的垃圾对象）。
         * <p>扫描 homework/ 与 avatar/ 前缀，把「数据库里查不到引用」且「超过宽限期」
         * 的对象删除。宽限期是为了不误删正在上传中的对象。
         */
        @Data
        public static class Reconcile {
            private boolean enabled = true;
            /** 宽限期（小时）：比这更新的对象一律不动，避免误删上传中的文件 */
            private int graceHours = 24;
            /** 单次最多扫描多少个对象，防止 Bucket 很大时任务跑太久 */
            private int maxObjects = 200000;
        }
    }

    // ------------------------------------------------------------------ 机房

    @Data
    public static class Classroom {
        /** 前端空闲提醒分钟数，应与 sa-token.active-timeout 配合 */
        private int idleLogoutMinutes = 20;
        /** 下课倒计时提醒分钟数 */
        private int checkoutWarnMinutes = 5;
    }

    // ------------------------------------------------------------------ 超管

    @Data
    public static class Admin {
        private String initUsername = "admin";
        private String initEmail;
        /** 留空则应用启动失败（不允许空密码初始化超管） */
        private String initPassword;
    }
}
