# LocalTranslate（暂名 Neko Translate）

一个**完全离线**的 Android 翻译 App：用户自行导入 GGUF 翻译模型，通过 [llama.cpp](https://github.com/ggml-org/llama.cpp) 在手机 CPU 上本地推理。不申请网络权限，文本不出设备。

> 当前阶段：**Step 0 技术验证**。只有一个测试页面（导入/加载模型 → 选语言 → 流式翻译 → 停止 → 速度统计）。

## 支持的模型

| 模型 | 架构（GGUF） | 推荐量化 | 状态 |
|---|---|---|---|
| [Index-Translate-2B](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF) / 9B（bilibili Index 团队） | `qwen35` | **Q4_K_M** | 内置 Profile |
| [Hy-MT2-1.8B](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) / [7B](https://huggingface.co/tencent/Hy-MT2-7B-GGUF)（腾讯混元） | `hunyuan-dense` | **Q4_K_M** | 内置 Profile |
| 其他 llama.cpp 支持的指令模型 | – | Q4_K_M | “Custom” Profile，自填 Prompt 模板 |

- 推荐 **Q4_K_M**：体积、速度、质量最平衡（Index-Translate-2B ≈ 1.3 GB，Hy-MT2-1.8B ≈ 1.1 GB）。
- ⚠️ **Hy-MT2 的 1.25bit / 2bit GGUF 无法加载**：它们依赖腾讯的 STQ 内核（[llama.cpp PR #22836](https://github.com/ggml-org/llama.cpp/pull/22836)），不在 llama.cpp 主线里。请用 Q4_K_M / Q6_K / Q8_0。
- 导入时尽量**保留官方文件名**（如 `Index-Translate-2B.Q4_K_M.gguf`、`Hy-MT2-1.8B-Q4_K_M.gguf`）：官方 GGUF 的 `general.name` 只写了 `2b` / `1.8B`，自动匹配 Profile 主要靠文件名（`hunyuan-dense` 架构即使改名也会回退到 Hy-MT2）。匹配错了可以在界面上手动切换 Profile。
- 9B / 7B 在大内存手机上能跑，但明显慢于 2B / 1.8B。

### 内置 Prompt 与采样参数

| Profile | 用户 Prompt | Chat 包装 | 采样 |
|---|---|---|---|
| Index-Translate | `请将以下{source_lang}文本翻译为{target_lang}，直接输出翻译结果，不要进行任何解释。\n\n{text}`（源语言为“自动”时为 `请将以下文本翻译为{target_lang}，…`），语言名用中文（`英语`、`日语`…） | Qwen3.5 模板、关闭思考：`<\|im_start\|>user\n{prompt}<\|im_end\|>\n<\|im_start\|>assistant\n<think>\n\n</think>\n\n` | 贪心（temperature 0），max_tokens 1024 |
| Hy-MT2 | 任一侧是中文时：`将以下文本翻译为{target_lang}，注意只需要输出翻译后的结果，不要额外解释：\n\n{text}`；否则：`Translate the following text into {target_lang}. Note that you should only output the translated result without any additional explanation:\n\n{text}` | `<｜hy_begin▁of▁sentence｜><｜hy_User｜>{prompt}<｜hy_Assistant｜>`（无 system prompt） | temperature 0.7, top_p 0.6, top_k 20, repetition_penalty 1.05, max_tokens 4096 |

来源：[Index-Translate-2B 模型卡](https://huggingface.co/IndexTeam/Index-Translate-2B)、[Index-Translate-2B-GGUF](https://huggingface.co/IndexTeam/Index-Translate-2B-GGUF)、[官方客户端 translate.py](https://github.com/bilibili/Index-Translate/blob/main/inference/llm/translate.py)、[Hy-MT2-1.8B-GGUF 模型卡](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF)、[Hy-MT2 GitHub](https://github.com/Tencent-Hunyuan/Hy-MT2)、[Hy-MT2 chat_template.jinja](https://huggingface.co/tencent/Hy-MT2-1.8B/blob/main/chat_template.jinja)。

为什么不直接用 GGUF 自带的 chat template：`llama_chat_apply_template` 不执行 Jinja，只按特征识别模板。实测（llama.cpp b11707）它把 Index-Translate 的 Qwen3.5 模板渲染成**不带空 think 块**的 ChatML，把 Hy-MT2 模板误判成 HunyuanVL（渲染成 `<bos>{内容}<｜hy_User｜>`）。所以内置 Profile 使用从官方 Jinja 模板转写的固定包装；Custom Profile 才走 GGUF 模板。

## 构建

需要：JDK 17、Android SDK（compileSdk 37）、NDK **29.0.14206865**、CMake **3.31.6**（可在 SDK Manager 安装，或让 Gradle 自动下载）。

```bash
git clone --recursive https://github.com/furina315/LocalTranslate.git
cd LocalTranslate
# 如果 clone 时没带 --recursive：
git submodule update --init --recursive

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- 只编 `arm64-v8a`，minSdk 29（Android 10）。
- 原生代码**始终以 Release 编译**（debug APK 也是），否则 llama.cpp 会慢十倍以上。
- CMake 配置时会通过 FetchContent 下载 KleidiAI（需要网络）。
- `gradle.properties` 开关：
  - `lt.cpuAllVariants=true`（默认）：按 llama.cpp 官方 `examples/llama.android` 的做法，编出 armv8.0 → armv9.2+SME 共 7 个 `libggml-cpu-*.so`，运行时按 CPU 特性自动选最优。
  - `lt.cpuAllVariants=false` + `lt.cpuArch=armv8.6-a+dotprod+i8mm+fp16`：只编一个固定架构，APK 更小、编译更快（骁龙 8 Elite Gen 5 支持 dotprod / i8mm）。
  - `lt.kleidiai=true`：启用 Arm KleidiAI 内核。
- GitHub Actions（`.github/workflows/android-debug.yml`）每次 push 到 main 都会跑单元测试、编 debug APK，并作为 artifact 上传（Actions 页面 → 对应运行 → Artifacts）。

### 推到你自己的 GitHub 仓库

如果你拿到的是打包好的源码（zip 或 git bundle）：

```bash
# 1. 在 GitHub 网页上新建空的私有仓库 LocalTranslate（不要勾选 README / .gitignore / License）
# 2. 解压后进入目录
unzip LocalTranslate.zip && cd LocalTranslate
#   或者用 bundle：git clone LocalTranslate.bundle LocalTranslate && cd LocalTranslate
git submodule update --init --recursive      # 拉取 llama.cpp（固定在 b11707）
git remote add origin https://github.com/furina315/LocalTranslate.git
git push -u origin main
```

推上去之后 Actions 会自动开始构建 APK。

### 在手机上测试

1. 从 Hugging Face 下载 `Index-Translate-2B.Q4_K_M.gguf` 或 `Hy-MT2-1.8B-Q4_K_M.gguf` 到手机。
2. 打开 App →「导入 .gguf」→ 选文件（复制到 App 私有目录 `files/models`，显示进度并校验 GGUF 头）。
3. 「加载」→ 选语言 → 输入原文 →「翻译」，可随时「停止」。下方显示加载耗时、prompt 处理速度、生成 tokens/s 和进程 RSS。
4. `adb logcat -s LocalTranslate llama.cpp` 查看原生日志（含选中的 CPU 变体）。

## 架构

```
app/      com.nekohome.translate
  ui/       Compose Material 3 测试页（动态取色 + 深色模式）+ TranslateViewModel
  data/     ModelProfile / BuiltInProfiles / Language / ModelRepository（SAF 导入）
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
- [ ] **Step 1 模型适配层**：完善 Profile（术语表 / 风格等约束翻译格式、按模型调参）、Profile 持久化与手动覆盖、上下文长度自适应。
- [ ] **Step 2 UI MVP**：正式翻译页、模型管理页、历史记录。
- [ ] **Step 3 系统集成**：`PROCESS_TEXT`（选中文字直接翻译）、分享菜单、长文本分段、设置页。

## License

Apache-2.0（见 `LICENSE`）。llama.cpp 为 MIT License。模型权重遵循各自许可证。

---

## English summary

**LocalTranslate** (placeholder name *Neko Translate*) is a fully offline Android translator. Users import GGUF translation models, which run on-device through llama.cpp (CPU backend, arm64-v8a, minSdk 29). Built-in profiles cover **Index-Translate 2B/9B** (Qwen3.5) and **Hy-MT2 1.8B/7B** (Hunyuan dense) with the official prompt formats and sampling settings; a *Custom* profile accepts any template with `{source_lang}`, `{target_lang}`, `{text}`. Q4_K_M is recommended. Hy-MT2's 1.25-bit/2-bit GGUFs need the non-mainline STQ kernel and will not load.

Build: `git clone --recursive`, then `./gradlew :app:assembleDebug` (JDK 17, NDK 29.0.14206865, CMake 3.31.6). CI builds a debug APK on every push to `main` and uploads it as an artifact. To publish a packaged copy: create an empty GitHub repo, `git submodule update --init --recursive`, `git remote add origin …`, `git push -u origin main`.

Roadmap: step 0 tech validation (this) → step 1 model adapter layer → step 2 UI MVP (translate page, model manager, history) → step 3 PROCESS_TEXT / share / long-text chunking / settings.
