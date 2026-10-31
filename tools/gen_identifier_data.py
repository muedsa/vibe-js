import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UCD = str(ROOT / "build" / "ucd17" / "DerivedCoreProperties.txt")
OUT = str(ROOT / "vibe-js-core/src/commonMain/kotlin/com/muedsa/js/lexer/UnicodeIdentifierData.kt")


def parse(properties):
    result = {name: [] for name in properties}
    for line in open(UCD, encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        parts = [p.strip() for p in line.split(";")]
        if len(parts) < 2 or parts[1] not in result:
            continue
        rng = parts[0]
        if ".." in rng:
            start, end = rng.split("..")
            result[parts[1]].append((int(start, 16), int(end, 16)))
        else:
            value = int(rng, 16)
            result[parts[1]].append((value, value))
    return result


def merge(values):
    values.sort()
    merged = []
    for start, end in values:
        if merged and start <= merged[-1][1] + 1:
            if end > merged[-1][1]:
                merged[-1] = (merged[-1][0], end)
        else:
            merged.append((start, end))
    return merged


def encode(intervals):
    return ",".join(f"{start:X}-{end:X}" for start, end in intervals)


sets = parse(["ID_Start", "ID_Continue"])
start_ranges = merge(sets["ID_Start"])
continue_ranges = merge(sets["ID_Continue"])
print("ID_Start ranges:", len(start_ranges), "ID_Continue ranges:", len(continue_ranges))

chunks = []
current = []
size = 0
for start, end in start_ranges:
    item = f"{start:X}-{end:X}"
    if current and size + len(item) + 1 > 12000:
        chunks.append(",".join(current))
        current = []
        size = 0
    current.append(item)
    size += len(item) + 1
if current:
    chunks.append(",".join(current))

cont_chunks = []
current = []
size = 0
for start, end in continue_ranges:
    item = f"{start:X}-{end:X}"
    if current and size + len(item) + 1 > 12000:
        cont_chunks.append(",".join(current))
        current = []
        size = 0
    current.append(item)
    size += len(item) + 1
if current:
    cont_chunks.append(",".join(current))

with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(
        "package com.muedsa.js.lexer\n\n"
        "/**\n"
        " * Unicode 17.0.0 的 ID_Start / ID_Continue 数据（由 tools/generate_unicode_data.py 从 DerivedCoreProperties.txt 生成）。\n"
        " *\n"
        " * 区间以 \"start-end\" 十六进制编码；首次使用时解码为 IntArray 并做二分查找。\n"
        " * 同时包含 Other_ID_Start / Other_ID_Continue 与 ZWNJ/ZWJ 等规范要求的字符。\n"
        " */\n"
        "internal object UnicodeIdentifierData {\n\n"
        "    fun isStart(codePoint: Int): Boolean = contains(startRanges, codePoint)\n\n"
        "    fun isContinue(codePoint: Int): Boolean = contains(continueRanges, codePoint)\n\n"
        "    private fun contains(ranges: IntArray, codePoint: Int): Boolean {\n"
        "        var low = 0\n"
        "        var high = ranges.size / 2 - 1\n"
        "        while (low <= high) {\n"
        "            val mid = (low + high) ushr 1\n"
        "            val start = ranges[mid * 2]\n"
        "            val end = ranges[mid * 2 + 1]\n"
        "            when {\n"
        "                codePoint < start -> high = mid - 1\n"
        "                codePoint > end -> low = mid + 1\n"
        "                else -> return true\n"
        "            }\n"
        "        }\n"
        "        return false\n"
        "    }\n\n"
        "    private val startRanges: IntArray by lazy(LazyThreadSafetyMode.NONE) {\n"
        "        decode(\n"
    )
    for index, chunk in enumerate(chunks):
        suffix = " +" if index < len(chunks) - 1 else ""
        handle.write(f'            "{chunk}"{suffix}\n')
    handle.write(
        "        )\n"
        "    }\n\n"
        "    private val continueRanges: IntArray by lazy(LazyThreadSafetyMode.NONE) {\n"
        "        decode(\n"
    )
    for index, chunk in enumerate(cont_chunks):
        suffix = " +" if index < len(cont_chunks) - 1 else ""
        handle.write(f'            "{chunk}"{suffix}\n')
    handle.write(
        "        )\n"
        "    }\n\n"
        "    private fun decode(encoded: String): IntArray {\n"
        "        val entries = encoded.split(',')\n"
        "        val ranges = IntArray(entries.size * 2)\n"
        "        for (index in entries.indices) {\n"
        "            val separator = entries[index].indexOf('-')\n"
        "            ranges[index * 2] = entries[index].substring(0, separator).toInt(16)\n"
        "            ranges[index * 2 + 1] = entries[index].substring(separator + 1).toInt(16)\n"
        "        }\n"
        "        return ranges\n"
        "    }\n"
        "}\n"
    )

print("wrote", OUT, os.path.getsize(OUT), "bytes")
