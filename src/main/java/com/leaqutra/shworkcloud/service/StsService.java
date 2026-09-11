package com.leaqutra.shworkcloud.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.http.FormatType;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.aliyuncs.profile.IClientProfile;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.config.OssProperties;
import com.leaqutra.shworkcloud.config.StsProperties;
import com.leaqutra.shworkcloud.mapper.UploadSessionMapper;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.security.RateLimiter;
import com.leaqutra.shworkcloud.vo.FileVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 上传凭证签发：STS 临时凭证 + 服务端生成的 uploadKey/uploadToken。
 * <p>
 * <b>关于 STS SDK：</b>标准做法是引入 {@code com.aliyun:aliyun-java-sdk-sts} 并使用
 * {@code AssumeRoleRequest}。本环境本地仓库没有该 artifact 且无外网，
 * 因此改用 {@code aliyun-java-sdk-core} 的通用 RPC 调用
 * （{@link CommonRequest} + {@code Sts/2015-04-01/AssumeRole}），
 * 效果完全等价。将来补上该依赖后，把 {@link #assumeRole} 换成 AssumeRoleRequest 即可。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StsService {

    private static final String STS_DOMAIN = "sts.aliyuncs.com";
    private static final String STS_VERSION = "2015-04-01";
    private static final String STS_ACTION = "AssumeRole";

    private final OssProperties oss;
    private final StsProperties sts;
    private final AppProperties appProperties;
    private final UploadTokenService uploadTokenService;
    private final QuotaService quotaService;
    private final RateLimiter rateLimiter;
    private final LoginUser loginUser;

    /**
     * 签发上传凭证。
     * <p>顺序：限流 -> 配额预检 -> 生成 Key/Token -> 落 Redis 与会话表 -> AssumeRole。
     */
    public FileVo.StsVo issueUploadToken() {
        long userId = loginUser.id();
        rateLimiter.checkStsIssue(userId);

        // 1) 配额预检：剩余空间连 1MB 都不到就没必要签发凭证了
        quotaService.ensureFree(userId, appProperties.getUpload().getMinFreeBytes());

        // 2) 服务端生成 ObjectKey（不含扩展名：显示名与后缀以 commit 时的 name 为准）
        String uploadKey = ObjectKeys.newUserKey(oss.userPrefix(userId));

        // 3) 一次性令牌：Redis（5 分钟） + upload_session（孤儿对账用）
        String uploadToken = uploadTokenService.mint(userId, uploadKey);

        // 4) 换取 STS 临时凭证，Policy 收窄到本人前缀
        Credentials credentials = assumeRole(userId);

        return new FileVo.StsVo(
                credentials.accessKeyId(),
                credentials.accessKeySecret(),
                credentials.securityToken(),
                credentials.expiration(),
                oss.getRegion(),
                oss.getEndpoint(),
                oss.getBucketName(),
                oss.userPrefix(userId),
                uploadKey,
                uploadToken);
    }

    /** 动态收紧到 homework/{userId}/*，仅授予写入与分片相关权限 */
    private Credentials assumeRole(long userId) {
        String resource = "acs:oss:*:*:%s/%s/%d/*".formatted(
                oss.getBucketName(), oss.getKeyPrefix(), userId);
        String policy = """
                {"Version":"1","Statement":[{"Effect":"Allow",\
                "Action":["oss:PutObject","oss:ListParts","oss:AbortMultipartUpload"],\
                "Resource":["%s"]}]}""".formatted(resource);

        CommonRequest request = new CommonRequest();
        request.setSysMethod(MethodType.POST);
        request.setSysDomain(STS_DOMAIN);
        request.setSysVersion(STS_VERSION);
        request.setSysAction(STS_ACTION);
        request.setSysAccept(FormatType.JSON);
        request.putQueryParameter("RoleArn", sts.getRoleArn());
        request.putQueryParameter("RoleSessionName", "up-" + userId);
        request.putQueryParameter("DurationSeconds", String.valueOf(sts.getDurationSeconds()));
        request.putQueryParameter("Policy", policy);

        try {
            IClientProfile profile = DefaultProfile.getProfile(oss.getRegion(),
                    sts.getAccessKeyId(), sts.getAccessKeySecret());
            CommonResponse response = new DefaultAcsClient(profile).getCommonResponse(request);
            if (response.getHttpStatus() != 200) {
                // 不把 OSS/STS 原始报文返回给前端，避免泄露账号信息
                log.error("AssumeRole 失败 httpStatus={} body={}", response.getHttpStatus(), response.getData());
                throw new BizException(ErrorCode.STS_ERROR);
            }
            JSONObject json = JSONUtil.parseObj(response.getData());
            JSONObject cred = json.getJSONObject("Credentials");
            if (cred == null) {
                log.error("AssumeRole 响应缺少 Credentials: {}", response.getData());
                throw new BizException(ErrorCode.STS_ERROR);
            }
            return new Credentials(cred.getStr("AccessKeyId"), cred.getStr("AccessKeySecret"),
                    cred.getStr("SecurityToken"), cred.getStr("Expiration"));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("AssumeRole 调用异常 userId={}", userId, e);
            throw new BizException(ErrorCode.STS_ERROR);
        }
    }

    private record Credentials(String accessKeyId, String accessKeySecret,
                               String securityToken, String expiration) {
    }
}
