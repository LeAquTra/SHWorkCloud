package com.leaqutra.shworkcloud.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.CommunityDto;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.PostService;
import com.leaqutra.shworkcloud.vo.CommunityVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 绀惧尯瀹℃牳锛堝悗鍙帮級銆? * <p>
 * <b>鏉冮檺鍙ｅ緞鏄€岀鐞嗗憳鍙婁互涓娿€嶏紝鑰屼笖鏄笁涓鑹茬殑鏄惧紡鏋氫妇锛?/b>
 * {@code admin} / {@code teacher} / {@code super_admin}銆? * <p>鈿狅笍 <b>缁濅笉鑳藉啓鎴?{@code @SaCheckRole("admin")} 涔嬪鐨勬暟鍊兼瘮杈?/b>锛? * 鏈」鐩殑瑙掕壊缂栧彿涓嶆槸鏈夊簭绛夌骇锛? 瀛︾敓 / 1 绠＄悊鍛?/ 2 鏁欏笀 / 9 瓒呯锛夛紝
 * 鏁欏笀(2) 鐨勬暟鍊兼瘮绠＄悊鍛?1) 澶э紝浣?Sa-Token 鐨勮鑹插瓧绗︿覆鏄淳鐢熷€? * 锛堣 {@code StpInterfaceImpl}锛? 鈫?super_admin+admin+teacher锛? * 1 鈫?admin+teacher锛? 鈫?teacher锛夆€斺€?鎵€浠ユ妸鏁欏笀涔熺畻杩?绠＄悊鍛樹互涓?锛? * 蹇呴』鏄惧紡鍒楀嚭 {@code teacher}锛屼笉鑳介潬 {@code admin} 涓€涓爣绛俱€? * <p>闇€姹傚師鏂囨槸"绠＄悊鍛樹互涓婂鏍搁€氳繃"锛岃繖閲屾妸<b>鏁欏笀</b>涔熺撼鍏ワ細
 * 鏁欏笀灏辨槸鏈烘埧绠＄悊鍛橈紝璇惧爞涓婇渶瑕佽兘澶勭悊瀛︾敓鍙戠殑鍐呭銆? * <p>涓ゅ眰闃茬嚎锛歿@code SaTokenConfigure} 鐨勬暀甯堢櫧鍚嶅崟閲屽凡鍔犲叆 {@code /admin/posts/**}锛? * 璺敱瑙勫垯涓嶅啀鎶婃暀甯堟尅鍦ㄥ闈紱鐒跺悗杩欓噷鐨勬敞瑙ｅ啀閫愪釜鏂规硶鏀剁揣銆? * Service 灞傜殑 {@code PostService.requireReviewer()} 杩樹細鐙珛鏍￠獙涓€娆?鈥斺€? * 娉ㄨВ鍙 Controller 鐢熸晥锛屽皢鏉ヨ嫢鏈変汉浠庡埆澶勮皟 Service锛岄偅閬撻槻绾夸粛鐒跺湪銆? * <p>
 * 馃敶 <b>{@code mode = SaMode.OR} 涓嶈兘鐪侊紙鐪熷疄鏁呴殰锛?/b>锛歋a-Token 鐨? * {@code @SaCheckRole} 榛樿鏄?<b>AND</b> 璇箟 鈥斺€?瑕佹眰鍚屾椂鍏峰鍒楀嚭鐨?b>鍏ㄩ儴</b>瑙掕壊銆? * 鑰屾湰椤圭洰鐨勮鑹叉槸娲剧敓鐨勶紙瑙?{@code StpInterfaceImpl}锛夛紝鍙湁瓒呯鍚屾椂鎷ユ湁
 * {@code super_admin + admin + teacher}锛涚鐞嗗憳(1) 鍙湁 {@code admin + teacher}锛? * 鏁欏笀(2) 鍙湁 {@code teacher}銆備簬鏄笉鍐?OR 鏃讹細
 * <pre>
 *   瓒呯   鈫?admin 鉁?teacher 鉁?super_admin 鉁?鈫?閫氳繃
 *   绠＄悊鍛?鈫?admin 鉁?teacher 鉁?super_admin 鉁?鈫?<b>403</b>锛堢敤鎴锋姤鐨勫氨鏄繖涓級
 *   鏁欏笀   鈫?admin 鉁?                         鈫?403
 * </pre>
 * 琛ㄧ幇灏辨槸"绀惧尯瀹℃牳鍙湁瓒呯骇绠＄悊鍛樿兘杩?銆傛湰椤圭洰鍏跺畠 Controller 鐨勫瑙掕壊娉ㄨВ
 * <b>鍏ㄩ兘鍐欎簡 OR</b>锛圓dminUserController / AdminCaptchaController /
 * AdminImportController锛夛紝鏈枃浠跺綋鍒濇槸鍞竴婕忔帀鐨勩€? * 鍥炲綊瀹堝崼锛歿@code AdminPostAuthorizationTest}銆亄@code CommunityContractTest}銆? */
@RestController
@RequestMapping("/admin/posts")
@RequiredArgsConstructor
public class AdminPostController {

    private final PostService postService;
    private final ClientIpUtil clientIpUtil;

    /**
     * 瀹℃牳闃熷垪銆?     * <pre>
     *   GET /api/admin/posts?status=0          # 鍙湅寰呭鏍革紙榛樿锛屾寜鎻愪氦鏃堕棿鍊掑簭锛?     *   GET /api/admin/posts?status=1&amp;page=1   # 宸查€氳繃
     *   GET /api/admin/posts?status=2          # 宸叉嫆缁?     *   GET /api/admin/posts                   # 鍏ㄩ儴
     * </pre>
     * 杩斿洖閲屽甫 {@code pendingTotal}锛屽墠绔彲浠ヤ竴鐩存樉绀?杩樻湁 N 鏉″緟瀹?銆?     */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @GetMapping
    public R<CommunityVo.ReviewPage> queue(CommunityDto.ReviewQuery query) {
        return R.ok(postService.reviewPage(query));
    }

    /** 寰呭鏍告暟閲忥紙鍚庡彴瀵艰埅瑙掓爣锛岃疆璇㈢敤锛屽搷搴斾綋鏈€灏忥級 */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/pending-count")
    public R<Long> pendingCount() {
        return R.ok(postService.pendingCount());
    }

    /**
     * 閫氳繃 / 鎷掔粷銆?     * <pre>
     *   POST /api/admin/posts/review   {"postId": 12, "approve": true}
     *   POST /api/admin/posts/review   {"postId": 12, "approve": false, "rejectReason": "鍚笉鑹俊鎭?}
     * </pre>
     * 鎷掔粷鐞嗙敱浣滆€呭彲瑙侊紙寤鸿濉絾涓嶅己鍒讹紝鐞嗙敱瑙?{@code PostRules.normalizeRejectReason}锛夈€?     * 鍙兘澶勭悊寰呭鏍哥殑甯栧瓙锛氶噸澶嶅鐞嗕細杩斿洖 40097锛岃€屼笉鏄潤榛樿鐩栧墠涓€涓汉鐨勭粨璁恒€?     */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @PostMapping("/review")
    public R<CommunityVo.ActionResult> review(@RequestBody CommunityDto.ReviewReq req,
                                              HttpServletRequest request) {
        return R.ok(postService.review(req, clientIpUtil.get(request)));
    }

    /**
     * 鍗曟潯璇︽儏锛堝鏍告椂鐐瑰紑鐪嬪畬鏁村唴瀹癸級銆?     * <p>涓庣敤鎴风 {@code GET /community/posts/{id}} 鐨勫尯鍒槸<b>涓嶅彈鍙鎬ч檺鍒?/b>锛?     * 寰呭鍐呭瀹℃牳鍛樺繀椤昏兘鐪嬪埌锛屽惁鍒欐病娉曞銆?     */
    @SaCheckRole(value = {"admin", "teacher", "super_admin"}, mode = SaMode.OR)
    @GetMapping("/{id}")
    public R<CommunityVo.Post> detail(@PathVariable Long id) {
        return R.ok(postService.detail(id));
    }
}

