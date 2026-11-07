# SA频谱

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

# 4. 打包（把 classes.dex 加入 base.apk）
& "$bt\zipalign.exe" -p -f 4 "$out\unsigned.apk" "$out\aligned.apk"
& "$jdk\bin\java.exe" -jar "$bt\lib\apksigner.jar" sign --ks <你的密钥> --out "SA频谱.apk" "$out\aligned.apk"
```

## 工作原理

```
仪器 --USB CDC--> data 0 (dBm 浮点) --+
              --> frequencies (Hz) ---+--> 原生分辨率绘制
                                       --> 平均 / 峰值保持 / 标记
```

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
| `level <-76..-6>` | 混频电平 |
| `status` | 返回 `Paused` 或 `Resumed` |

## 关键实现说明

1. **自适应轮询**：先读 `sweeptime` 拿到设备扫描时间，轮询周期 = 扫描时间 + 120ms 余量。
   轮询快于设备扫描会抢占固件扫描线程，导致扫描变慢甚至设备假死。

2. **命令静默下发**：改参数前设置 `quiesce` 让数据循环停下，等当前帧读完再发命令，
   避免与设备回传数据并发。

3. **UI 线程零阻塞**：所有串口操作在后台单线程执行器排队，
   断开连接时的线程 join 也在后台，不冻结界面。

4. **断线保护**：串口操作全部 try/catch 兜底，先停数据线程再关连接，拔线不会闪退。

## 已知限制

- 仅支持 USB CDC 模式（VID 0483 / PID 5740），仪器须正常运行，DFU 模式不可用
- Basic 最多 290 点、频率上限 950 MHz（高波段 240–950M 需 `mode high input`）
- 应用名「SA频谱」为中性名称，与 tinySA 商标所有者无关

---

AI 总结仅供参考
