<#
.SYNOPSIS
    作业云盘后端 API 冒烟验证（无需前端）。

.DESCRIPTION
    按真实链路跑一遍：登录 -> 个性属性 -> 申请上传凭证 -> 直传 OSS -> 建立索引
    -> 列表 -> **下载回本地并逐字节校验** -> Range 断点续传 -> 回收站 -> 彻底删除。

    为什么要逐字节校验：下载是最容易"看起来成功、实际拿到损坏文件"的一环
    （Content-Length 不对、Range 边界错、中文文件名乱码都会在这里暴露）。

.PARAMETER BaseUrl
    后端地址，默认 http://localhost:8081/api

.PARAMETER Login
    登录名（学号或用户名）。不传则只跑公开接口（注册流程说明）。

.PARAMETER Password
    密码。

.PARAMETER FilePath
    要上传的本地文件。不传则自动生成一个带中文名的临时文件（顺便验证中文文件名下载）。

.PARAMETER CaptchaPassToken
    人机验证凭证。后台题库启用后，登录与申请上传凭证都需要它（业务码 40105）。
    获取方式：在浏览器里过一次验证码，从 POST /auth/captcha/verify 的响应里取
    captchaPassToken（默认 5 分钟内有效，且本项目**不消费**它，所以登录与上传可以共用一个）。
    也可以临时把 CAPTCHA_ENABLED 设为 false 并重启服务，跑完再改回来。

.EXAMPLE
    # 用超管账号跑全链路
    ./scripts/api-smoke.ps1 -Login admin -Password 'YourInitPassword'

.EXAMPLE
    # 上传指定文件
    ./scripts/api-smoke.ps1 -Login 20260001 -Password 'Sh@2026' -FilePath D:\demo.docx

.EXAMPLE
    # 题库已启用（登录需要人机验证）时
    ./scripts/api-smoke.ps1 -Login admin -Password 'YourInitPassword' -CaptchaPassToken 5f3c...
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8081/api',
    [string]$Login = '',
    [string]$Password = '',
    [string]$FilePath = '',
    [string]$DownloadDir = '',
    [string]$CaptchaPassToken = ''
)

$ErrorActionPreference = 'Stop'
$script:Failures = @()
$script:StepNo = 0

function Write-Step([string]$text) {
    $script:StepNo++
    Write-Host ""
    Write-Host ("[{0}] {1}" -f $script:StepNo, $text) -ForegroundColor Cyan
}

function Write-Ok([string]$text) {
    Write-Host ("    PASS  {0}" -f $text) -ForegroundColor Green
}

function Write-Fail([string]$text) {
    Write-Host ("    FAIL  {0}" -f $text) -ForegroundColor Red
    $script:Failures += $text
}

function Write-Info([string]$text) {
    Write-Host ("          {0}" -f $text) -ForegroundColor DarkGray
}

# ------------------------------------------------------------------ HTTP 封装

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null,
        [string]$Token = ''
    )
    $headers = @{}
    if ($Token) { $headers['Authorization'] = $Token }
    $args = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $headers
        ContentType = 'application/json; charset=utf-8'
    }
    if ($null -ne $Body) {
        $args['Body'] = ($Body | ConvertTo-Json -Depth 6 -Compress)
    }
    try {
        $resp = Invoke-RestMethod @args
    } catch {
        $detail = $_.Exception.Message
        $status = ''
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        }
        throw "HTTP $status $Method $Path 失败：$detail"
    }
    if ($null -eq $resp -or $null -eq $resp.code) {
        throw "响应不是统一格式（缺少 code）：$Method $Path"
    }
    if ($resp.code -ne 0) {
        throw "业务错误 code=$($resp.code) message=$($resp.message) （$Method $Path）"
    }
    return $resp.data
}

