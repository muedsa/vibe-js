import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UCD = str(ROOT / "build" / "ucd17" / "CaseFolding.txt")
OUT = str(
    ROOT
    / "vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/regex/UnicodeCaseFoldingData.kt"
)


def parse():
    # 仅使用 C/S 状态（简单/通用折叠）；F 为完整折叠、T 为 Turkic 专用映射
    mappings = {}
    priorities = {}
    for line in open(UCD, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = [part.strip() for part in line.split(";")]
        if len(parts) < 3 or parts[1] not in ("C", "S"):
            continue
        source = int(parts[0], 16)
        target_text = parts[2].split()
        if len(target_text) != 1:
            continue
        target = int(target_text[0], 16)
        priority = 1 if parts[1] == "C" else 0
        if source in mappings and priorities[source] >= priority:
            continue
        mappings[source] = target
        priorities[source] = priority
    return sorted(mappings.items())


pairs = parse()
print("case folding simple/common mappings:", len(pairs))

lines = []
chunk = []
size = 0
for source, target in pairs:
    item = f"0x{source:X}, 0x{target:X}"
    if chunk and size + len(item) + 2 > 100:
        lines.append("            " + ", ".join(chunk) + ",")
        chunk = []
        size = 0
    chunk.append(item)
    size += len(item) + 2
if chunk:
    lines.append("            " + ", ".join(chunk) + ",")

with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(
        "package com.muedsa.js.runtime.regex\n\n"
        "/**\n"
        " * Unicode 17.0.0 CaseFolding.txt 的简单/通用映射（状态 C/S）。\n"
        " *\n"
        " * 供忽略大小写正则的 Canonicalize 使用：JS 只应用简单折叠，\n"
        " * 与 Kotlin 的完整大小写转换在部分字符（如 U+1FD3）上不同。\n"
        " */\n"
        "internal object UnicodeCaseFoldingData {\n\n"
        "    private val pairs = intArrayOf(\n"
        + "\n".join(lines)
        + "\n    )\n\n"
        "    /** 返回码点的简单折叠结果；无映射时返回自身。 */\n"
        "    fun simpleFold(codePoint: Int): Int {\n"
        "        var low = 0\n"
        "        var high = pairs.size / 2 - 1\n"
        "        while (low <= high) {\n"
        "            val middle = (low + high) ushr 1\n"
        "            val source = pairs[middle * 2]\n"
        "            when {\n"
        "                codePoint < source -> high = middle - 1\n"
        "                codePoint > source -> low = middle + 1\n"
        "                else -> return pairs[middle * 2 + 1]\n"
        "            }\n"
        "        }\n"
        "        return codePoint\n"
        "    }\n"
        "}\n"
    )
print("wrote", OUT)
