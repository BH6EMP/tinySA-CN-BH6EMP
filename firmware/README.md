# 中文固件源码

> **汉化：BH6EMP** · GPL-3.0-or-later

改自 [erikkaashoek/tinySA](https://github.com/erikkaashoek/tinySA) 固件，仅替换显示层文案，不改测量与校准逻辑。

## 汉化相关文件

| 文件 | 说明 |
|---|---|
| `cjkfont.c` / `cjkfont.h` | 14×15 点阵中文字库，268 字，const 存 Flash |
| `ui.c` | 菜单与界面文案汉化（306 处） |
| `ili9341.c` | 绘制层中文分支（`drawstring` / `7x13` / `10x14`） |
| `Makefile` | 加入 `cjkfont.c` 与 `-D__CJK_FONT__` |
| `plot.c` | 版本页署名行等小改动 |

## 编译

ChibiOS 为子模块，需先拉取：

```bash
git submodule update --init --recursive
make -j8 VERSION="tinySA_BH6EMP_V0.1_CH"
```

工具链：`arm-none-eabi-gcc`（实测 15.2.1）。

> 改版本号后必须先 `make clean`。`VERSION` 是命令行变量，不参与依赖判断，增量编译不会重编 `main.c`。

## 资源占用

```
Flash  116,460 / 118,784 B = 98.04%
RAM    16 KB / 16 KB（与原版一致，零增长）
```

## 未汉化的部分（刻意保留）

- **左信息栏**（Atten / RBW / Scan / PAUSED 等）：行距仅 8px，放不下汉字
- **串口命令与协议**：保持 ASCII，脚本兼容
- 走 `lcd_printf` 流式输出的字符串：该路径无中文分支，若改成中文会越界显示乱码

## 生成字库

字库由译文表生成。若新增译文，需重新生成本文件（本仓库只包含生成结果 `cjkfont.c`）：

```
labels/menu_zh.csv  →  字模提取  →  cjkfont.c
```

编码格式：

```
汉字 = [ 0x80 | (idx>>7) ][ 0x20 + (idx & 0x7F) ]
```

---

AI 总结仅供参考
