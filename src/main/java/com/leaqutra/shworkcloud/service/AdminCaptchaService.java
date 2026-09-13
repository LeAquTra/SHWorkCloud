package com.leaqutra.shworkcloud.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.dto.AdminDto;
import com.leaqutra.shworkcloud.dto.CaptchaQuery;
import com.leaqutra.shworkcloud.entity.CaptchaImage;
import com.leaqutra.shworkcloud.mapper.CaptchaImageMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.AdminVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * 后台验证码题库管理。
 * <p>
 * 图片由<b>服务端</b>用主凭证写入 OSS {@code captcha/} 前缀
 * （该前缀不在用户 STS Policy 内，用户无法上传/篡改/枚举）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminCaptchaService {

    /** 图片单张上限 */
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    /**
     * 列表里图片签名地址的有效期。
     * <p>用 1 小时：与头像、相册预览一致。出题接口那个 5 分钟太短 ——
     * 后台可能开着页面慢慢翻题库，5 分钟后就整页图裂了。
     */
    private static final long IMAGE_URL_SECONDS = 3600;

    private final CaptchaImageMapper captchaImageMapper;
    private final OssSignService ossSignService;
    private final CaptchaService captchaService;
    private final AuditService auditService;
    private final LoginUser loginUser;

    /**
     * 题库列表。
     * <p>每项都带上服务端签发的 {@code imageUrl}，后台才能<b>在线阅览题目图片</b>；
     * 之前直接返回裸实体，前端只有 {@code objectKey}，根本显示不出图。
     */
    public PageVO<AdminVo.CaptchaItemVo> page(CaptchaQuery query) {
        LambdaQueryWrapper<CaptchaImage> wrapper = new LambdaQueryWrapper<>();
        if (query.getType() != null) {
            wrapper.eq(CaptchaImage::getType, query.getType());
        }
        if (query.getStatus() != null) {
            wrapper.eq(CaptchaImage::getStatus, query.getStatus());
        }
        wrapper.orderByDesc(CaptchaImage::getId);
        Page<CaptchaImage> page = new Page<>(query.normalizedPage(), query.normalizedSize());
        return PageVO.of(captchaImageMapper.selectPage(page, wrapper), this::toItemVo);
    }

    private AdminVo.CaptchaItemVo toItemVo(CaptchaImage image) {
        return new AdminVo.CaptchaItemVo(
                image.getId(), image.getType(), image.getObjectKey(), image.getAnswer(),
                image.getDataJson(), image.getWidth(), image.getHeight(), image.getWeight(),
                image.getUsedCount(),
                image.getStatus() == null ? null : image.getStatus().intValue(),
                image.getRemark(), signedImageUrl(image.getObjectKey()),
                image.getCreateTime());
    }

    /**
     * 签图片地址。
     * <p>单张签名失败（例如对象已被手工删除）只返回 null，不让整个列表 500 ——
     * 后台列表里坏一张图是可以接受的，整页打不开不行。
     */
    private String signedImageUrl(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        try {
            return ossSignService.presignedObjectUrl(objectKey, IMAGE_URL_SECONDS);
        } catch (Exception e) {
            log.warn("验证码图片签名失败 key={} err={}", objectKey, e.getMessage());
            return null;
        }
    }

    /** 题库概览：题库为空时注册接口不可用，后台需要醒目提示 */
    public long countEnabled() {
        return captchaImageMapper.countEnabled();
    }

    public long countAll() {
        return captchaImageMapper.countAll();
    }

    /** 上传图片到 OSS 并落库 */
    @Transactional(rollbackFor = Exception.class)
    public Long upload(MultipartFile file, AdminDto.CaptchaUpsertReq req, String clientIp) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "请选择要上传的图片");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE, "验证码图片不能超过 5MB");
        }
        String originalName = file.getOriginalFilename();
        String ext = FileNaming.extension(originalName == null ? "" : originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.BAD_PARAM,
                    "仅支持图片格式：" + String.join("/", ALLOWED_EXT));
        }
        validateUpsert(req);

        String objectKey = ObjectKeys.newCaptchaKey();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.OSS_ERROR, "读取上传文件失败");
        }
        ossSignService.put(objectKey, content, file.getContentType());

        CaptchaImage image = new CaptchaImage();
        image.setType(req.type());
        image.setObjectKey(objectKey);
        image.setAnswer(req.answer().trim());
        image.setDataJson(normalizeJson(req.dataJson()));
        image.setWidth(req.width() == null ? 0 : req.width());
        image.setHeight(req.height() == null ? 0 : req.height());
        image.setWeight(req.weight() == null || req.weight() <= 0 ? 100 : req.weight());
        image.setUsedCount(0L);
        image.setStatus((byte) 1);
        image.setRemark(req.remark());
        image.setCreatedBy(loginUser.id());
        captchaImageMapper.insert(image);

        captchaService.evictEnabledCache();
        auditService.log(loginUser.id(), "CAPTCHA_UPLOAD", "CAPTCHA",
                String.valueOf(image.getId()), clientIp, true, "type=" + req.type());
        return image.getId();
    }

    /** 只改图片数据，不动 OSS 对象 */
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AdminDto.CaptchaUpsertReq req, String clientIp) {
        CaptchaImage image = mustExist(id);
        validateUpsert(req);
        image.setType(req.type());
        image.setAnswer(req.answer().trim());
        image.setDataJson(normalizeJson(req.dataJson()));
        if (req.width() != null) {
            image.setWidth(req.width());
        }
        if (req.height() != null) {
            image.setHeight(req.height());
        }
        if (req.weight() != null && req.weight() > 0) {
            image.setWeight(req.weight());
        }
        image.setRemark(req.remark());
        captchaImageMapper.updateById(image);
        captchaService.evictEnabledCache();
        auditService.log(loginUser.id(), "CAPTCHA_UPDATE", "CAPTCHA", String.valueOf(id),
                clientIp, true, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void toggleStatus(Long id, byte status, String clientIp) {
        CaptchaImage image = mustExist(id);
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.BAD_PARAM, "状态值只能是 0 或 1");
        }
        captchaImageMapper.updateStatus(image.getId(), status);
        captchaService.evictEnabledCache();
        auditService.log(loginUser.id(), "CAPTCHA_STATUS", "CAPTCHA", String.valueOf(id),
                clientIp, true, "status=" + status);
    }

    /** 删除记录并删除 OSS 图片 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String clientIp) {
        CaptchaImage image = mustExist(id);
        captchaImageMapper.deleteById(id);
        captchaService.evictEnabledCache();
        // 删除 OSS 对象放最后：即使失败，题库记录已删，不影响出题
        ossSignService.delete(image.getObjectKey());
        auditService.log(loginUser.id(), "CAPTCHA_DELETE", "CAPTCHA", String.valueOf(id),
                clientIp, true, null);
    }

    // ---------------------------------------------------------------- 校验

    private void validateUpsert(AdminDto.CaptchaUpsertReq req) {
        if (req == null) {
            throw new BizException(ErrorCode.BAD_PARAM, "缺少题目数据");
        }
        Integer type = req.type();
        if (type == null || type < CaptchaImage.TYPE_TEXT || type > CaptchaImage.TYPE_CLICK) {
            throw new BizException(ErrorCode.BAD_PARAM, "题型只能是 1(字符) / 2(单选) / 3(点选)");
        }
        if (!StringUtils.hasText(req.answer())) {
            throw new BizException(ErrorCode.BAD_PARAM, "标准答案不能为空");
        }
        if (req.answer().length() > 255) {
            throw new BizException(ErrorCode.BAD_PARAM, "答案过长（最多 255 字符）");
        }

        // 单选与点选必须有结构化标注，且答案要能对应上
        if (type == CaptchaImage.TYPE_SINGLE || type == CaptchaImage.TYPE_CLICK) {
            if (!StringUtils.hasText(req.dataJson())) {
                throw new BizException(ErrorCode.BAD_PARAM,
                        type == CaptchaImage.TYPE_SINGLE ? "单选题必须提供选项 dataJson" : "点选题必须提供坐标 dataJson");
            }
            JSONArray array;
            try {
                array = JSONUtil.parseArray(req.dataJson());
            } catch (Exception e) {
                throw new BizException(ErrorCode.BAD_PARAM, "dataJson 不是合法 JSON 数组");
            }
            if (array.isEmpty()) {
                throw new BizException(ErrorCode.BAD_PARAM, "dataJson 不能为空数组");
            }
            if (type == CaptchaImage.TYPE_SINGLE) {
                Set<String> keys = new HashSet<>();
                for (Object o : array) {
                    JSONObject item = (JSONObject) o;
                    String key = item.getStr("k");
                    if (!StringUtils.hasText(key)) {
                        throw new BizException(ErrorCode.BAD_PARAM, "选项缺少 k 字段");
                    }
                    keys.add(key.trim());
                }
                if (!keys.contains(req.answer().trim())) {
                    throw new BizException(ErrorCode.BAD_PARAM,
                            "答案必须等于某个选项的 k 值，当前选项：" + keys);
                }
            } else {
                Set<Integer> ids = new HashSet<>();
                for (Object o : array) {
                    JSONObject item = (JSONObject) o;
                    Integer pointId = item.getInt("id");
                    Integer x = item.getInt("x");
                    Integer y = item.getInt("y");
                    if (pointId == null || x == null || y == null) {
                        throw new BizException(ErrorCode.BAD_PARAM,
                                "标注点必须包含 id / x / y");
                    }
                    ids.add(pointId);
                }
                for (String part : req.answer().split(",")) {
                    int pointId;
                    try {
                        pointId = Integer.parseInt(part.trim());
                    } catch (NumberFormatException e) {
                        throw new BizException(ErrorCode.BAD_PARAM,
                                "点选答案应为逗号分隔的点 ID，如 3,1,2");
                    }
                    if (!ids.contains(pointId)) {
                        throw new BizException(ErrorCode.BAD_PARAM,
                                "答案引用了不存在的标注点 id=" + pointId);
                    }
                }
            }
        }
    }

    private String normalizeJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        String trimmed = json.trim();
        // MySQL JSON 列不接受空字符串
        return trimmed.isEmpty() ? null : trimmed;
    }

    private CaptchaImage mustExist(Long id) {
        CaptchaImage image = captchaImageMapper.selectById(id);
        if (image == null) {
            throw new BizException(ErrorCode.FILE_NOT_FOUND, "题目不存在");
        }
        return image;
    }
}
