# 第三方说明

## Backdrop 与 Shapes

液态玻璃使用 [Kyant0 / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/1.0.6) 的 `io.github.kyant0:backdrop:1.0.6`，并包含其传递依赖 `io.github.kyant0:shapes:1.2.0`。两者由 Kyant 提供，采用 Apache License 2.0。AMI 复用库的 GPU 模糊、镜片折射、色散、高光与阴影，没有复制其他厂商的系统修改模块。

[完整 Apache-2.0 许可](../app/src/main/assets/licenses/Backdrop-1.0.6.txt) 同时随安装包保留在 assets/licenses 中。选型与资源边界见 [液态玻璃说明](LIQUID_GLASS.md)。

## 网易云网站协议参考

`core/NeteaseWeb.kt` 和 `data/NeteaseAccount.kt` 使用 JCA 与 Android 原生网络独立实现网站请求，公开协议参数与端点参考 [NeteaseCloudMusicApiEnhanced/api-enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced) 的 MIT 许可资料。AMI 不包含或调用其代理服务、替换音源或解锁模块。固定 RSA 公钥与网站协议参数不是用户凭据或开放平台私钥。

保留参考项目许可：

```text
The MIT License (MIT)

Copyright (c) 2013-2022 Binaryify

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
```
