package com.leaqutra.shworkcloud.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.dto.AuthDto;
import com.leaqutra.shworkcloud.dto.CaptchaSession;
import com.leaqutra.shworkcloud.entity.CaptchaImage;
import com.leaqutra.shworkcloud.mapper.CaptchaImageMapper;
import com.leaqutra.shworkcloud.security.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 图片验证码：从 OSS 随机取图，答案只留在服务端。
 * <p>
 * 安全要点：
 * <ul>
 *   <li>答案与坐标标注存在 Redis 会话中，<b>永不下发</b>；</li>
 *   <li>图片只给短期签名 URL，且 {@code captcha/} 前缀不在用户 STS Policy 内，
 *       用户无法枚举或篡改题库；</li>
 *   <li>单题最多错 N 次即作废；通过后签发一次性 passToken，
 *       发邮件前必须消费它，防止绕过图片验证刷邮件。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private static final String SESSION_KEY = "cap:session:";
    private static final String PASS_KEY = "cap:pass:";
    private static final String USED_KEY = "cap:pass:used:";
    private static final String ENABLED_CACHE_KEY = "cap:enabled";
    /** 通过一次人机验证后，该用户在上传场景的免验证窗口 */
    private static final String UPLOAD_PASS_KEY = "cap:upload:ok:";

    private final CaptchaImageMapper captchaImageMapper;
    private final StringRedisTemplate redis;
    private final OssSignService ossSignService;
    private final AppProperties appProperties;
    private final RateLimiter rateLimiter;

    // ---------------------------------------------------------------- 出题

    /** 出题：加权随机抽一题 -> 签名图片 URL -> Redis 建答题会话 */
    public com.leaqutra.shworkcloud.vo.CaptchaVo.CaptchaVoBody issue(String clientIp) {
        rateLimiter.checkCaptchaFetch(clientIp);

        List<CaptchaImage> candidates = enabledWeights();
        if (candidates.isEmpty()) {
            throw new BizException(ErrorCode.CAPTCHA_EMPTY);
        }
        CaptchaImage picked = weightedPick(candidates);
        CaptchaImage image = captchaImageMapper.selectById(picked.getId());
        if (image == null || image.getStatus() == null || image.getStatus() != 1) {
            throw new BizException(ErrorCode.CAPTCHA_EMPTY);
        }

        long expireSeconds = appProperties.getCaptcha().getImageUrlExpireSeconds();
        String imageUrl = ossSignService.presignedObjectUrl(image.getObjectKey(), expireSeconds);

        String captchaId = ObjectKeys.uuid32();
        CaptchaSession session = new CaptchaSession(image.getId(), image.getType(), image.getAnswer(),
                image.getDataJson(), appProperties.getCaptcha().getMaxFail());
        redis.opsForValue().set(SESSION_KEY + captchaId, JSONUtil.toJsonStr(session),
                Duration.ofSeconds(appProperties.getCaptcha().getSessionExpireSeconds()));
        captchaImageMapper.incrUsedCount(image.getId());

        return new com.leaqutra.shworkcloud.vo.CaptchaVo.CaptchaVoBody(
                captchaId, image.getType(), imageUrl, image.getWidth(), image.getHeight(),
                buildPrompts(image));
    }

    /**
     * 加权随机抽样。
     * <p>刻意不用 {@code ORDER BY weight*RAND()}：那会全表扫描。
     * 这里在内存里对「启用题目」做累积权重抽样，题目集合另有 60 秒缓存。
     */
    private CaptchaImage weightedPick(List<CaptchaImage> candidates) {
        long total = 0;
        for (CaptchaImage c : candidates) {
            total += Math.max(1, c.getWeight() == null ? 100 : c.getWeight());
        }
        long hit = ThreadLocalRandom.current().nextLong(total);
        long cursor = 0;
        for (CaptchaImage c : candidates) {
            cursor += Math.max(1, c.getWeight() == null ? 100 : c.getWeight());
            if (hit < cursor) {
                return c;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private List<CaptchaImage> enabledWeights() {
        String cached = redis.opsForValue().get(ENABLED_CACHE_KEY);
        if (cached != null) {
            try {
                return JSONUtil.toList(cached, CaptchaImage.class);
            } catch (Exception ignored) {
                redis.delete(ENABLED_CACHE_KEY);
            }
        }
        List<CaptchaImage> list = captchaImageMapper.selectEnabledWeights();
        redis.opsForValue().set(ENABLED_CACHE_KEY, JSONUtil.toJsonStr(list),
                Duration.ofSeconds(appProperties.getCaptcha().getIdCacheSeconds()));
        return list;
    }

    /**
     * 构造可下发的提示信息。
     * <p>题型 2：选项标签（不含正确项）。
     * 题型 3：按答案要求的顺序给出要点击的文字标签 —— 这是题型本身的设计
     * （「按顺序点击图中文字」），坐标与点 ID 绝不下发。
     */
    private List<String> buildPrompts(CaptchaImage image) {
        if (!StringUtils.hasText(image.getDataJson())) {
            return List.of();
        }
        JSONArray array;
        try {
            array = JSONUtil.parseArray(image.getDataJson());
        } catch (Exception e) {
            log.warn("验证码题目 data_json 解析失败 id={}", image.getId());
            return List.of();
        }
        int type = image.getType() == null ? CaptchaImage.TYPE_TEXT : image.getType();
        if (type == CaptchaImage.TYPE_SINGLE) {
            List<String> labels = new ArrayList<>();
            for (Object o : array) {
                JSONObject item = (JSONObject) o;
                String label = item.getStr("label");
                if (StringUtils.hasText(label)) {
                    labels.add(label);
                }
            }
            return labels;
        }
        if (type == CaptchaImage.TYPE_CLICK) {
            Map<Integer, String> labelById = new LinkedHashMap<>();
            for (Object o : array) {
                JSONObject item = (JSONObject) o;
                Integer id = item.getInt("id");
                String label = item.getStr("label");
                if (id != null && StringUtils.hasText(label)) {
                    labelById.put(id, label);
                }
            }
            List<String> ordered = new ArrayList<>();
            for (Integer id : parseAnswerIds(image.getAnswer())) {
                String label = labelById.get(id);
                if (label != null) {
                    ordered.add(label);
                }
            }
            return ordered;
        }
        return List.of();
    }

    // ---------------------------------------------------------------- 校验

    /** 校验作答；通过后签发一次性 passToken */
    public String verify(AuthDto.CaptchaVerifyReq req) {
        if (!StringUtils.hasText(req.captchaId())) {
            throw new BizException(ErrorCode.CAPTCHA_EXPIRED);
        }
        String key = SESSION_KEY + req.captchaId();
        String json = redis.opsForValue().get(key);
        if (json == null) {
            throw new BizException(ErrorCode.CAPTCHA_EXPIRED);
        }
        CaptchaSession session;
        try {
            session = JSONUtil.toBean(json, CaptchaSession.class);
        } catch (Exception e) {
            redis.delete(key);
            throw new BizException(ErrorCode.CAPTCHA_EXPIRED);
        }

        boolean ok = switch (session.getType() == null ? 0 : session.getType()) {
            case CaptchaImage.TYPE_TEXT -> StringUtils.hasText(req.answer())
                    && session.getAnswer() != null
                    && session.getAnswer().trim().equalsIgnoreCase(req.answer().trim());
            case CaptchaImage.TYPE_SINGLE -> StringUtils.hasText(req.answer())
                    && session.getAnswer() != null
                    && session.getAnswer().trim().equalsIgnoreCase(req.answer().trim());
            case CaptchaImage.TYPE_CLICK -> matchClicks(session, req.clicks());
            default -> false;
        };

        if (!ok) {
            int left = (session.getFailLeft() == null ? appProperties.getCaptcha().getMaxFail()
                    : session.getFailLeft()) - 1;
            if (left <= 0) {
                redis.delete(key);
            } else {
                session.setFailLeft(left);
                redis.opsForValue().set(key, JSONUtil.toJsonStr(session),
                        Duration.ofSeconds(appProperties.getCaptcha().getSessionExpireSeconds()));
            }
            throw new BizException(ErrorCode.CAPTCHA_WRONG,
                    left > 0 ? "答案错误，还可尝试 " + left + " 次" : "答案错误，本题已作废，请换一张");
        }

        redis.delete(key);
        String passToken = ObjectKeys.uuid32();
        redis.opsForValue().set(PASS_KEY + passToken, "1",
                Duration.ofSeconds(appProperties.getCaptcha().getSessionExpireSeconds()));
        return passToken;
    }

    /**
     * 点选校验：按答案要求的点 ID 顺序逐个匹配点击坐标，带容差半径。
     * <p>容差按<b>原图</b>坐标计算，前端必须把显示坐标按缩放比换算回原图坐标。
     */
    private boolean matchClicks(CaptchaSession session, List<AuthDto.ClickPoint> clicks) {
        List<Integer> expectedIds = parseAnswerIds(session.getAnswer());
        if (expectedIds.isEmpty() || clicks == null || clicks.size() != expectedIds.size()) {
            return false;
        }
        if (!StringUtils.hasText(session.getDataJson())) {
            return false;
        }
        Map<Integer, int[]> points = new LinkedHashMap<>();
        for (Object o : JSONUtil.parseArray(session.getDataJson())) {
            JSONObject item = (JSONObject) o;
            Integer id = item.getInt("id");
            Integer x = item.getInt("x");
            Integer y = item.getInt("y");
            if (id != null && x != null && y != null) {
                points.put(id, new int[]{x, y});
            }
        }
        int tolerance = appProperties.getCaptcha().getClickTolerancePx();
        long toleranceSq = (long) tolerance * tolerance;
        for (int i = 0; i < expectedIds.size(); i++) {
            int[] point = points.get(expectedIds.get(i));
            if (point == null) {
                return false;
            }
            AuthDto.ClickPoint click = clicks.get(i);
            long dx = click.x() - point[0];
            long dy = click.y() - point[1];
            if (dx * dx + dy * dy > toleranceSq) {
                return false;
            }
        }
        return true;
    }

    private List<Integer> parseAnswerIds(String answer) {
        List<Integer> ids = new ArrayList<>();
        if (!StringUtils.hasText(answer)) {
            return ids;
        }
        for (String part : answer.split(",")) {
            try {
                ids.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 脏数据：跳过
            }
        }
        return ids;
    }

    // ------------------------------------------------------------ passToken

    /** 发邮件码前消费一次性 passToken（失败即说明没走完图片验证或已被用过） */
    public void consumePassToken(String passToken) {
        if (!StringUtils.hasText(passToken)
                || !Boolean.TRUE.equals(redis.delete(PASS_KEY + passToken))) {
            throw new BizException(ErrorCode.CAPTCHA_PASS_INVALID);
        }
    }

    // -------------------------------------------------- 三个场景的人机验证

    /**
     * 题库此刻是否可用（至少有一张启用中的题目）。
     * <p>复用"启用题目"的 60 秒缓存，所以这个判断很便宜，可以直接放在登录路径上。
     */
    public boolean bankUsable() {
        return !enabledWeights().isEmpty();
    }

    /**
     * 该场景此刻是否<b>真的</b>要求人机验证。
     * <p>注意"配置要求"与"真的要求"是两回事：题库为空时后者为 false（见 {@link CaptchaRules}）。
     */
    public boolean required(CaptchaRules.Scope scope) {
        AppProperties.Captcha captcha = appProperties.getCaptcha();
        boolean scopeEnabled = switch (scope) {
            case LOGIN -> captcha.isRequireOnLogin();
            case REGISTER -> captcha.isRequireOnRegister();
            case UPLOAD -> captcha.isRequireOnUpload();
        };
        return CaptchaRules.required(captcha.isEnabled(), scopeEnabled, bankUsable());
    }

    /** 三个场景各自要不要验证码，供前端在动作之前就把窗口弹出来 */
    public com.leaqutra.shworkcloud.vo.AuthVo.HumanCheckVo humanCheck() {
        return new com.leaqutra.shworkcloud.vo.AuthVo.HumanCheckVo(
                required(CaptchaRules.Scope.LOGIN),
                required(CaptchaRules.Scope.REGISTER),
                required(CaptchaRules.Scope.UPLOAD),
                appProperties.getCaptcha().getSessionExpireSeconds());
    }

    /**
     * 登录前的人机验证。
     * <p>刻意<b>不消费</b> passToken：密码输错时不该逼用户再做一次验证码
     * （token 自身 5 分钟过期，而暴力破解另有 IP+账号限流与失败锁定兜着）。
     */
    public void checkLoginPass(String passToken) {
        if (!required(CaptchaRules.Scope.LOGIN)) {
            return;
        }
        if (hasPassToken(passToken)) {
            return;
        }
        throw new BizException(ErrorCode.CAPTCHA_REQUIRED);
    }

    /**
     * 发注册邮件码前的人机验证。
     * <p>这里必须<b>一次性消费</b>：否则同一个凭证能被反复用来刷邮件验证码。
     */
    public void checkRegisterPass(String passToken) {
        if (!required(CaptchaRules.Scope.REGISTER)) {
            return;
        }
        consumePassToken(passToken);
    }

    /**
     * 申请上传凭证前的人机验证。
     * <p>通过一次后开启"免验证窗口"：上传整个文件夹可能有几十个文件，
     * 每个文件都过一次验证码是不可用的设计。
     * <p>上传用的 passToken <b>不删除</b>（只校验存在性）：并发的多个文件会同时拿到
     * 「需要人机验证」，其中一个消费掉 token 后，其余的就会因为 token 已被删除而失败。
     * 让 token 随自身 TTL 自然过期、由免验证窗口接手，既没有竞争也不会削弱强度 ——
     * 窗口本身就允许在 10 分钟内反复申请凭证。
     */
    public void checkUploadPass(Long userId, String passToken) {
        if (!required(CaptchaRules.Scope.UPLOAD)) {
            return;
        }
        if (hasUploadWindow(userId)) {
            return;
        }
        if (hasPassToken(passToken)) {
            openUploadWindow(userId);
            return;
        }
        throw new BizException(ErrorCode.CAPTCHA_REQUIRED);
    }

    private boolean hasPassToken(String passToken) {
        return StringUtils.hasText(passToken)
                && Boolean.TRUE.equals(redis.hasKey(PASS_KEY + passToken));
    }

    private boolean hasUploadWindow(Long userId) {
        return userId != null && Boolean.TRUE.equals(redis.hasKey(UPLOAD_PASS_KEY + userId));
    }

    private void openUploadWindow(Long userId) {
        if (userId == null) {
            return;
        }
        long minutes = Math.max(1, appProperties.getCaptcha().getUploadPassMinutes());
        redis.opsForValue().set(UPLOAD_PASS_KEY + userId, "1", Duration.ofMinutes(minutes));
    }

    /** 标记某邮箱已完成图片验证（5 分钟内可提交注册） */
    public void markEmailVerified(String email, long seconds) {
        redis.opsForValue().set(USED_KEY + email, "1", Duration.ofSeconds(seconds));
    }

    public boolean isEmailVerified(String email) {
        return Boolean.TRUE.equals(redis.hasKey(USED_KEY + email));
    }

    public void clearEmailVerified(String email) {
        redis.delete(USED_KEY + email);
    }

    public void evictEnabledCache() {
        redis.delete(ENABLED_CACHE_KEY);
    }
}
