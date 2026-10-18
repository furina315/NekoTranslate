# NekoTranslate

**NekoTranslate** 是一个**完全离线**的 Android 翻译 App：用户自行导入 GGUF 翻译模型，通过 [llama.cpp](https://github.com/ggml-org/llama.cpp) 在手机 CPU 上本地推理。不申请网络权限，文本不出设备。

> 当前阶段：**Step 2 UI MVP**。底部导航三个页面：**翻译**（语言选择 + 交换、输入、流式译文、复制、停止）、**模型**（导入 / 加载 / 卸载 / 删除 GGUF，按模型保存 Profile 与采样、上下文、线程参数）、**设置**（主题、动态取色、默认目标语言、屏幕常亮、关于）。

## 支持的模型

| 模型 | 架构（GGUF） | 推荐量化 | 状态 |
|---|---|---|---|
| [Index-Translate-2B](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF) / 9B（bilibili Index 团队） | `qwen35` | **Q4_K_M** | 内置 Profile |
| [Hy-MT2-1.8B](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) / [7B](https://huggingface.co/tencent/Hy-MT2-7B-GGUF)（腾讯混元） | `hunyuan-dense` | **Q4_K_M** | 内置 Profile |
| 其他 llama.cpp 支持的指令模型 | – | Q4_K_M | “Custom” Profile，自填 Prompt 模板 |

- 推荐 **Q4_K_M**：体积、速度、质量最平衡（Index-Translate-2B ≈ 1.3 GB，Hy-MT2-1.8B ≈ 1.1 GB）。
- ⚠️ **Hy-MT2 的 1.25bit / 2bit GGUF 无法加载**：它们依赖腾讯的 STQ 内核（[llama.cpp PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)），不在 llama.cpp 主线里。请用 Q4_K_M / Q6_K / Q8_0。
- 导入时尽量**保留官方文件名**（如 `Index-Translate-2B.Q4_K_M.gguf`、`Hy-MT2-1.8B-Q4_K_M.gguf`）：官方 GGUF 的 `general.name` 只写了 `2b` / `1.8B`，自动匹配 Profile 主要靠文件名（`hunyuan-dense` 架构即使改名也会回退到 Hy-MT2）。匹配错了可以在「模型」页手动切换 Profile。
- 9B / 7B 在大内存手机上能跑，但明显慢于 2B / 1.8B。

### 内置 Prompt 与采样参数

| Profile | 用户 Prompt | Chat 包装 | 采样 |
|---|---|---|---|
| Index-Translate | `请将以下{source_lang}文本翻译为{target_lang}，直接输出翻译结果，不要进行任何解释。\n\n{text}`（源语言为“自动”时为 `请将以下文本翻译为{target_lang}，…`），语言名用中文（`英语`、`日语`…） | Qwen3.5 模板、关闭思考：`<\|im_start\|>user\n{prompt}<\|im_end\|>\n<\|im_start\|>assistant\n<think>\n\n</think>\n\n` | 贪心（temperature 0），max_tokens 1024 |
| Hy-MT2 | 任一侧是中文时：`将以下文本翻译为{target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{text}`；否则：`Translate the following text into {target_lang}. Note that you should only output the translated result without any additional explanation:\n\n{text}` | `<｜hy_begin▁of▁sentence｜><｜hy_User｜>{prompt}<｜hy_Assistant｜>`（无 system prompt） | temperature 0.7, top_p 0.6, top_k 20, repetition_penalty 1.05, max_tokens 4096 |

来源：[Index-Translate-2B 模型卡](https://huggingface.co/IndexTeam/Index-Translate-2B)、[Index-Translate-2B-GGUF](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF)、[官方客户端 translate.py](https://github.com/bilibili/Index-Translate/blob/main/inference/llm/translate.py)、[Hy-MT2-1.8B-GGUF 模型卡](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF)、[Hy-MT2 GitHub](https://github.com/Tencent-Hunyuan/Hy-MT2)、[Hy-MT2 chat_template.jinja](https://huggingface.co/tencent/Hy-MT2-1.8B/blob/main/chat_template.jinja)。

为什么不直接用 GGUF 自带的 chat template：`llama_chat_apply_template` 不执行 Jinja，只按特征识别模板。实测（llama.cpp b11707）它把 Index-Translate 的 Qwen3.5 模板渲染成**不带空 think 块**的 ChatML，把 Hy-MT2 模板误判成 HunyuanVL（渲染成 `<bos>{内容}<｜hy_User｜>`）。所以内置 Profile 使用从官方 Jinja 模板转写的固定包装；Custom Profile 才走 GGUF 模板。

## 下载与构建

- **下载**：到 [Releases](https://github.com/furina315/NekoTranslate/releases) 页面下载 APK，每个版本有三个：`NekoTranslate-<版本>-arm64-v8a.apk`（**推荐**，64 位手机）、`-armeabi-v7a.apk`（只支持 32 位的老设备）、`-universal.apk`（两种都含，体积更大）。
- **发版**：打 `v*` tag 并推送，CI 自动编译并发布 Release（tag 带 `-` 如 `v0.2.0-beta1` 会标记为预发布）：`git tag -a v0.2.0 -m "更新说明" && git push origin v0.2.0`。tag 说明会放在 Release 正文顶部，后面附自动生成的提交列表；版本名和 versionCode 都由 tag 算出（vX.Y.Z → X×10000 + Y×100 + Z），无需手动修改。也可以在 Actions →「Release APKs」→ Run workflow 手动构建，结果发布到预发布 `nightly`（每次手动运行覆盖）。
- **armeabi-v7a 的限制**：32 位进程只有约 3 GB 地址空间，只建议用 ≤2B 的 Q4_K_M 模型（约 1.1–1.3 GB）；7B / 9B 基本无法加载。v7a 只有一个 NEON 版 CPU 后端（没有 dotprod / i8mm 变体和 KleidiAI），速度明显慢于 v8a。


需要：JDK 17、Android SDK（compileSdk 37）、NDK **29.0.14206865**、CMake **3.31.6**（可在 SDK Manager 安装，或让 Gradle 自动下载）。

```bash
git clone --recursive git@github.com:furina315/NekoTranslate.git
# 或 HTTPS：git clone --recursive https://github.com/furina315/NekoTranslate.git
cd NekoTranslate
# 如果 clone 时没带 --recursive：
git submodule update --init --recursive

./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,universal}-release.apk（日常使用/测流畅度请装这个）
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
# 只编 arm64-v8a（更快）：./gradlew :app:assembleRelease -Plt.abis=arm64-v8a

# 调试版（未混淆，Compose 明显更卡，只用于调试）：
./gradlew :app:assembleDebug   # → app/build/outputs/apk/debug/app-<abi>-debug.apk
```

- 编 `arm64-v8a` 和 `armeabi-v7a`（ABI split + universal），minSdk 29（Android 10）。versionCode = tag 算出的基础值 × 10 + ABI（universal 0、v7a 1、v8a 2）。
- 原生代码**始终以 Release 编译**（debug APK 也是），否则 llama.cpp 会慢十倍以上。
- CMake 配置时会通过 FetchContent 下载 KleidiAI（需要网络）。
- `gradle.properties` 开关：
  - `lt.cpuAllVariants=true`（默认）：按 llama.cpp 官方 `examples/llama.android` 的做法，编出 armv8.0 → armv9.2+SME 共 7 个 `libggml-cpu-*.so`，运行时按 CPU 特性自动选最优。
  - `lt.cpuAllVariants=false` + `lt.cpuArch=armv8.6-a+dotprod+i8mm+fp16`：只编一个固定架构，APK 更小、编译更快（骁龙 8 Elite Gen 5 支持 dotprod / i8mm）。
  - `lt.kleidiai=true`：启用 Arm KleidiAI 内核。
- **release 与 debug 的区别**：release 开启 R8 混淆/优化（`isMinifyEnabled`）和资源压缩，并通过 `androidx.profileinstaller` 安装 Compose 自带的 baseline profile 以及本项目的 `app/src/main/baseline-prof.txt`，安装后即 AOT 编译 UI 代码；debug 版 Compose 未优化，动画会明显掉帧。**测试流畅度请只用 release。**
- ⚠️ **release 目前用 debug keystore 签名**（`signingConfig = signingConfigs.getByName("debug")`），这样 CI 产物可以直接安装。它**不能用于发布**：正式发布前要换成自己的 release keystore（换签名后需要先卸载旧版再装）。release 的包名是 `com.nekohome.translate`，debug 是 `com.nekohome.translate.debug`，两者可以同时安装（模型需要各自导入）。
- JNI 混淆规则在 `llama/consumer-rules.pro`（保留 `LlamaNative` 的 native 方法，以及 C++ 通过 `GetMethodID("onToken")` 回调的 `TokenCallback` 及其所有实现）。
- Compose 稳定性配置：`app/compose-stability.conf`（把只读集合、`File`、`:llama` 模块的数据类标为 stable，让列表卡片等在无变化时跳过重组）。
- GitHub Actions：`.github/workflows/ci.yml` 在每次 push / PR 到 main 时只编译 Kotlin 并跑 JVM 单元测试（`-Plt.skipNative=true`，不编原生库、不出 APK）；`.github/workflows/release.yml` 在推送 `v*` tag 或手动运行时编三个 release APK，连同 R8 mapping（`NekoTranslate-<版本>-mapping.txt`，用于还原崩溃堆栈）发布到 GitHub Release。

### 推到你自己的 GitHub 仓库

如果你拿到的是打包好的源码（zip 或 git bundle）：

```bash
# 1. 在 GitHub 网页上新建空的仓库 NekoTranslate（不要勾选 README / .gitignore / License）
# 2. 解压后进入目录
unzip NekoTranslate.zip && cd NekoTranslate
#   或者用 bundle：git clone NekoTranslate.bundle NekoTranslate && cd NekoTranslate
git submodule update --init --recursive      # 拉取 llama.cpp（固定在 b11707）
git remote add origin git@github.com:furina315/NekoTranslate.git
git push -u origin main
```

推上去之后 Actions 会自动跑单元测试；要出 APK 请推 `v*` tag 或手动运行「Release APKs」。

### 在手机上测试

1. 从 Hugging Face 下载 `Index-Translate-2B.Q4_K_M.gguf` 或 `Hy-MT2-1.8B-Q4_K_M.gguf` 到手机。
2. 打开 App →「模型」页 →「导入 .gguf」→ 选文件（复制到 App 私有目录 `files/models`，显示进度并校验 GGUF 头）。
3. 在模型卡片上点「加载」；下方「参数」区可切换 Profile（自动匹配 / 手动 / Custom 模板）、调 temperature、top_p、top_k、重复惩罚、最大长度、上下文长度、线程数（按模型持久化，可一键恢复 Profile 默认值；改上下文 / 线程后会提示重新加载）。
4. 回到「翻译」页 → 选语言 → 输入原文 →「翻译」，可随时「停止」，译文可一键复制。译文下方显示 tokens 数与 tok/s（可在设置里关闭）。
5. `adb logcat -s NekoTranslate llama.cpp` 查看原生日志（含选中的 CPU 变体）。

## 架构

```
app/      com.nekohome.translate
  ui/       AppRoot（Material 3 底部导航）+ MainViewModel（三个页面共享，模型跨页面保持加载）
    translate/  翻译页        models/  模型管理 + 参数        settings/  设置 + 关于
  data/     ModelProfile / BuiltInProfiles / Language / ModelRepository（SAF 导入）
            ModelParams（按模型的参数覆盖，DataStore 持久化）/ SettingsRepository（DataStore）
  engine/   TranslationEngine（专用单线程执行所有原生调用）、输出清洗、内存统计
llama/    com.nekohome.translate.llama（Android library + JNI）
  src/main/cpp/CMakeLists.txt   add_subdirectory(third_party/llama.cpp)
  src/main/cpp/llama_jni.cpp    只依赖 llama.h 公共 C API
  LlamaSession / LlamaBackend / GgufMetadataReader（纯 Kotlin GGUF 头解析）
third_party/llama.cpp           git submodule，固定在 b11707
```

JNI 层能力：按路径（含 `/proc/self/fd/N`）或文件描述符加载模型；设置 n_ctx / 线程数 / batch；读取 GGUF 元数据（架构、名称、量化类型、chat template）；`llama_chat_apply_template`；带 temperature / top_p / top_k / min_p / 重复惩罚的流式生成（以 UTF-8 字节回调，emoji 安全）；随时取消；卸载；返回 prompt 处理耗时与生成速度。

## 路线图

- [x] **Step 0 技术验证**：llama.cpp 集成、JNI、模型导入、流式翻译测试页、CI。
- [ ] **Step 1 模型适配层**：完善 Profile（术语表 / 风格等约束翻译格式）、上下文长度自适应。（按模型调参、Profile 持久化与手动覆盖已完成）
- [x] **Step 2 UI MVP**：底部导航（翻译 / 模型 / 设置）、正式翻译页、模型管理页、设置页。
- [ ] 历史记录。
- [ ] **Step 3 系统集成**：`PROCESS_TEXT`（选中文字直接翻译）、分享菜单、长文本分段。

## License

Apache-2.0（见 `LICENSE`）。llama.cpp 为 MIT License。模型权重遵循各自许可证。
