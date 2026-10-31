"""生成 String.prototype.normalize 所需的 Unicode 规范化数据表。

数据来源（UCD 17.0.0）：
  - UnicodeData.txt         分解映射与规范组合类（CCC）
  - CompositionExclusions.txt 不参与规范组合的码点

输出 Kotlin 文件：runtime/UnicodeNormalizationData.kt
表项使用分号分隔、十六进制编码：
  - CCC_RANGES:            "起始-结束:类值"
  - CANONICAL_DECOMP:      "码点:分解序列（逗号分隔）"
  - COMPAT_DECOMP:         "码点:兼容分解序列（逗号分隔）"
  - COMPOSITION:           "首+次>组合码点"
"""

from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UCD = ROOT / "build" / "ucd17"
OUT = (
    ROOT
    / "vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/UnicodeNormalizationData.kt"
)


def parse_unicode_data():
    ccc = {}
    canonical = {}
    compat = {}
    with open(UCD / "UnicodeData.txt", encoding="utf-8") as handle:
        for line in handle:
            parts = line.rstrip("\n").split(";")
            if len(parts) < 6:
                continue
            cp = int(parts[0], 16)
            cls = int(parts[3]) if parts[3] else 0
            if cls:
                ccc[cp] = cls
            mapping = parts[5].strip()
            if not mapping:
                continue
            if mapping.startswith("<"):
                seq = mapping.split(">", 1)[1].strip()
                compat[cp] = [int(item, 16) for item in seq.split()]
            else:
                canonical[cp] = [int(item, 16) for item in mapping.split()]
    return ccc, canonical, compat


def parse_exclusions():
    result = set()
    with open(UCD / "CompositionExclusions.txt", encoding="utf-8") as handle:
        for line in handle:
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            if ".." in line:
                start, end = line.split("..")
                result.update(range(int(start, 16), int(end, 16) + 1))
            else:
                result.add(int(line, 16))
    return result


def ccc_ranges(ccc):
    items = sorted(ccc.items())
    ranges = []
    for cp, cls in items:
        if ranges and ranges[-1][2] == cls and cp == ranges[-1][1] + 1:
            ranges[-1][1] = cp
        else:
            ranges.append([cp, cp, cls])
    return ranges


def chunk_entries(entries, limit=12000):
    chunks = []
    current = []
    size = 0
    for entry in entries:
        if current and size + len(entry) + 1 > limit:
            chunks.append(";".join(current))
            current = []
            size = 0
        current.append(entry)
        size += len(entry) + 1
    if current:
        chunks.append(";".join(current))
    return chunks


def kotlin_constants(prefix, chunks):
    lines = []
    for index, chunk in enumerate(chunks):
        # 每个分块以 ';' 结尾，拼接后条目边界不会丢失
        lines.append(f'    private const val {prefix}_{index} = "{chunk};"')
    return "\n".join(lines)


