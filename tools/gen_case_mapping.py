import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UCD = ROOT / "build" / "ucd17"
OUT = (
    ROOT
    / "vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/UnicodeCaseMappingData.kt"
)


def parse_simple():
    """解析 UnicodeData.txt 的简单大写/小写映射（字段 12/13）。"""
    upper = {}
    lower = {}
    for line in open(UCD / "UnicodeData.txt", encoding="utf-8"):
        fields = line.split(";")
        code_point = int(fields[0], 16)
        if fields[12]:
            upper[code_point] = int(fields[12], 16)
        if fields[13]:
            lower[code_point] = int(fields[13], 16)
    return upper, lower


def parse_full():
    """解析 SpecialCasing.txt 的无条件完整映射（忽略 locale/context 条件项）。"""
    upper = {}
    lower = {}
    for line in open(UCD / "SpecialCasing.txt", encoding="utf-8"):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        fields = [part.strip() for part in line.split(";")]
        if len(fields) < 5 or fields[4]:
            continue
        code_point = int(fields[0], 16)
        if fields[1]:
            mapped = [int(part, 16) for part in fields[1].split()]
            if mapped != [code_point]:
                lower[code_point] = mapped
        if fields[3]:
            mapped = [int(part, 16) for part in fields[3].split()]
            if mapped != [code_point]:
                upper[code_point] = mapped
    return upper, lower


def format_ints(values, indent="        "):
    """把整数序列格式化为每行约 100 字符的 Kotlin intArrayOf 片段。"""
    lines = []
    chunk = []
    size = 0
    for value in values:
        item = f"0x{value:X}"
        if chunk and size + len(item) + 2 > 100:
            lines.append(indent + ", ".join(chunk) + ",")
            chunk = []
            size = 0
        chunk.append(item)
        size += len(item) + 2
    if chunk:
        lines.append(indent + ", ".join(chunk) + ",")
    return "\n".join(lines)


def pairs(mapping):
    """字典转升序 (source, target) 扁平数组。"""
    values = []
    for source in sorted(mapping):
        values.append(source)
        values.append(mapping[source])
    return values


def full_tables(mapping):
    """完整映射转 keys/offsets/values 三段结构。"""
    keys = []
    offsets = [0]
    values = []
    for source in sorted(mapping):
        keys.append(source)
        values.extend(mapping[source])
        offsets.append(len(values))
    return keys, offsets, values


simple_upper, simple_lower = parse_simple()
full_upper, full_lower = parse_full()
upper_keys, upper_offsets, upper_values = full_tables(full_upper)
lower_keys, lower_offsets, lower_values = full_tables(full_lower)

text = f"""package com.muedsa.js.runtime

/**
 * Unicode 17.0.0 大小写映射数据（由 tools/gen_case_mapping.py 自动生成，请勿手工修改）。
 *
 * - 简单映射来自 UnicodeData.txt 的大写/小写字段，按键升序存为 (source, target) 对；
 * - 完整映射来自 SpecialCasing.txt 的无条件项，使用 keys/offsets/values 三段编码；
 * - 有条件（locale/context）的完整映射不在此表，词尾 Sigma 由运行时代码处理。
 */
internal object UnicodeCaseMappingData {{

    /** UnicodeData 简单大写映射，每两个元素为 (source, target)。 */
    val UPPER_SIMPLE: IntArray = intArrayOf(
{format_ints(pairs(simple_upper))}
    )

    /** UnicodeData 简单小写映射，每两个元素为 (source, target)。 */
    val LOWER_SIMPLE: IntArray = intArrayOf(
{format_ints(pairs(simple_lower))}
    )

    /** 完整大写映射的源码点（升序）。 */
    val UPPER_FULL_KEYS: IntArray = intArrayOf(
{format_ints(upper_keys)}
    )

    /** 完整大写映射的 values 区间偏移，长度比 keys 多 1。 */
    val UPPER_FULL_OFFSETS: IntArray = intArrayOf(
{format_ints(upper_offsets)}
    )

    /** 完整大写映射的目标码点序列。 */
    val UPPER_FULL_VALUES: IntArray = intArrayOf(
{format_ints(upper_values)}
    )

    /** 完整小写映射的源码点（升序）。 */
    val LOWER_FULL_KEYS: IntArray = intArrayOf(
{format_ints(lower_keys)}
    )

    /** 完整小写映射的 values 区间偏移，长度比 keys 多 1。 */
    val LOWER_FULL_OFFSETS: IntArray = intArrayOf(
{format_ints(lower_offsets)}
    )

    /** 完整小写映射的目标码点序列。 */
    val LOWER_FULL_VALUES: IntArray = intArrayOf(
{format_ints(lower_values)}
    )
}}
"""

with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(text)

print(
    "case mapping tables:",
    f"simple upper={len(simple_upper)}",
    f"simple lower={len(simple_lower)}",
    f"full upper={len(full_upper)}",
    f"full lower={len(full_lower)}",
)
