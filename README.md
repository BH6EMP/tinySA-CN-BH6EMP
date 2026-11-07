# tinySA 中文化（非官方）

> **汉化：BH6EMP**
>
> 本项目是第三方非官方汉化，与 tinySA 及其商标所有者无任何隶属关系。
> 原始固件、桌面软件、商标均归其各自作者所有，本项目仅按 GPLv3 分发修改版。

## 内容

| 目录 | 说明 | 许可 |
|---|---|---|
| `firmware/` | 中文固件源码（改自 tinySA 固件） | GPL-3.0-or-later |
| `android/` | 「SA频谱」安卓 App 源码 | GPL-3.0-or-later |
| `desktop/` | QtTinySA 汉化 + 兼容修复补丁 | GPL-3.0-or-later |
| `dist/` | 预编译产物 | GPL-3.0-or-later |
| `docs/` | 刷入指南 | GPL-3.0-or-later |

---

## 一、中文固件

把 tinySA Basic 的操作界面汉化，保留原有操作逻辑，仅替换显示层文案。

### 改动

- **界面文案汉化 306 处**：一级、二级、三级菜单
- **新增 14×15 点阵中文字库**：268 字，`const` 存 Flash，**不占 RAM**
- **左信息栏保持英文**：该区域行距仅 8px，放不下汉字
- **版本号**：`tinySA_BH6EMP_V0.1_CH`
- **版本页底部**：`Translate:BH6EMP`

### 资源占用

```
Flash  116,460 / 118,784 B = 98.04%
RAM    16 KB / 16 KB（与原版一致，零增长）
配置区 0x0801D000 起未触碰（不影响校准数据）
```

### 编码方案

```
ASCII : 原样 1 字节
汉字  : 2 字节 [ 0x80 | (idx>>7) ] [ 0x20 + (idx & 0x7F) ]
解码  : idx = ((hi & 0x7F) << 7) | (lo - 0x20)
```

低字节刻意避开 `0x00/0x08/0x09/0x0A`，避免被字符串函数当作控制字符。

### 编译

需要 `arm-none-eabi-gcc` 与 ChibiOS 子模块：

```bash
git submodule update --init --recursive   # 拉取 ChibiOS
make -j8 VERSION="tinySA_BH6EMP_V0.1_CH"
```

> 注意：改版本号必须先 `make clean`，`VERSION` 是命令行变量，不参与依赖判断。

---

## 二、安卓 App「SA频谱」

配合 tinySA 使用的手机端助手，通过 OTG 串口读取数据，按手机原生分辨率重绘。

### 功能

- **曲线**：读 `frequencies` 与 `data 0`，原生分辨率绘制（不是投屏，无放大模糊）
- **瀑布图**：时间-频率-强度，256 级色标
- **迹线处理**：实时 / 平均4 / 平均16（指数平均）/ 峰值保持
- **标记**：拖动或输入频率定位，顶部显示频率与电平
- **单位**：dBm / dBμV / dBmV / Vrms / Vpp / W
- **纵轴**：可手动设置范围，刻度自动取整
- **量程**：LOW 口 0–350M 两段、HIGH 口 240–950M 两段，自动切换 `mode low/high input`
- **参数**：带宽 / 扫描时间 / 衰减 / 点数，面板内即时反馈
- **连接时自动同步**仪器当前设置
- **自适应轮询**：按设备扫描时间决定取数节奏，避免抢占固件扫描线程
- **断线保护**：拔线或关机不会闪退

### 构建

零第三方依赖，手工调用 Android SDK 工具链（`aapt2` + `javac` + `d8` + `apksigner`）。
需要 JDK 17 与 Android SDK（platform 33、build-tools 30.0.3），步骤见 `android/README.md`。

---

## 三、QtTinySA 汉化 + 兼容修复

补丁形式发布，需先取得上游源码：

```bash
git clone https://github.com/g4ixt/QtTinySA.git
cd QtTinySA/src
git apply ../../localisation.patch
python ../../apply_cn_db.py
python QtTinySA.py
```

覆盖 8 个 `.ui` 对话框与主界面的 160+ 条文案，并修复 Basic 机型上的若干兼容问题
（Windows 机型误判、`display out of sync`、点数越界死机、RBW auto 崩溃等），
详见 `desktop/QtTinySA-CN/README.md`。

---

## 四、刷入固件

见 [`docs/刷入指南.md`](docs/刷入指南.md)，包含：

- DFU 模式与 BOOT0 恢复方法
- 刷写命令与超时注意事项（约 10 分钟）
- 常见故障排查（白屏、状态机假死、驱动问题）

---

## 免责声明

1. 本项目为**非官方**修改，与 tinySA 官方无关。
2. `tinySA`、`tinySA Ultra` 及相关标识是其所有者的注册商标，本项目仅作描述性引用。
3. 刷机有风险，可能导致设备无法启动，**风险自负**。
4. 固件按 GNU GPL v3 分发，**不提供任何担保**。

## 致谢

- **Erik Kaashoek**（tinySA 固件作者）
- **TTRFTECH / edy555**（NanoVNA 固件、ChibiOS 分支）
- **Ian Jefferson G4IXT**（QtTinySA）

## 文件校验（SHA-256）

| 文件 | 大小 | SHA-256 |
|---|---|---|
| `SA频谱_v1.1.apk` | 37,338 B | `547A1E41BCD46400CE1744624FE68E90DE4B9B4BE597958090759F40B0466C52` |
| `tinySA_CN_BH6EMP_V0.1_CH.bin` | 116,460 B | `DEE5744BF6563A8032B00A28CFED6E61591A97BD48B735A638005EFF76BCAB61` |
| `tinySA_CN_BH6EMP_V0.1_CH.dfu` | 116,773 B | `24ED4690461FCC4E49F4BDCD12DD8F80049401DC17C3841F9FDEF6B017DD1434` |

下载后可自行核对，确认文件未被篡改：

```bash
sha256sum SA频谱_v1.1.apk
# Windows: Get-FileHash SA频谱_v1.1.apk -Algorithm SHA256
```

## 许可

除另有声明外，本仓库所有内容按 **GNU General Public License v3.0 or later** 分发，见 [`LICENSE`](LICENSE)。

---

AI 总结仅供参考
