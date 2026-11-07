#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""QtTinySA 汉化: 更新配置数据库中下拉框的中文取值

用法: python apply_cn_db.py
说明: QtTinySA 的迹线类型/标记类型存在 SQLite 配置库里, .ui 补丁改不到。
      本脚本同时更新源码目录与用户配置目录中的 QtTSAprefs.db。
汉化: BH6EMP
"""
import os
import shutil
import sqlite3

TRACE = {"Normal": "常规", "Average": "平均", "Max": "最大", "Min": "最小", "Freeze": "冻结"}
MARKER = {"Off": "关闭", "Normal": "常规", "Max": "最大", "Min": "最小"}

TARGETS = [
    os.path.join(os.path.dirname(__file__), "..", "..", "firmware", "..", "QtTSAprefs.db"),
    os.path.expandvars(r"%LOCALAPPDATA%\QtTinySA\QtTSAprefs.db"),
]


def patch_db(path):
    if not os.path.exists(path):
        return None
    shutil.copy2(path, path + ".bak")
    con = sqlite3.connect(path)
    cur = con.cursor()
    n = 0
    for old, new in TRACE.items():
        n += cur.execute(
            "update combo set value=? where type='trace' and value=?", (new, old)
        ).rowcount
    for old, new in MARKER.items():
        n += cur.execute(
            "update combo set value=? where type='marker' and value=?", (new, old)
        ).rowcount
    con.commit()
    con.close()
    return n


if __name__ == "__main__":
    for t in TARGETS:
        p = os.path.abspath(t)
        r = patch_db(p)
        print(("更新 %d 行: " % r if r is not None else "跳过(不存在): ") + p)
