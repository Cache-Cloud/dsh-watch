# keystore/

`dsh-watch.jks` 是一个**公开的调试用签名密钥**（storepass / keypass / alias 都是 `dsh-watch`）。

它被提交进仓库，唯一原因是让 GitHub Actions 每次产出的 APK 都用同一把密钥签名——
用不同的密钥签名的 APK 无法覆盖安装，每次构建都换密钥会让升级变成「先卸载」。
本项目的定位是自用侧载，不存在密钥泄露带来的额外风险。

**不要用这个密钥签任何要发布的东西。** 想用自己的密钥：

1. 生成 `keytool -genkeypair -keystore my.jks -alias me -keyalg RSA -keysize 2048 -validity 20000`
2. `base64 -w0 my.jks` 后存进仓库 Secrets（例如 `KEYSTORE_BASE64`）
3. 在 workflow 里把它解码成文件，并把 `DSH_WATCH_KEYSTORE` 指过去

注意：换成自己的密钥后，已经装过旧版的手表必须先卸载再装。
