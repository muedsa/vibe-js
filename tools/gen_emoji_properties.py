import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EMOJI_TEST = str(ROOT / "build" / "ucd17" / "emoji-test.txt")
EMOJI_DATA = str(ROOT / "build" / "ucd17" / "emoji" / "emoji-data.txt")
OUT = str(ROOT / "vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/regex/UnicodeStringPropertyData.kt")

fq = []
for line in open(EMOJI_TEST, encoding="utf-8"):
    line = line.split("#", 1)[0].strip()
    if not line:
        continue
    parts = [p.strip() for p in line.split(";")]
    if len(parts) < 2:
        continue
    seq = tuple(int(x, 16) for x in parts[0].split())
    if parts[1] == "fully-qualified":
        fq.append(seq)

emoji_set, pres_set = set(), set()
for line in open(EMOJI_DATA, encoding="utf-8"):
    line = line.split("#", 1)[0].strip()
    if not line:
        continue
    parts = [p.strip() for p in line.split(";")]
    if len(parts) < 2:
        continue
    rng = parts[0]
    a, b = (rng.split("..") if ".." in rng else (rng, rng))
    values = range(int(a, 16), int(b, 16) + 1)
    if parts[1] == "Emoji":
        emoji_set.update(values)
    elif parts[1] == "Emoji_Presentation":
        pres_set.update(values)

REGIONAL = range(0x1F1E6, 0x1F200)
MODIFIER = range(0x1F3FB, 0x1F400)

fq_basic = {s for s in fq if len(s) == 1 or (len(s) == 2 and s[1] == 0xFE0F)}
basic = set(fq_basic)
for cp in pres_set:
    if cp not in REGIONAL and (cp,) not in basic:
        basic.add((cp,))

keycap = {s for s in fq if len(s) == 3 and s[1] == 0xFE0F and s[2] == 0x20E3}
modifier = {s for s in fq if len(s) == 2 and s[1] in MODIFIER}
flag = {s for s in fq if len(s) == 2 and all(c in REGIONAL for c in s)}
tag = {s for s in fq if 0xE007F in s}
zwj = {s for s in fq if 0x200D in s}

sets = {
    "Basic_Emoji": basic,
    "Emoji_Keycap_Sequence": keycap,
    "RGI_Emoji_Modifier_Sequence": modifier,
    "RGI_Emoji_Flag_Sequence": flag,
    "RGI_Emoji_Tag_Sequence": tag,
    "RGI_Emoji_ZWJ_Sequence": zwj,
}
sets["RGI_Emoji"] = set().union(*sets.values())

expected_counts = {
    "Basic_Emoji": 1400,
    "Emoji_Keycap_Sequence": 12,
    "RGI_Emoji_Modifier_Sequence": 665,
    "RGI_Emoji_Flag_Sequence": 259,
    "RGI_Emoji_Tag_Sequence": 3,
    "RGI_Emoji_ZWJ_Sequence": 1614,
    "RGI_Emoji": 3953,
}
for name, expected in expected_counts.items():
    actual = len(sets[name])
    if actual != expected:
        raise SystemExit(f"{name}: expected {expected}, got {actual}")
    print(name, actual)


def encode(sequence):
    return "-".join(format(cp, "X") for cp in sequence)


def emit_chunks(values):
    chunks = []
    current = []
    size = 0
    for value in sorted(values):
        encoded = encode(value)
        if current and size + len(encoded) + 1 > 12000:
            chunks.append("|".join(current))
            current = []
            size = 0
        current.append(encoded)
        size += len(encoded) + 1
    if current:
        chunks.append("|".join(current))
    return chunks


with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(
        "package com.muedsa.js.runtime.regex\n\n"
        "/**\n"
        " * Unicode 17.0.0 的 RegExp 属性字符串（properties of strings）数据。\n"
        " *\n"
        " * 由 tools/generate_unicode_data.py 从 emoji-test.txt 与 emoji-data.txt 生成：每个值是以 `|` 分隔的\n"
        " * 码点序列，序列内部码点以 `-` 分隔。仅供 `v` 模式的 `\\p{...}` 与 `\\q{...}` 使用。\n"
        " */\n"
        "internal object UnicodeStringPropertyData {\n\n"
        "    /** 解析属性字符串名；未知名称返回 null。 */\n"
        "    fun resolve(expression: String): List<String>? = decoded[expression]\n\n"
        "    private val decoded: Map<String, List<String>> by lazy {\n"
        "        raw.mapValues { (_, chunks) ->\n"
        "            val result = ArrayList<String>()\n"
        "            for (chunk in chunks) {\n"
        "                for (entry in chunk.split('|')) {\n"
        "                    val builder = StringBuilder()\n"
        "                    for (token in entry.split('-')) {\n"
        "                        appendCodePoint(builder, token.toInt(16))\n"
        "                    }\n"
        "                    result.add(builder.toString())\n"
        "                }\n"
        "            }\n"
        "            result\n"
        "        }\n"
        "    }\n\n"
        "    private fun appendCodePoint(builder: StringBuilder, codePoint: Int) {\n"
        "        if (codePoint <= 0xFFFF) {\n"
        "            builder.append(codePoint.toChar())\n"
        "        } else {\n"
        "            val value = codePoint - 0x10000\n"
        "            builder.append(((value shr 10) + 0xD800).toChar())\n"
        "            builder.append(((value and 0x3FF) + 0xDC00).toChar())\n"
        "        }\n"
        "    }\n\n"
    )
    handle.write("    private val raw: Map<String, List<String>> = mapOf(\n")
    for name in sorted(sets):
        chunks = emit_chunks(sets[name])
        handle.write(f'        "{name}" to listOf(\n')
        for chunk in chunks:
            handle.write(f'            "{chunk}",\n')
        handle.write("        ),\n")
    handle.write("    )\n")
    handle.write("}\n")

print("wrote", OUT, os.path.getsize(OUT), "bytes")
