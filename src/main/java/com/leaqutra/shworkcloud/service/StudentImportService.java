package com.leaqutra.shworkcloud.service;

import com.leaqutra.shworkcloud.common.BizException;
import com.leaqutra.shworkcloud.common.ErrorCode;
import com.leaqutra.shworkcloud.config.AppProperties;
import com.leaqutra.shworkcloud.entity.SysUser;
import com.leaqutra.shworkcloud.mapper.UserMapper;
import com.leaqutra.shworkcloud.security.PasswordGenerator;
import com.leaqutra.shworkcloud.security.PasswordHasher;
import com.leaqutra.shworkcloud.security.LoginUser;
import com.leaqutra.shworkcloud.vo.AdminVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 学生名单批量导入。
 * <p>
 * 这是机房场景的<b>主开户路径</b>：老师课前导入名单，学生用「学号 + 初始密码」登录，
 * 完全绕开邮箱验证码，45 分钟的课上不会被注册流程卡住。
 * <p>
 * 两个实现取舍：
 * <ul>
 *   <li><b>先全校验再写库</b>：避免「写了 20 行才因为第 21 行失败而回滚」这种让老师困惑的结果；</li>
 *   <li><b>不加大事务</b>：skip/update 策略下逐行独立提交，
 *       一行失败不影响其他行（与文档 §6.5.2 的建议一致）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentImportService {

    private static final Pattern STUDENT_NO = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");

    /** 表头别名，兼容中英文与常见写法 */
    private static final Map<String, String> HEADER_ALIAS = new HashMap<>();

    static {
        HEADER_ALIAS.put("学号", "studentNo");
        HEADER_ALIAS.put("studentno", "studentNo");
        HEADER_ALIAS.put("student_no", "studentNo");
        HEADER_ALIAS.put("姓名", "realName");
        HEADER_ALIAS.put("realname", "realName");
        HEADER_ALIAS.put("real_name", "realName");
        HEADER_ALIAS.put("班级", "className");
        HEADER_ALIAS.put("classname", "className");
        HEADER_ALIAS.put("class_name", "className");
        HEADER_ALIAS.put("初始密码", "password");
        HEADER_ALIAS.put("密码", "password");
        HEADER_ALIAS.put("password", "password");
        HEADER_ALIAS.put("容量gb", "quotaGb");
        HEADER_ALIAS.put("容量", "quotaGb");
        HEADER_ALIAS.put("quotagb", "quotaGb");
        HEADER_ALIAS.put("quota", "quotaGb");
    }

    private final UserMapper userMapper;
    private final AppProperties appProperties;
    private final AuditService auditService;
    private final LoginUser loginUser;

    /** 解析并导入 */
    public AdminVo.ImportResultVo importCsv(byte[] content, String defaultClass, String clientIp) {
        long maxBytes = appProperties.getStudentImport().getMaxFileSizeBytes();
        if (content == null || content.length == 0) {
            throw new BizException(ErrorCode.IMPORT_FORMAT_ERROR, "文件为空");
        }
        if (content.length > maxBytes) {
            throw new BizException(ErrorCode.IMPORT_LIMIT_EXCEEDED,
                    "文件超过 " + (maxBytes / 1024 / 1024) + "MB");
        }

        List<List<String>> rows = CsvSupport.parse(CsvSupport.decode(content));
        if (rows.size() < 2) {
            throw new BizException(ErrorCode.IMPORT_FORMAT_ERROR, "文件至少需要表头与一行数据");
        }
        int maxRows = appProperties.getStudentImport().getMaxRows();
        if (rows.size() - 1 > maxRows) {
            throw new BizException(ErrorCode.IMPORT_LIMIT_EXCEEDED, "单次最多导入 " + maxRows + " 行");
        }

        Map<String, Integer> columns = resolveColumns(rows.get(0));
        if (!columns.containsKey("studentNo")) {
            throw new BizException(ErrorCode.IMPORT_FORMAT_ERROR, "未找到「学号」列");
        }

        // ---------- 第一阶段：全部解析并校验 ----------
        List<ParsedRow> parsed = new ArrayList<>();
        List<AdminVo.ImportFailureVo> failures = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            int excelRow = i + 1;   // 表头占第 1 行
            List<String> row = rows.get(i);
            try {
                parsed.add(parseRow(excelRow, row, columns, defaultClass));
            } catch (BizException e) {
                failures.add(new AdminVo.ImportFailureVo(excelRow, cell(row, columns, "studentNo"),
                        "FAIL", e.getMessage()));
            }
        }

        // fail 策略：任何一行不合法都不写库
        if ("fail".equalsIgnoreCase(appProperties.getStudentImport().getStrategy())
                && !failures.isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED,
                    "共 " + failures.size() + " 行校验失败，已按 fail 策略放弃整批导入：" 
                            + failures.get(0).reason());
        }

        // ---------- 第二阶段：逐行写库（各自独立提交） ----------
        String strategy = appProperties.getStudentImport().getStrategy();
        int success = 0;
        int skipped = 0;
        int updated = 0;
        for (ParsedRow row : parsed) {
            try {
                SysUser existing = userMapper.selectByLogin(row.studentNo);
                if (existing != null) {
                    if ("fail".equalsIgnoreCase(strategy)) {
                        failures.add(new AdminVo.ImportFailureVo(row.excelRow, row.studentNo,
                                "FAIL", "学号已存在"));
                        continue;
                    }
                    if ("update".equalsIgnoreCase(strategy)) {
                        userMapper.updateStudentInfo(existing.getId(), row.realName,
                                row.className, row.quotaBytes);
                        updated++;
                        continue;
                    }
                    skipped++;
                    failures.add(new AdminVo.ImportFailureVo(row.excelRow, row.studentNo,
                            "SKIP", "学号已存在，已跳过"));
                    continue;
                }

                SysUser user = new SysUser();
                user.setUsername(row.studentNo);
                user.setStudentNo(row.studentNo);
                user.setRealName(row.realName);
                user.setClassName(row.className);
                user.setNickname(row.realName);
                user.setPassword(PasswordHasher.encode(row.rawPassword));
                user.setRole((byte) SysUser.ROLE_STUDENT);
                user.setStatus((byte) 1);
                user.setDeleted((byte) 0);
                // 关键：首次登录强制改密，否则全班长期共用一个密码
                user.setPwdChanged((byte) 0);
                user.setStorageQuota(row.quotaBytes);
                user.setUsedStorage(0L);
                user.setLoginFailCount(0);
                userMapper.insert(user);
                success++;
            } catch (DuplicateKeyException e) {
                // 并发导入或历史脏数据：唯一索引兜底
                skipped++;
                failures.add(new AdminVo.ImportFailureVo(row.excelRow, row.studentNo,
                        "SKIP", "学号已存在（唯一索引冲突）"));
            } catch (Exception e) {
                log.warn("导入单行失败 studentNo={}", row.studentNo, e);
                failures.add(new AdminVo.ImportFailureVo(row.excelRow, row.studentNo,
                        "FAIL", "写入失败：" + e.getMessage()));
            }
        }

        int failed = (int) failures.stream().filter(f -> "FAIL".equals(f.type())).count();
        boolean defaultPwdUsed = !StringUtils.hasText(appProperties.getStudentImport().getDefaultPassword());
        long operatorId = loginUser.id();
        SysUser operator = userMapper.selectById(operatorId);
        auditService.log(operatorId, operator == null ? null : operator.getUsername(),
                "IMPORT_STUDENTS", "USER", null, clientIp, failed == 0,
                "total=%d,success=%d,update=%d,skip=%d,fail=%d".formatted(
                        rows.size() - 1, success, updated, skipped, failed));

        return new AdminVo.ImportResultVo(rows.size() - 1, success, skipped + updated, failed,
                defaultPwdUsed,
                defaultPwdUsed ? null : appProperties.getStudentImport().getDefaultPassword(),
                failures);
    }

    private Map<String, Integer> resolveColumns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String key = header.get(i) == null ? "" : header.get(i).trim().toLowerCase()
                    .replace(" ", "").replace("（", "(").replace("）", ")");
            String mapped = HEADER_ALIAS.get(key);
            if (mapped != null && !columns.containsKey(mapped)) {
                columns.put(mapped, i);
            }
        }
        return columns;
    }

    private ParsedRow parseRow(int excelRow, List<String> row, Map<String, Integer> columns,
                               String defaultClass) {
        String studentNo = cell(row, columns, "studentNo");
        String realName = cell(row, columns, "realName");
        String className = cell(row, columns, "className");
        String password = cell(row, columns, "password");
        String quotaGb = cell(row, columns, "quotaGb");

        if (!StringUtils.hasText(studentNo)) {
            throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED, "学号为空");
        }
        if (!STUDENT_NO.matcher(studentNo).matches()) {
            throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED,
                    "学号格式不正确（应为 3~32 位字母/数字/下划线/短横线）");
        }
        if (!StringUtils.hasText(realName)) {
            throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED, "姓名为空");
        }
        if (realName.length() > 50) {
            throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED, "姓名过长（最多 50 字符）");
        }
        String finalClass = StringUtils.hasText(className) ? className : defaultClass;
        if (finalClass != null && finalClass.length() > 100) {
            throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED, "班级名过长（最多 100 字符）");
        }

        String rawPassword = StringUtils.hasText(password)
                ? password
                : appProperties.getStudentImport().getDefaultPassword();
        if (!StringUtils.hasText(rawPassword)) {
            rawPassword = PasswordGenerator.random();
        }

        long quota = appProperties.getQuota().getDefaultBytes();
        if (StringUtils.hasText(quotaGb)) {
            try {
                double gb = Double.parseDouble(quotaGb);
                if (gb <= 0 || gb > 1024) {
                    throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED, "容量应在 0~1024GB 之间");
                }
                quota = (long) (gb * 1024 * 1024 * 1024);
            } catch (NumberFormatException e) {
                throw new BizException(ErrorCode.IMPORT_VALIDATE_FAILED, "容量不是合法数字：" + quotaGb);
            }
        }
        return new ParsedRow(excelRow, studentNo, realName, finalClass, rawPassword, quota);
    }

    private String cell(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= row.size()) {
            return "";
        }
        String value = row.get(index);
        return value == null ? "" : value.trim();
    }

    /** 导入结果里回显的"表头顺序"提示（前端模板下载用） */
    public static List<String> templateColumns() {
        return List.of("学号", "姓名", "班级", "初始密码", "容量GB");
    }

    private record ParsedRow(int excelRow, String studentNo, String realName, String className,
                             String rawPassword, long quotaBytes) {
    }
}
