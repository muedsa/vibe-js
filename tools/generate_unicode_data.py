#!/usr/bin/env python3
"""下载 Unicode 17.0.0 数据并重新生成 RegExp/标识符数据表。

用法（可从任意目录运行）：
    python tools/generate_unicode_data.py

脚本会把需要的 UCD 文件下载到 build/ucd17，然后依次执行：
  - gen_unicode_properties.py   \p{...} 码点属性表
  - gen_emoji_properties.py     属性字符串（emoji 序列）表
  - gen_identifier_data.py      ID_Start / ID_Continue 表
"""

import runpy
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UCD = ROOT / "build" / "ucd17"
UCD_BASE = "https://www.unicode.org/Public/17.0.0/ucd/"
EMOJI_BASE = "https://www.unicode.org/Public/17.0.0/emoji/"

FILES = {
    "PropertyAliases.txt": UCD_BASE + "PropertyAliases.txt",
    "PropertyValueAliases.txt": UCD_BASE + "PropertyValueAliases.txt",
    "Scripts.txt": UCD_BASE + "Scripts.txt",
    "ScriptExtensions.txt": UCD_BASE + "ScriptExtensions.txt",
    "PropList.txt": UCD_BASE + "PropList.txt",
    "DerivedCoreProperties.txt": UCD_BASE + "DerivedCoreProperties.txt",
    "DerivedNormalizationProps.txt": UCD_BASE + "DerivedNormalizationProps.txt",
    "extracted/DerivedGeneralCategory.txt":
        UCD_BASE + "extracted/DerivedGeneralCategory.txt",
    "extracted/DerivedBinaryProperties.txt":
        UCD_BASE + "extracted/DerivedBinaryProperties.txt",
    "emoji/emoji-data.txt": UCD_BASE + "emoji/emoji-data.txt",
    "emoji-test.txt": EMOJI_BASE + "emoji-test.txt",
}


def ensure_data() -> None:
    for relative, url in FILES.items():
        target = UCD / relative
        if target.is_file() and target.stat().st_size > 0:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        print(f"download {url}")
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
        except Exception as error:  # noqa: BLE001 - 下载失败时给出明确提示
            sys.exit(f"下载失败：{url}\n{error}")
        target.write_bytes(data)


def main() -> None:
    ensure_data()
    tools = Path(__file__).resolve().parent
    for script in (
        "gen_unicode_properties.py",
        "gen_emoji_properties.py",
        "gen_identifier_data.py",
    ):
        print(f"run {script}")
        runpy.run_path(str(tools / script), run_name="__main__")


if __name__ == "__main__":
    main()
