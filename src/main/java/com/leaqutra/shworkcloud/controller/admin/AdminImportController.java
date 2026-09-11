package com.leaqutra.shworkcloud.controller.admin;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaMode;
import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.security.ClientIpUtil;
import com.leaqutra.shworkcloud.service.StudentImportService;
import com.leaqutra.shworkcloud.vo.AdminVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 学生名单批量导入（机房场景的主开户路径）。
 */
@RestController
@RequestMapping("/admin/students")
@RequiredArgsConstructor
public class AdminImportController {

    private final StudentImportService studentImportService;
    private final ClientIpUtil clientIpUtil;

    /** 下载 CSV 模板（带 UTF-8 BOM，保证 Excel 打开不乱码） */
    @SaCheckRole(value = {"teacher", "admin", "super_admin"}, mode = SaMode.OR)
    @GetMapping(value = "/import-template", produces = "text/csv;charset=UTF-8")
    public byte[] template() {
        String header = String.join(",", StudentImportService.templateColumns());
        String sample = "20260001,张三,高一(3)班,,2\n20260002,李四,高一(3)班,,2\n";
        // BOM 让 Excel 正确识别 UTF-8
        byte[] bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = (header + "\n" + sample).getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(body, 0, result, bom.length, body.length);
        return result;
    }

    /** 导入名单：CSV（兼容 UTF-8 BOM 与 GBK） */
    @SaCheckRole(value = {"teacher", "admin", "super_admin"}, mode = SaMode.OR)
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<AdminVo.ImportResultVo> importCsv(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) String defaultClass,
                                              HttpServletRequest request) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_PARAM, "请选择要导入的 CSV 文件");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new BizException(ErrorCode.IMPORT_FORMAT_ERROR, "读取上传文件失败");
        }
        return R.ok(studentImportService.importCsv(content, defaultClass, clientIpUtil.get(request)));
    }
}
