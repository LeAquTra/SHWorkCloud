package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.AvatarService;
import com.leaqutra.shworkcloud.service.ContentDisposition;
import com.leaqutra.shworkcloud.service.DownloadTarget;
import com.leaqutra.shworkcloud.service.UserService;
import com.leaqutra.shworkcloud.vo.AuthVo;
import com.leaqutra.shworkcloud.vo.FriendVo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * 当前用户自助接口。
 * <p>
 * 可自助修改的只有个性属性；学号/姓名/班级是学籍数据，由教师通过名单导入维护。
 */
@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AvatarService avatarService;
    private final ClientIpUtil clientIpUtil;

    /** 当前用户资料（含个性属性、头像与容量） */
    @GetMapping("/profile")
    public R<AuthVo.UserProfileVo> profile() {
        return R.ok(userService.profile());
    }

    /**
     * 修改个性属性（部分更新）。
     * <p>
     * 允许的键：{@code nickname} / {@code signature} / {@code gender} / {@code birthday}。
     * <p>语义：<b>不传的键不会被改动</b>；传 null 或空串表示清空该属性（昵称不能清空）。
     * <p>头像不在这里改 —— 请用 {@code POST /user/avatar}。
     */
    @PutMapping("/profile")
    public R<AuthVo.UserProfileVo> updateProfile(@RequestBody Map<String, Object> body) {
        return R.ok(userService.updateProfile(body));
    }

    // ---------------------------------------------------------------- 头像

    /**
     * 上传/更换自定义头像。
     * <p>限制：**仅 JPG / PNG**，**≤ 5MB**，存阿里云 OSS。
     * 校验不信任文件名后缀与 Content-Type，而是校验 magic bytes 并用 ImageIO 真正解析一次。
     * <p>更换时会**自动删除 OSS 上的旧头像**，保证 Bucket 不堆积。
     *
     * @param file 表单字段名固定为 {@code file}
     */
    @PostMapping(value = "/avatar", consumes = "multipart/form-data")
    public R<AuthVo.UserProfileVo> uploadAvatar(@RequestPart("file") MultipartFile file,
                                                HttpServletRequest request) {
        avatarService.upload(file, clientIpUtil.get(request));
        // 直接返回最新资料，客户端不用再查一次
        return R.ok(userService.profile());
    }

    /** 清除头像（同时删除 OSS 对象） */
    @DeleteMapping("/avatar")
    public R<AuthVo.UserProfileVo> clearAvatar(HttpServletRequest request) {
        avatarService.clear(clientIpUtil.get(request));
        return R.ok(userService.profile());
    }

    /**
     * 以稳定的地址读取某个用户的头像。
     * <p>需要携带 {@code Authorization} 头，适合能自行设置请求头的客户端。
     * <p>⚠️ 浏览器 {@code <img src>} 无法携带请求头，那种场景请用资料接口里的
     * {@code avatarUrl}（1 小时有效的签名地址）。
     */
    @GetMapping("/avatar/{userId}")
    public void avatar(@PathVariable Long userId, HttpServletResponse response) throws IOException {
        try (DownloadTarget target = avatarService.open(userId)) {
            response.setStatus(HttpStatus.OK.value());
            response.setContentType(target.contentType());
            response.setContentLengthLong(target.contentLength());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline(target.fileName()));
            // 头像不敏感，允许客户端缓存 1 小时（换头像后 avatarVersion 会变，可绕过缓存）
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=3600");
            try (InputStream in = target.content();
                 OutputStream out = response.getOutputStream()) {
                in.transferTo(out);
                out.flush();
            }
        }
    }

    /**
     * 查看<b>别人</b>的公开主页（社区里点作者头像、好友列表点某人都走它）。
     * <p>
     * <b>字段是收窄过的</b>：只有昵称/姓名/班级/头像/签名/角色，以及"我与 TA 的关系"，
     * <b>没有</b>邮箱、生日、性别、容量、已用空间等 —— 详见
     * {@link UserService#publicProfile(long)} 的说明。
     * <p>
     * 路径用 {@code /user/{id}/profile} 而不是 {@code /user/profile/{id}}：
     * 与既有的 {@code GET /user/avatar/{userId}} 保持"资源在前、子资源在后"的一致形状，
     * 也避免与 {@code GET /user/profile} 产生路径歧义。
     * <p>注意：{@code /user/quota} 是固定路径，Spring 会优先精确匹配，
     * 不会被这里的 {@code {userId}} 抢走。
     */
    @GetMapping("/{userId}/profile")
    public R<FriendVo.UserCard> publicProfile(@PathVariable Long userId) {
        return R.ok(userService.publicProfile(userId));
    }

    /** 上传前的容量预检；同时给出回收站占用，便于提示"清空可释放" */
    @GetMapping("/quota")
    public R<AuthVo.QuotaVo> quota() {
        return R.ok(userService.quota());
    }
}