def main() -> None:
    ccc, canonical, compat = parse_unicode_data()
    exclusions = parse_exclusions()

    # 主组合对：规范分解长度为 2、首字符为起始符、且未列入排除表
    composition = {}
    for cp, seq in canonical.items():
        if len(seq) != 2 or cp in exclusions:
            continue
        first, second = seq
        if ccc.get(first, 0) != 0:
            continue
        composition[(first, second)] = cp

    ccc_entries = [f"{start:X}-{end:X}:{cls}" for start, end, cls in ccc_ranges(ccc)]
    canonical_entries = [
        f"{cp:X}:{','.join(f'{item:X}' for item in seq)}"
        for cp, seq in sorted(canonical.items())
    ]
    compat_entries = [
        f"{cp:X}:{','.join(f'{item:X}' for item in seq)}"
        for cp, seq in sorted(compat.items())
    ]
    composition_entries = [
        f"{first:X},{second:X}>{cp:X}"
        for (first, second), cp in sorted(composition.items())
    ]

    print(
        "ccc ranges:", len(ccc_entries),
        "canonical:", len(canonical_entries),
        "compat:", len(compat_entries),
        "composition:", len(composition_entries),
    )

    ccc_chunks = chunk_entries(ccc_entries)
    canonical_chunks = chunk_entries(canonical_entries)
    compat_chunks = chunk_entries(compat_entries)
    composition_chunks = chunk_entries(composition_entries)

    def joined(prefix, chunks):
        names = [f"{prefix}_{index}" for index in range(len(chunks))]
        return " + ".join(names)

    body = f"""package com.muedsa.js.runtime

/**
 * Unicode 规范化数据表（由 tools/gen_normalization_data.py 生成，请勿手工修改）。
 *
 * 数据来源：Unicode 17.0.0 的 UnicodeData.txt 与 CompositionExclusions.txt。
 */
internal object UnicodeNormalizationData {{

{kotlin_constants("CCC_RANGE", ccc_chunks)}

{kotlin_constants("CANONICAL_DECOMP", canonical_chunks)}

{kotlin_constants("COMPAT_DECOMP", compat_chunks)}

{kotlin_constants("COMPOSITION", composition_chunks)}

    /** 规范组合类（未列出的码点为 0）。 */
    private val cccRanges: List<IntArray> by lazy {{
        parseCcc({joined("CCC_RANGE", ccc_chunks)})
    }}

    /** 规范分解映射（含单元素映射）。 */
    private val canonicalMap: Map<Int, IntArray> by lazy {{
        parseDecompositions({joined("CANONICAL_DECOMP", canonical_chunks)})
    }}

    /** 兼容分解映射（仅 `<tag>` 形式的映射）。 */
    private val compatMap: Map<Int, IntArray> by lazy {{
        parseDecompositions({joined("COMPAT_DECOMP", compat_chunks)})
    }}

    /** 主组合对（不含 Hangul 算法部分）。 */
    private val compositionMap: Map<Long, Int> by lazy {{
        parseCompositions({joined("COMPOSITION", composition_chunks)})
    }}

    /** 组合类值。 */
    fun combiningClass(codePoint: Int): Int {{
        val ranges = cccRanges
        var low = 0
        var high = ranges.size - 1
        while (low <= high) {{
            val middle = (low + high) ushr 1
            val range = ranges[middle]
            when {{
                codePoint < range[0] -> high = middle - 1
                codePoint > range[1] -> low = middle + 1
                else -> return range[2]
            }}
        }}
        return 0
    }}

    /** 规范分解（单层，调用方负责递归）。 */
    fun canonicalDecomposition(codePoint: Int): IntArray? = canonicalMap[codePoint]

    /** 兼容分解（单层，调用方负责递归）。 */
    fun compatibilityDecomposition(codePoint: Int): IntArray? = compatMap[codePoint]

    /** 主组合：a + b 组合为单个码点，不存在时返回 null。 */
    fun composePair(first: Int, second: Int): Int? =
        compositionMap[(first.toLong() shl 21) or second.toLong()]

    private fun parseCcc(text: String): List<IntArray> {{
        if (text.isEmpty()) return emptyList()
        return text.split(';').filter {{ it.isNotEmpty() }}.map {{ entry ->
            val colon = entry.indexOf(':')
            val range = entry.substring(0, colon)
            val value = entry.substring(colon + 1).toInt()
            val dash = range.indexOf('-')
            IntArray(3).also {{ array ->
                array[0] = range.substring(0, dash).toInt(16)
                array[1] = range.substring(dash + 1).toInt(16)
                array[2] = value
            }}
        }}
    }}

    private fun parseDecompositions(text: String): Map<Int, IntArray> {{
        if (text.isEmpty()) return emptyMap()
        val result = HashMap<Int, IntArray>()
        for (entry in text.split(';').filter {{ it.isNotEmpty() }}) {{
            val colon = entry.indexOf(':')
            val codePoint = entry.substring(0, colon).toInt(16)
            val sequence = entry.substring(colon + 1)
            result[codePoint] = sequence.split(',').map {{ it.toInt(16) }}.toIntArray()
        }}
        return result
    }}

    private fun parseCompositions(text: String): Map<Long, Int> {{
        if (text.isEmpty()) return emptyMap()
        val result = HashMap<Long, Int>()
        for (entry in text.split(';').filter {{ it.isNotEmpty() }}) {{
            val greater = entry.indexOf('>')
            val pair = entry.substring(0, greater)
            val composed = entry.substring(greater + 1).toInt(16)
            val comma = pair.indexOf(',')
            val first = pair.substring(0, comma).toInt(16)
            val second = pair.substring(comma + 1).toInt(16)
            result[(first.toLong() shl 21) or second.toLong()] = composed
        }}
        return result
    }}
}}
"""

    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(body)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
