# SA频谱（当前版本 v1.1）

> **开发/汉化：BH6EMP**

配合 tinySA Basic 使用的安卓端频谱显示与控制工具。

## 环境

- JDK 17
- Android SDK：platform android-33，build-tools 30.0.3
- 无需 Gradle，手工调用工具链

## 构建

```powershell
$jdk = "<JDK17>"; $sdk = "<AndroidSdk>"
$bt = "$sdk\build-tools\30.0.3"; $plat = "$sdk\platforms\android-33\android.jar"
$out = "build"

# 1. 资源
& "$bt\aapt2.exe" compile --dir res -o "$out\res.zip"
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $plat --manifest AndroidManifest.xml --auto-add-overlay "$out\res.zip"

# 2. 编译
& "$jdk\bin\javac.exe" -encoding UTF-8 -source 11 -target 11 -classpath $plat -d "$out\classes" (Get-ChildItem src -Recurse -Filter *.java).FullName

# 3. dex
& "$jdk\bin\java.exe" -cp "$bt\lib\d8.jar" com.android.tools.r8.D8 --lib $plat --min-api 21 --output "$out\dex" (Get-ChildItem "$out\classes" -Recurse -Filter *.class).FullName

# 4. 打包
& "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk"
& "$jdk\bin\java.exe" -jar "$bt\lib\apksigner.jar" sign --ks <你的密钥> --out "SA频谱.apk" "$out\aligned.apk"
```

## 版本

| 版本 | 主要变化 |
|---|---|
| v1.1 | 去掉标题栏；息屏唤醒自动重连、设置不丢；移除图内"等待数据"文字 |
| v1.0 | 曲线/瀑布图/标记/单位/纵轴可调/自适应轮询/断线保护 |

## 功能

- **曲线**：读 `frequencies` 与 `data 0`，按手机原生分辨率绘制
- **瀑布图**：时间-频率-强度，256 级色标，可开关
- **迹线处理**：实时 / 平均4 / 平均16 / 峰值保持
- **标记**：拖动或输入频率定位
- **单位**：dBm / dBμV / dBmV / Vrms / Vpp / W
- **纵轴**：可手动设置范围，刻度自动取整
- **量程**：LOW 口 0–350M 两段、HIGH 口 240–950M 两段，自动切换 `mode low/high input`
- **参数**：带宽 / 扫描时间 / 衰减 / 点数
- **自适应轮询**：按设备扫描时间取数，避免抢占固件扫描线程
- **断线保护**：拔线或关机不闪退

## 协议要点

| 命令 | 返回 |
|---|---|
| `frequencies` | 每行一个整数 Hz |
| `data <0-2>` | 每行一个浮点 dBm |
| `sweep <起><止>[点数]` | 设置扫频范围（**点数上限 290**） |
| `mode low\|high input` | 切换 LOW / HIGH 输入口 |
| `rbw <值\|auto>` | 分辨率带宽 |
| `sweeptime <秒>` | 扫描时间 0.003–60 |
| `attenuate <0-31\|auto>` | 衰减 |
| `status` | 返回 `Paused` 或 `Resumed` |

## 关键实现说明

1. **自适应轮询**：先读 `sweeptime`，轮询周期 = 扫描时间 + 120ms。轮询快于设备扫描会抢占固件扫描线程。
2. **命令静默下发**：改参数前设置 `quiesce` 让数据循环停下，避免与回传数据并发。
3. **UI 线程零阻塞**：串口操作全部在后台单线程执行器排队。
4. **状态持久化**：设置写入 `SharedPreferences`，重建或息屏唤醒后恢复并自动重连。

## 已知限制

- 仅支持 USB CDC 模式（VID 0483 / PID 5740），仪器须正常运行，DFU 模式不可用
- Basic 最多 290 点、频率上限 950 MHz（高波段 240–950M 需 `mode high input`）
- 应用名「SA频谱」为中性名称，与 tinySA 商标所有者无关

---

AI 总结仅供参考
