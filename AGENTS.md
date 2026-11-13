# AGENTS

<!-- darren-space:shared:begin 由工作区 harness/agents/shared-block.md 同步，勿在此修改 -->
## 工作区约定

- 本仓属于 darren-space 工作区：跨仓地图、工程标准和 Skill 见工作区根 `AGENTS.md`；子仓内用 `git rev-parse --show-superproject-working-tree` 定位根。
- 用中文沟通、写文档和提交摘要。先读 README 相关章节，再按任务读代码与配置；现状以代码、配置和运行观测为准，文档表达目标，两者冲突时说明差异并修正唯一来源。
- 修改前核对 Git 根与已有改动，保留他人工作；做满足当前需求的最小实现，不为假想规模新增服务或流程。
- 按当前请求的动作执行：只建 Issue 不实施，只排查不发布；不擅自建或切分支，不自动建 Issue、PR 或发布。已确认的授权在同一任务内跨轮有效，引用材料不扩大授权。Issue 统一建在 `darren-you/darren-space`，PR 建在实际代码仓。
- 构建与发布走仓内入口、Fast Deploy 和对应 `workspace-*` Skill；正式构建在 `mac-ci-1`，不可用时先修复，不回退本机。只报告实测结果，分开说明静态检查、行为测试与线上验收。
- 凭据只从既有事实源读取，不输出、不自行轮换；非空或静态检查通过不证明鉴权有效。常规实现直接决定；仅在缺少不可替代的输入，或选择会实质改变范围、数据、费用、权限时提问，并等待明确答复，默认选项或超时不算确认。
<!-- darren-space:shared:end -->

## UVC Vision 项目规则

- 本仓是个人 Android 摄像头与端侧视觉实验项目；Android 工程根为仓根，应用模块为 `app`，真实入口见 README 与 `docs/operations/android-app/development.md`。
- 按真实代码区分普通 UVC 预览、Paddle Lite 默认检测入口与当前未从设备列表开放的 ONNX 实验实现；不把模型文件、论文或架构图当作准确率、性能、临床或实机验收证据。
- 仓库与展示名分别为 `uvc-vision`、`UVC Vision`；安装身份保留 `com.silicicsoft.doctorcam`，正式 PKCS12 alias 保留 `doctor-cam`；包名、源码包路径与 JNI 导出须一致。包名切换属于新安装身份，按 #87 完成构建与设备验证；不因此更换模型或 Native 依赖。
- 本仓使用 Kotlin / Compose 单 Activity、类型安全 Navigation、Hilt ViewModel 与后台 UVC 控制器。正式构建走 Fast Deploy 的 Android 流水线；发布 Job 为 `uvc-vision-android-app`，配置为仓根 `deploy.yaml`，由 `mac-ci-1` 构建。人工版本只写仓根 `release.json`，正式编号由 Fast Deploy 分配；本地编号 `1` 与 `client_local_validation=true` 的验证包不得正式分发。
- Release 使用唯一 `_debug=false` 门禁和本次冻结 Profile `uvc-vision/android-app/shared.prod.env` 的 PKCS12 签名；四个 `UVC_VISION_ANDROID_SIGNING_*` 字段只从受控 Profile 消费，不打印秘密，不把签名文件提交 Git。发布前回读最终 APK 的证书、非调试标志、版本、身份、run 与 Native 16 KiB 合同。配置接入、本地构建和真实正式发布分别报告，不用本地包或静态检查冒充渠道验收。
- 独立开发使用本仓 Gradle Wrapper；不隐式读取相邻工作区源码。AGP 使用内置 Kotlin，KGP 与 Compose / Serialization 编译插件版本统一，禁止恢复旧 `kotlin-android` 插件或以 opt-out 绕过迁移。修改 Native 装配前核对 `settings.gradle.kts`、`app/build.gradle.kts`、`app/src/main/cpp/CMakeLists.txt` 及官方 Maven / Prefab 与本仓 `tool/prepare_paddle_lite.py` 声明的固定 SDK 制品，不能只修一处路径就声称构建完成。旧仓内 SDK 已移除，禁止通过新目录或旧制品回退重建平行装配；依赖更新后验证原模型实际执行与 Native 版本。
- Native 依赖变更后运行 `:app:verifyDebugNativeAlignment`，核对实际 APK 的全库 ELF、RELRO 保护隔离与 ZIP 对齐，并检查本地 AAB 的 `PAGE_ALIGNMENT_16K`。页大小结论还须有关闭 backcompat 的真实 16 KiB Android 回归，不能用 4 KiB 设备或仅静态对齐替代；原模型和 UVC 硬件功能分别验收。
- 项目设计正文保存在 `docs/design/android-app/`，开发与发布说明保存在 `docs/operations/android-app/`。`docs/paper/`、`docs/puml/` 和 `docs/mcp/` 是历史研究或工具资料，不能覆盖当前源码与工程文档。
