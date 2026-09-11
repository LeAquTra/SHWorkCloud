package com.leaqutra.shworkcloud.controller;

import com.leaqutra.shworkcloud.common.PageVO;
import com.leaqutra.shworkcloud.common.R;
import com.leaqutra.shworkcloud.dto.FileDto;
import com.leaqutra.shworkcloud.service.FileService;
import com.leaqutra.shworkcloud.vo.FileVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 回收站。
 */
@RestController
@RequestMapping("/recycle")
@RequiredArgsConstructor
public class RecycleController {

    private final FileService fileService;

    @GetMapping
    public R<PageVO<FileVo.FileItemVo>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "50") long size) {
        return R.ok(fileService.recycleList(page, size));
    }

    /** 还原；原父目录若还在回收站会一并还原，原名被占用时自动改名 */
    @PostMapping("/restore")
    public R<Integer> restore(@RequestBody FileDto.IdsReq req) {
        return R.ok(fileService.restore(req.ids()));
    }

    /** 彻底删除：删索引 + 释放容量，OSS 对象在事务提交后删 */
    @DeleteMapping("/purge")
    public R<Integer> purge(@RequestBody FileDto.IdsReq req) {
        return R.ok(fileService.purge(req.ids()));
    }

    @DeleteMapping("/empty")
    public R<Integer> empty() {
        return R.ok(fileService.emptyRecycle());
    }
}
