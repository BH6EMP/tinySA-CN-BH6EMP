# QtTinySA 汉化 + 兼容性修复

> **汉化：BH6EMP**
>
> 原始项目：[g4ixt/QtTinySA](https://github.com/g4ixt/QtTinySA)（GPL-3.0-or-later，作者 Ian Jefferson G4IXT）

## 应用步骤

```bash
git clone https://github.com/g4ixt/QtTinySA.git
cd QtTinySA/src
git apply /path/to/localisation.patch
python /path/to/apply_cn_db.py
python QtTinySA.py
```

## 一、汉化

| 文件 | 改动 |
|---|---|
| `modules/*.ui`（8 个） | 160+ 条界面文案汉化 |
| `QtTinySA.py` | 迹线类型判断键改为中文；文件对话框标题汉化 |
| `modules/graphs.py` | 标记类型判断键改为中文 |
| `QtTSAprefs.db` | 迹线/标记类型下拉项改为中文（由 `apply_cn_db.py` 完成） |

**数据库与代码必须一起改。** 迹线类型和标记类型既显示在界面上，又被代码当作判断条件。
只改数据库不改代码，标记功能会静默失效且不报错。

## 二、兼容性修复（重要）

上游 QtTinySA 面向 tinySA Ultra 开发，用在 **tinySA Basic** 上有若干问题，本补丁一并修复：

### 1. Windows 上被误判为 Ultra

```python
# 上游代码
def identify(self, port):
    if system() == 'Linux' or system() == 'Darwin':
        return port.product
    else:
        return 'tinySA4'      # Windows 一律当 Ultra
```

Windows 无法从端口描述区分机型，结果 Basic 被按 Ultra 协议处理
（发 LNA、spur auto、时间同步等专有命令），数据对不上。

**修复**：连接前短暂打开端口发一次 `version`，按返回内容判断机型。

### 2. `scanraw` 不支持自动重复扫描

Basic 固件的 `scanraw` 每次只扫一遍就返回，而 QtTinySA 默认只在第一轮发命令、
之后期待设备自动持续输出 → 数据错位 → 报 `QtTinySA display out of sync`。

**修复**：Basic 强制 `loop=False`，每扫一次重发一次命令。

### 3. 点数超过 290 导致固件越界

固件的 `scanraw` **不检查点数上限**：

```c
uint32_t points = sweep_points;
if (argc > 2) points = my_atoi(argv[2]);   // 没有 clamp
for (i = 0; i < points; i++)
    perform(false, i, ...);                 // i 超出 290 即写越界
```

Basic 的 `measured[]` 只有 290 个元素。请求更多点会让固件写越界并死机。

**修复**：Basic 分支把点数裁剪到 20–290。

### 4. RBW 为 auto 时崩溃

Basic 分支对 RBW 做数值裁剪，但 RBW 设为 auto 时值是字符串 `'auto'`：

```
numpy._core._exceptions._UFuncNoLoopError: dtype('<U4')
```

**修复**：仅对数值裁剪，字符串原样交给设备。

### 5. 版本号解析

上游用 `version.split('_')[-1]` 取版本，遇到 `tinySA_XXX_V0.1_CH` 这类
非标准版本串会解析成 `CH`。**修复**：提取 `x.y` 形式并保留后缀（如 `0.1-CH`）。

### 6. 日志写入文件

新增 `%LOCALAPPDATA%\QtTinySA\QtTinySA.log`，便于排查问题，无需控制台。

## 三、未汉化部分

- **校准模式**（`low/direct/ultra/lna` 等）：设备模式标识，原样保留
- **单位符号**（dBm/dB/kHz 等）：通用写法
- **设备指令**（`off/on/auto`）：直接下发给仪器，必须保持英文
- 程序日志输出

## 四、已知限制

- Basic 最多 290 点、频率上限 950 MHz、高波段需切换 `mode high input`

## 许可

本补丁按 GPL-3.0-or-later 分发，与上游一致。使用本补丁的衍生作品必须同样以 GPL-3.0 发布。

---

AI 总结仅供参考
