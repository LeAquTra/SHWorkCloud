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
