package com.leaqutra.shworkcloud.service;

/**
 * 人机验证的开关判定。
 * <p>
 * 抽成纯静态类是为了可单测，也让"这一次到底要不要弹验证码"只在<b>一处</b>定义 ——
 * 登录、注册发邮件码、申请上传凭证三个场景都调用 {@link #required}。
 * <p>
 * <b>为什么要三重与运算</b>（总开关 + 场景开关 + 题库可用）：
 * 图片验证码来自后台题库，题库完全可能为空（全新部署、题目被误删）。
 * 如果只看配置开关，题库为空时登录接口会一直返回「请先完成人机验证」，
 * 而用户根本没法完成 —— 等于把所有人挡在门外。
 * 所以"题库里一张可用题目都没有"必须自动降级为"不要求"，这是本类的核心护栏。
 */
public final class CaptchaRules {

    /** 人机验证的场景，用于日志与"该场景是否要求"的查询 */
    public enum Scope {
        LOGIN("登录"),
        REGISTER("注册"),
        UPLOAD("上传");

        private final String label;

        Scope(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private CaptchaRules() {
    }

    /**
     * 该场景此刻是否真的要求人机验证。
     *
     * @param masterEnabled {@code app.captcha.enabled}：总开关
     * @param scopeEnabled  该场景的开关（如 {@code app.captcha.require-on-login}）
     * @param bankUsable    题库里是否有可用题目（至少一张启用中的题）
     */
    public static boolean required(boolean masterEnabled, boolean scopeEnabled, boolean bankUsable) {
        return masterEnabled && scopeEnabled && bankUsable;
    }
}