# ------------------------------------------------------------------ 内置测试图片
# 一张 8x8 PNG（蓝色）与一张（红色），用于验证"上传头像 / 更换头像 / 内容一致"。
# 直接内嵌字节而不是依赖 System.Drawing：后者在 .NET 6+ 上不再是默认可用的程序集。
$PngBlue = 'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAAUSURBVChTY3Bo+P8fHx4RCv7/BwAszq+BqW2XPwAAAABJRU5ErkJggg=='
$PngRed  = 'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAAUSURBVChTY/jv4PAfHx4RChz+AwBD1p+B26+pJgAAAABJRU5ErkJggg=='

# ------------------------------------------------------------------ multipart 上传
# PowerShell 5.1 没有 -Form 参数，因此手工拼 multipart 体，7.x 与 5.1 都能跑。
function Add-Utf8Bytes {
    param([System.IO.Stream]$Stream, [string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $Stream.Write($bytes, 0, $bytes.Length)
}

function New-MultipartBody {
    param([hashtable]$Fields, [string]$FileField, [System.IO.FileInfo]$File, [ref]$Boundary)
    $boundary = [System.Guid]::NewGuid().ToString('N')
    $Boundary.Value = $boundary
    $LF = "`r`n"
    $ms = New-Object System.IO.MemoryStream
    if ($Fields) {
        foreach ($key in $Fields.Keys) {
            Add-Utf8Bytes $ms ("--$boundary$LF")
            Add-Utf8Bytes $ms ("Content-Disposition: form-data; name=`"$key`"$LF$LF")
            Add-Utf8Bytes $ms ("$($Fields[$key])$LF")
        }
    }
    if ($File) {
        Add-Utf8Bytes $ms ("--$boundary$LF")
        Add-Utf8Bytes $ms ("Content-Disposition: form-data; name=`"$FileField`"; filename=`"$($File.Name)`"$LF")
        Add-Utf8Bytes $ms ("Content-Type: application/octet-stream$LF$LF")
        $fileBytes = [System.IO.File]::ReadAllBytes($File.FullName)
        $ms.Write($fileBytes, 0, $fileBytes.Length)
        Add-Utf8Bytes $ms $LF
    }
    Add-Utf8Bytes $ms ("--$boundary--$LF")
    $result = $ms.ToArray()
    $ms.Dispose()
    return $result
}

function Invoke-Multipart {
    param([string]$Path, [string]$Token, [System.IO.FileInfo]$File, [hashtable]$Fields)
    $boundary = ''
    $body = New-MultipartBody -Fields $Fields -FileField 'file' -File $File -Boundary ([ref]$boundary)
    $headers = @{}
    if ($Token) { $headers['Authorization'] = $Token }
    try {
        return Invoke-RestMethod -Method Post -Uri "$BaseUrl$Path" -Headers $headers `
            -ContentType "multipart/form-data; boundary=$boundary" -Body $body
    } catch {
        $status = ''
        if ($_.Exception.Response) { $status = [int]$_.Exception.Response.StatusCode }
        throw "HTTP $status POST $Path 失败：$($_.Exception.Message)"
    }
}

# ------------------------------------------------------------------ 准备文件

$tempCreated = $false
if (-not $FilePath) {
    # 故意用中文名：验证 Content-Disposition 的 RFC 5987 编码
    $FilePath = Join-Path ([System.IO.Path]::GetTempPath()) ("冒烟测试-{0}.txt" -f (Get-Date -Format 'HHmmss'))
    $content = "作业云盘冒烟测试`n生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n这是一段中文内容，用于校验下载后的字节一致性。`n"
    [System.IO.File]::WriteAllText($FilePath, $content, (New-Object System.Text.UTF8Encoding($false)))
    $tempCreated = $true
}
if (-not (Test-Path $FilePath)) {
    throw "找不到文件：$FilePath"
}
$file = Get-Item $FilePath
$sourceMd5 = (Get-FileHash -Path $file.FullName -Algorithm MD5).Hash.ToLower()
$fileBytes = [System.IO.File]::ReadAllBytes($file.FullName)

Write-Host "=== 作业云盘后端冒烟验证 ===" -ForegroundColor Yellow
Write-Info "后端地址：$BaseUrl"
Write-Info "测试文件：$($file.Name)  ($($file.Length) 字节)"
Write-Info "源文件 MD5：$sourceMd5"

# ------------------------------------------------------------------ 1. 公开配置

Write-Step '公开接口：注册流程说明'
try {
    $cfg = Invoke-Api -Method GET -Path '/auth/register-config'
    Write-Ok "registerEnabled=$($cfg.registerEnabled) requireImageCaptcha=$($cfg.requireImageCaptcha) mailEnabled=$($cfg.mailEnabled)"
    Write-Info $cfg.mailHint
} catch {
    Write-Fail $_.Exception.Message
}

Write-Step '公开接口：人机验证配置（登录/注册/上传）'
$humanCheck = $null
try {
    $humanCheck = Invoke-Api -Method GET -Path '/auth/human-check'
    Write-Ok "login=$($humanCheck.login) register=$($humanCheck.register) upload=$($humanCheck.upload) passTtl=$($humanCheck.passTtlSeconds)s"
    $needsCaptcha = $humanCheck.login -or $humanCheck.upload -or $humanCheck.register
    if ($needsCaptcha -and -not $CaptchaPassToken) {
        Write-Info '⚠️ 当前后台题库已启用：登录 / 申请上传凭证会返回 40105「请先完成人机验证」。'
        Write-Info '   处理方式（二选一）：'
        Write-Info '   A. 传 -CaptchaPassToken <凭证>：浏览器过一次验证码，从 /auth/captcha/verify 响应里取；'
        Write-Info '   B. 临时关闭：在服务器 env 里设 CAPTCHA_ENABLED=false 并重启，跑完冒烟再改回 true。'
    }
} catch {
    Write-Fail $_.Exception.Message
}

# ------------------------------------------------------------------ 2. 登录

if (-not $Login -or -not $Password) {
    Write-Host ""
    Write-Host "未提供 -Login/-Password，跳过需要登录的用例。" -ForegroundColor Yellow
    Write-Host "提示：可用自助注册接口先开一个账号，或直接传超管账号。" -ForegroundColor Yellow
} else {
    Write-Step "登录（$Login）"
    $token = ''
    try {
        $loginBody = @{ login = $Login; password = $Password }
        if ($CaptchaPassToken) { $loginBody['captchaPassToken'] = $CaptchaPassToken }
        $login = Invoke-Api -Method POST -Path '/auth/login' -Body $loginBody
        $token = $login.token
        Write-Ok "登录成功 userId=$($login.userId) role=$($login.role) mustChangePassword=$($login.mustChangePassword)"
        if ($login.mustChangePassword) {
            Write-Info '该账号处于"首次登录必须改密"状态：其它接口会被 40119 拦截。'
            Write-Info '请先调用 POST /auth/password 改密，或用已改密的账号重跑。'
        }
    } catch {
        if ($_.Exception.Message -match '40105|40103') {
            Write-Fail '登录需要人机验证（40105）：请传 -CaptchaPassToken，或临时设 CAPTCHA_ENABLED=false 后重启服务再跑'
            Write-Info '说明见 docs/部署运维手册.md 的「人机验证」一节'
        } else {
            Write-Fail $_.Exception.Message
        }
    }

    if ($token) {
        # ---------------------------------------------------------- 3. 个性属性
        Write-Step '个性属性：读取当前资料'
        $before = $null
        try {
            $before = Invoke-Api -Method GET -Path '/user/profile' -Token $token
            Write-Ok "username=$($before.username) nickname=$($before.nickname) gender=$($before.gender) birthday=$($before.birthday)"
            Write-Info "signature=$($before.signature)"
        } catch {
            Write-Fail $_.Exception.Message
        }

        Write-Step '个性属性：部分更新（只改签名与性别，验证不会清空其它字段）'
        try {
            $updated = Invoke-Api -Method PUT -Path '/user/profile' -Token $token -Body @{
                signature = '冒烟测试签名 ' + (Get-Date -Format 'HH:mm:ss')
                gender    = 1
                birthday  = '2008-09-01'
            }
            if ($updated.nickname -eq $before.nickname) {
                Write-Ok "只改指定字段成功；nickname 保持不变（$($updated.nickname)）"
            } else {
                Write-Fail "nickname 被意外修改：$($before.nickname) -> $($updated.nickname)"
            }
            Write-Ok "signature=$($updated.signature) gender=$($updated.gender) birthday=$($updated.birthday)"
        } catch {
            Write-Fail $_.Exception.Message
        }

        Write-Step '个性属性：非法性别应被拒绝（期望 code=40000）'
        try {
            Invoke-Api -Method PUT -Path '/user/profile' -Token $token -Body @{ gender = 9 } | Out-Null
            Write-Fail '非法 gender=9 竟然通过了'
        } catch {
            Write-Ok "已正确拒绝：$($_.Exception.Message)"
        }

        # ---------------------------------------------------------- 3.5 自定义头像
        Write-Step '自定义头像：上传 PNG（仅 JPG/PNG，≤5MB）'
        $avatarDir = if ($DownloadDir) { $DownloadDir } else { [System.IO.Path]::GetTempPath() }
        $avatarA = Join-Path $avatarDir 'avatar-a.png'
        $avatarB = Join-Path $avatarDir 'avatar-b.png'
        [System.IO.File]::WriteAllBytes($avatarA, [Convert]::FromBase64String($PngBlue))
        [System.IO.File]::WriteAllBytes($avatarB, [Convert]::FromBase64String($PngRed))

        $firstKey = $null
        try {
            $env1 = Invoke-Multipart -Path '/user/avatar' -Token $token -File (Get-Item $avatarA)
            if ($env1.code -eq 0 -and $env1.data.avatarKey) {
                $firstKey = $env1.data.avatarKey
                Write-Ok "上传成功 avatarKey=$firstKey"
                Write-Info "version=$($env1.data.avatarVersion)"
            } else {
                Write-Fail "上传失败 code=$($env1.code) $($env1.message)"
            }
        } catch { Write-Fail $_.Exception.Message }

        Write-Step '头像可读回：读到的字节应与上传的图片完全一致'
        try {
            $avatarOut = Join-Path $avatarDir 'avatar-downloaded.png'
            Invoke-WebRequest -Method GET -Uri "$BaseUrl/user/avatar/$($login.userId)" `
                -Headers @{ Authorization = $token } -OutFile $avatarOut -UseBasicParsing
            $avatarBytes = [System.IO.File]::ReadAllBytes($avatarOut)
            if ($avatarBytes.Length -ge 8 -and $avatarBytes[0] -eq 0x89 -and $avatarBytes[1] -eq 0x50 `
                -and $avatarBytes[2] -eq 0x4E -and $avatarBytes[3] -eq 0x47) {
                Write-Ok "读回 $($avatarBytes.Length) 字节，PNG 文件头正确"
            } else {
                Write-Fail '读回的内容不是 PNG'
            }
            $md5Out = (Get-FileHash -Path $avatarOut -Algorithm MD5).Hash
            $md5Src = (Get-FileHash -Path $avatarA -Algorithm MD5).Hash
            if ($md5Out -eq $md5Src) {
                Write-Ok '内容与上传的图片一致'
            } else {
                Write-Fail "内容不一致：上传 $md5Src / 读回 $md5Out"
            }
        } catch { Write-Fail $_.Exception.Message }

        Write-Step '更换头像：应换到新对象（服务端会顺带删掉 OSS 上的旧头像）'
        try {
            $env2 = Invoke-Multipart -Path '/user/avatar' -Token $token -File (Get-Item $avatarB)
            if ($env2.code -eq 0 -and $env2.data.avatarKey -and $env2.data.avatarKey -ne $firstKey) {
                Write-Ok "已换为新对象 version=$($env2.data.avatarVersion)"
                Write-Info '旧对象由服务端自动删除；若删除失败，OSS 对账任务会在宽限期后回收'
            } else {
                Write-Fail '更换头像后 avatarKey 未变化'
            }
        } catch { Write-Fail $_.Exception.Message }

        Write-Step '头像校验：伪装成 .png 的文本应被拒绝（期望 code=40000）'
        try {
            $fakeAvatar = Join-Path $avatarDir 'fake-avatar.png'
            [System.IO.File]::WriteAllText($fakeAvatar, 'not an image at all',
                (New-Object System.Text.UTF8Encoding($false)))
            $env3 = Invoke-Multipart -Path '/user/avatar' -Token $token -File (Get-Item $fakeAvatar)
            if ($env3.code -eq 40000) {
                Write-Ok "已正确拒绝：$($env3.message)"
            } else {
                Write-Fail "未被拒绝（code=$($env3.code) message=$($env3.message)）"
            }
        } catch { Write-Fail $_.Exception.Message }

        Write-Step '头像校验：超过 5MB 应被拒绝（期望 code=40082）'
        try {
            $tooBig = Join-Path $avatarDir 'too-big.png'
            $bigBytes = New-Object byte[] (5 * 1024 * 1024 + 1024)
            $bigBytes[0] = 0x89; $bigBytes[1] = 0x50; $bigBytes[2] = 0x4E; $bigBytes[3] = 0x47
            [System.IO.File]::WriteAllBytes($tooBig, $bigBytes)
            $env4 = Invoke-Multipart -Path '/user/avatar' -Token $token -File (Get-Item $tooBig)
            if ($env4.code -eq 40082) {
                Write-Ok "已正确拒绝：$($env4.message)"
            } else {
                Write-Fail "未被拒绝（code=$($env4.code) message=$($env4.message)）"
            }
            Remove-Item $tooBig -Force -ErrorAction SilentlyContinue
        } catch { Write-Fail $_.Exception.Message }

        Write-Step '清除头像：avatarKey 应变为空（OSS 对象同时删除）'
        try {
            $cleared = Invoke-Api -Method DELETE -Path '/user/avatar' -Token $token
            if (-not $cleared.avatarKey) {
                Write-Ok '头像已清除'
            } else {
                Write-Fail "清除后 avatarKey 仍为 $($cleared.avatarKey)"
            }
        } catch { Write-Fail $_.Exception.Message }

        # ---------------------------------------------------------- 4. 容量
        Write-Step '容量查询'
        try {
            $quota = Invoke-Api -Method GET -Path '/user/quota' -Token $token
            Write-Ok "quota=$($quota.quota) used=$($quota.used) free=$($quota.free) recycleUsed=$($quota.recycleUsed)"
        } catch {
            Write-Fail $_.Exception.Message
        }

        # ---------------------------------------------------------- 5. 上传
        Write-Step '上传：申请凭证 -> 直传 OSS -> 建立索引'
        $fileId = $null
        try {
            $ticketBody = @{
                name = $file.Name; size = $file.Length; contentType = 'text/plain'
            }
            # 上传场景的人机验证凭证（题库启用时必需）。本项目不消费该凭证，
            # 所以可以直接复用 -CaptchaPassToken 传进来的那一个。
            if ($CaptchaPassToken) { $ticketBody['captchaPassToken'] = $CaptchaPassToken }
            $ticket = Invoke-Api -Method POST -Path '/oss/ticket' -Token $token -Body $ticketBody
            Write-Ok "已签发凭证 objectKey=$($ticket.objectKey) partSize=$($ticket.partSize) partCount=$($ticket.partCount)"

            $put = Invoke-Api -Method POST -Path '/oss/put-url' -Token $token -Body @{
                uploadToken = $ticket.uploadToken; contentType = 'text/plain'
            }
            Write-Info "预签名 PUT 的 Content-Type 必须原样发送：$($put.contentType)"

            # 直传 OSS：这一步不经过业务服务器
            $putResp = Invoke-WebRequest -Method Put -Uri $put.url -InFile $file.FullName `
                -ContentType $put.contentType -UseBasicParsing
            if ($putResp.StatusCode -ge 200 -and $putResp.StatusCode -lt 300) {
                Write-Ok "直传 OSS 成功 HTTP $($putResp.StatusCode)"
            } else {
                Write-Fail "直传 OSS 返回 HTTP $($putResp.StatusCode)"
            }

            $commit = Invoke-Api -Method POST -Path '/files/commit' -Token $token -Body @{
                uploadToken = $ticket.uploadToken
                parentId    = 0
                name        = $file.Name
                contentType = 'text/plain'
                md5         = $sourceMd5
            }
            $fileId = $commit.fileId
            Write-Ok "建立索引成功 fileId=$fileId 服务端记录大小=$($commit.size) 凭证=$($commit.receipt)"

            if ($commit.size -ne $file.Length) {
                Write-Fail "服务端记录大小($($commit.size)) 与本地($($file.Length)) 不一致"
            }
        } catch {
            if ($_.Exception.Message -match '40105|40103') {
                Write-Fail '申请上传凭证需要人机验证（40105）：请加 -CaptchaPassToken <凭证> 重跑'
                Write-Info '（本项目不消费该凭证，登录与上传可以共用同一个）'
            } else {
                Write-Fail $_.Exception.Message
            }
        }

        # ---------------------------------------------------------- 6. 幂等
        Write-Step '幂等：同一个 uploadToken 重复 commit（期望返回同一 fileId，且不重复计容量）'
        try {
            Invoke-Api -Method POST -Path '/files/commit' -Token $token -Body @{
                uploadToken = $ticket.uploadToken; parentId = 0; name = $file.Name
            } | Out-Null
            # 令牌已被消费，第二次应当报"凭证无效"——这本身就是幂等的一道保障
            Write-Fail '重复 commit 未被拒绝（凭证应已被消费）'
        } catch {
            Write-Ok "重复提交被拒绝：$($_.Exception.Message)"
        }

        # ---------------------------------------------------------- 7. 列表
        Write-Step '列表：按前缀搜索刚上传的文件'
        try {
            $list = Invoke-Api -Method GET -Path ("/files?parentId=0&keyword={0}&page=1&size=50" -f [uri]::EscapeDataString($file.Name)) -Token $token
            $hit = $list.records | Where-Object { $_.id -eq $fileId } | Select-Object -First 1
            if ($hit) {
                Write-Ok "列表命中 id=$($hit.id) name=$($hit.name) size=$($hit.size)"
            } else {
                Write-Fail "列表中找不到 fileId=$fileId"
            }
        } catch {
            Write-Fail $_.Exception.Message
        }

        # ---------------------------------------------------------- 8. 下载回本地
        if ($fileId) {
            Write-Step '下载回本地：整文件下载并逐字节校验'
            if (-not $DownloadDir) { $DownloadDir = [System.IO.Path]::GetTempPath() }
            $downloadPath = Join-Path $DownloadDir ("downloaded-{0}" -f $file.Name)
            try {
                Invoke-WebRequest -Method GET -Uri "$BaseUrl/files/$fileId/download" `
                    -Headers @{ Authorization = $token } -OutFile $downloadPath -UseBasicParsing
                $downloadedMd5 = (Get-FileHash -Path $downloadPath -Algorithm MD5).Hash.ToLower()
                if ($downloadedMd5 -eq $sourceMd5) {
                    Write-Ok "下载内容与源文件完全一致（MD5 $downloadedMd5）"
                } else {
                    Write-Fail "下载内容不一致：源 $sourceMd5 / 下载 $downloadedMd5"
                }
                Write-Info "落地文件：$downloadPath"
            } catch {
                Write-Fail $_.Exception.Message
            }

            Write-Step '断点续传：Range 请求应返回 206 且只含请求的字节'
            try {
                $rangePath = Join-Path $DownloadDir 'range-part.bin'
                $rangeResp = Invoke-WebRequest -Method GET -Uri "$BaseUrl/files/$fileId/download" `
                    -Headers @{ Authorization = $token; Range = 'bytes=0-9' } `
                    -OutFile $rangePath -UseBasicParsing
                $rangeBytes = [System.IO.File]::ReadAllBytes($rangePath)
                $expect = $fileBytes[0..([Math]::Min(9, $fileBytes.Length - 1))]
                if ($rangeBytes.Length -eq $expect.Length -and (Compare-Object $rangeBytes $expect -SyncWindow 0) -eq $null) {
                    Write-Ok "Range 返回 $($rangeBytes.Length) 字节且内容正确（HTTP $($rangeResp.StatusCode)）"
                } else {
                    Write-Fail "Range 内容不符：期望 $($expect.Length) 字节，实际 $($rangeBytes.Length) 字节"
                }
            } catch {
                Write-Fail $_.Exception.Message
            }

            Write-Step '中文文件名编码：检查 Content-Disposition 头'
            try {
                $head = Invoke-WebRequest -Method GET -Uri "$BaseUrl/files/$fileId/download" `
                    -Headers @{ Authorization = $token; Range = 'bytes=0-0' } -UseBasicParsing
                $cd = $head.Headers['Content-Disposition']
                if ($cd -and $cd -match "filename\*=UTF-8''") {
                    Write-Ok "已按 RFC 5987 编码：$cd"
                } elseif ($cd) {
                    Write-Fail "缺少 filename*（中文名在浏览器会乱码）：$cd"
                } else {
                    Write-Fail '缺少 Content-Disposition 头'
                }
            } catch {
                Write-Fail $_.Exception.Message
            }

            Write-Step '越权校验：用无效 token 下载应被拒绝'
            try {
                Invoke-WebRequest -Method GET -Uri "$BaseUrl/files/$fileId/download" `
                    -Headers @{ Authorization = 'invalid-token' } -OutFile (Join-Path $DownloadDir 'x.bin') `
                    -UseBasicParsing | Out-Null
                Write-Fail '无效 token 竟然下载成功'
            } catch {
                Write-Ok '已拒绝无效 token'
            }

            # ------------------------------------------------------ 9. 回收站
            Write-Step '回收站：删除 -> 还原 -> 彻底删除'
            try {
                Invoke-Api -Method DELETE -Path '/files' -Token $token -Body @{ ids = @($fileId) } | Out-Null
                Write-Ok '已放入回收站'
                $recycle = Invoke-Api -Method GET -Path '/recycle?page=1&size=50' -Token $token
                if ($recycle.records | Where-Object { $_.id -eq $fileId }) {
                    Write-Ok '回收站列表中可见'
                } else {
                    Write-Fail '回收站列表中找不到该文件'
                }
                Invoke-Api -Method POST -Path '/recycle/restore' -Token $token -Body @{ ids = @($fileId) } | Out-Null
                Write-Ok '已还原'
                Invoke-Api -Method DELETE -Path '/files' -Token $token -Body @{ ids = @($fileId) } | Out-Null
                Invoke-Api -Method DELETE -Path '/recycle/purge' -Token $token -Body @{ ids = @($fileId) } | Out-Null
                Write-Ok '已彻底删除（OSS 对象在事务提交后异步删除）'
            } catch {
                Write-Fail $_.Exception.Message
            }
        }
    }
}

# ------------------------------------------------------------------ 收尾

if ($tempCreated) { Remove-Item $FilePath -Force -ErrorAction SilentlyContinue }

Write-Host ""
if ($script:Failures.Count -eq 0) {
    Write-Host "=== 全部通过 ✔ ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host ("=== 失败 {0} 项 ✖ ===" -f $script:Failures.Count) -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host ("  - " + $_) -ForegroundColor Red }
    exit 1
}
