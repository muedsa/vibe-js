import os
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UCD = str(ROOT / "build" / "ucd17")
OUT = str(ROOT / "vibe-js-core/src/commonMain/kotlin/com/muedsa/js/runtime/regex/UnicodePropertyData.kt")
MAX_CP = 0x10FFFF

SUPPORTED_BINARY = [
    "ASCII", "ASCII_Hex_Digit", "Alphabetic", "Any", "Assigned", "Bidi_Control",
    "Bidi_Mirrored", "Case_Ignorable", "Cased", "Changes_When_Casefolded",
    "Changes_When_Casemapped", "Changes_When_Lowercased", "Changes_When_NFKC_Casefolded",
    "Changes_When_Titlecased", "Changes_When_Uppercased", "Dash",
    "Default_Ignorable_Code_Point", "Deprecated", "Diacritic", "Emoji",
    "Emoji_Component", "Emoji_Modifier", "Emoji_Modifier_Base", "Emoji_Presentation",
    "Extended_Pictographic", "Extender", "Grapheme_Base", "Grapheme_Extend",
    "Hex_Digit", "IDS_Binary_Operator", "IDS_Trinary_Operator", "ID_Continue",
    "ID_Start", "Ideographic", "Join_Control", "Logical_Order_Exception",
    "Lowercase", "Math", "Noncharacter_Code_Point", "Pattern_Syntax",
    "Pattern_White_Space", "Quotation_Mark", "Radical", "Regional_Indicator",
    "Sentence_Terminal", "Soft_Dotted", "Terminal_Punctuation", "Unified_Ideograph",
    "Uppercase", "Variation_Selector", "White_Space", "XID_Continue", "XID_Start",
]


def read_lines(path):
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            yield line


def parse_ucd(path):
    """返回 (start, end, fields)：fields 为 ';' 分隔后的其余字段。"""
    for raw in read_lines(path):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = [part.strip() for part in line.split(";")]
        if len(parts) < 2:
            continue
        field = parts[0]
        if ".." in field:
            start, end = field.split("..")
            start, end = int(start, 16), int(end, 16)
        else:
            start = end = int(field, 16)
        yield start, end, parts[1:]


def merge(intervals):
    items = sorted(intervals)
    merged = []
    for start, end in items:
        if merged and start <= merged[-1][1] + 1:
            if end > merged[-1][1]:
                merged[-1] = (merged[-1][0], end)
        else:
            merged.append((start, end))
    return merged


def union(*groups):
    items = []
    for group in groups:
        items.extend(group)
    return merge(items)


def subtract(left, right):
    """left、right 均为已合并区间；返回 left - right。"""
    result = []
    right_index = 0
    for start, end in left:
        cursor = start
        while right_index < len(right) and right[right_index][1] < cursor:
            right_index += 1
        probe = right_index
        while probe < len(right) and right[probe][0] <= end:
            rstart, rend = right[probe]
            if rstart > cursor:
                result.append((cursor, min(end, rstart - 1)))
            cursor = max(cursor, rend + 1)
            if cursor > end:
                break
            probe += 1
        if cursor <= end:
            result.append((cursor, end))
    return merge(result)


# ---------- 别名 ----------
binary_aliases = {}
for raw in read_lines(f"{UCD}/PropertyAliases.txt"):
    line = raw.split("#", 1)[0].strip()
    if not line:
        continue
    parts = [part.strip() for part in line.split(";")]
    if len(parts) < 2:
        continue
    long_name = parts[1]
    if long_name in SUPPORTED_BINARY:
        for alias in parts:
            if alias:
                binary_aliases[alias] = long_name.upper()
print("binary alias entries:", len(binary_aliases))

gc_value_aliases = {}
script_value_aliases = {}
for raw in read_lines(f"{UCD}/PropertyValueAliases.txt"):
    line = raw.split("#", 1)[0].strip()
    if not line:
        continue
    parts = [part.strip() for part in line.split(";")]
    if len(parts) < 2:
        continue
    prop = parts[0]
    if prop == "gc":
        values = parts[1:]
        long_name = values[1] if len(values) > 1 else values[0]
        for alias in values:
            if alias:
                gc_value_aliases[alias] = long_name.upper()
    elif prop == "sc":
        values = parts[1:]
        long_name = values[1] if len(values) > 1 else values[0]
        for alias in values:
            if alias:
                script_value_aliases[alias] = long_name.upper()
print("gc value aliases:", len(gc_value_aliases), "script value aliases:", len(script_value_aliases))

# ---------- General_Category ----------
gc_sets = defaultdict(list)
for start, end, fields in parse_ucd(f"{UCD}/extracted/DerivedGeneralCategory.txt"):
    gc_sets[fields[0]].append((start, end))

all_cps = [(0, MAX_CP)]
assigned = union(*gc_sets.values())
gc_sets["Cn"] = union(gc_sets.get("Cn", []), subtract(all_cps, assigned))

aggregates = {
    "L": ["Lu", "Ll", "Lt", "Lm", "Lo"],
    "LC": ["Lu", "Ll", "Lt"],
    "M": ["Mn", "Mc", "Me"],
    "N": ["Nd", "Nl", "No"],
    "P": ["Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po"],
    "S": ["Sm", "Sc", "Sk", "So"],
    "Z": ["Zs", "Zl", "Zp"],
    "C": ["Cc", "Cf", "Cs", "Co", "Cn"],
}
for name, parts in aggregates.items():
    gc_sets[name] = union(*[gc_sets.get(part, []) for part in parts])
gc_data = {gc_value_aliases.get(name, name).upper(): merge(values)
           for name, values in gc_sets.items()}
print("gc values:", len(gc_data), "total ranges:", sum(len(v) for v in gc_data.values()))

# ---------- Script / Script_Extensions ----------
script_sets = defaultdict(list)
for start, end, fields in parse_ucd(f"{UCD}/Scripts.txt"):
    script_sets[fields[0]].append((start, end))

covered = union(*script_sets.values())
script_sets["Unknown"] = union(script_sets.get("Unknown", []), subtract(all_cps, covered))
script_data = {name.upper(): merge(values) for name, values in script_sets.items()}
for long_name in script_value_aliases.values():
    script_data.setdefault(long_name, [])

explicit_sets = defaultdict(list)
explicit_covered = []
for start, end, fields in parse_ucd(f"{UCD}/ScriptExtensions.txt"):
    explicit_covered.append((start, end))
    for short in fields[0].split():
        long_name = script_value_aliases.get(short)
        if long_name is None:
            raise SystemExit(f"unknown script alias {short}")
        explicit_sets[long_name].append((start, end))
explicit_covered = merge(explicit_covered)

script_ext_sets = {}
for long_name, values in script_data.items():
    base = subtract(values, explicit_covered)
    script_ext_sets[long_name] = union(base, explicit_sets.get(long_name, []))
for long_name in script_value_aliases.values():
    script_ext_sets.setdefault(long_name, [])
print("script values:", len(script_data), "scx values:", len(script_ext_sets),
      "script ranges:", sum(len(v) for v in script_data.values()),
      "scx ranges:", sum(len(v) for v in script_ext_sets.values()))

# ---------- 二进制属性 ----------
binary_sets = defaultdict(list)
for filename in [
    "PropList.txt",
    "DerivedCoreProperties.txt",
    "DerivedNormalizationProps.txt",
    "extracted/DerivedBinaryProperties.txt",
    "emoji/emoji-data.txt",
]:
    for start, end, fields in parse_ucd(f"{UCD}/{filename}"):
        if len(fields) != 1:
            continue
        if fields[0] in SUPPORTED_BINARY:
            binary_sets[fields[0]].append((start, end))

binary_sets["ASCII"] = [(0, 0x7F)]
binary_sets["Any"] = [(0, MAX_CP)]
binary_sets["Assigned"] = subtract(all_cps, gc_data["UNASSIGNED"])
binary_data = {}
for name in SUPPORTED_BINARY:
    values = merge(binary_sets.get(name, []))
    binary_data[name] = values
    # ASCII/Any/Assigned 是 ECMAScript 专有属性，不在 UCD 别名表中
    binary_aliases.setdefault(name, name.upper())
missing = [name for name in SUPPORTED_BINARY if not binary_data[name] and name not in ("ASCII", "Any")]
if missing:
    raise SystemExit(f"missing binary properties: {missing}")
print("binary ranges:", sum(len(v) for v in binary_data.values()))


def encode(intervals):
    return ",".join(f"{start:X}-{end:X}" for start, end in intervals)


def emit_map(name, mapping, value_is_encoded):
    lines = [f"    private val {name}: Map<String, String> = mapOf("]
    for key in sorted(mapping):
        value = mapping[key]
        text = encode(value) if value_is_encoded else value
        lines.append(f'        "{key}" to "{text}",')
    lines.append("    )")
    return "\n".join(lines)


with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(
        "package com.muedsa.js.runtime.regex\n\n"
        "/**\n"
        " * Unicode 17.0.0 属性数据（由 tools/generate_unicode_data.py 自动生成，请勿手工修改）。\n"
        " *\n"
        " * 覆盖 ECMAScript 允许的二进制属性、General_Category 值、Script 与\n"
        " * Script_Extensions 值及其别名。区间以十六进制 \"start-end\" 编码存储，\n"
        " * 首次使用时才解码，避免在字节码/初始化阶段展开大数组。\n"
        " */\n"
        "internal object UnicodePropertyData {\n\n"
        "    /** 解析 `\\p{...}` 花括号内的表达式；名称或取值非法时返回 null。 */\n"
        "    fun resolve(expression: String): UnicodePropertySet? {\n"
        "        if (expression.isEmpty()) return null\n"
        "        val equals = expression.indexOf('=')\n"
        "        if (equals >= 0) {\n"
        "            val property = expression.substring(0, equals)\n"
        "            val value = expression.substring(equals + 1)\n"
        "            if (value.isEmpty() || value.indexOf('=') >= 0) return null\n"
        "            if (!isAliasText(property) || !isAliasText(value)) return null\n"
        "            return when (property) {\n"
        "                \"gc\", \"General_Category\" ->\n"
        "                    generalCategoryAliases[value]?.let { generalCategorySets[it] }\n"
        "                \"sc\", \"Script\" ->\n"
        "                    scriptAliases[value]?.let { scriptSets[it] }\n"
        "                \"scx\", \"Script_Extensions\" ->\n"
        "                    scriptAliases[value]?.let { scriptExtensionSets[it] }\n"
        "                else -> null\n"
        "            }\n"
        "        }\n"
        "        if (!isAliasText(expression)) return null\n"
        "        val key = expression\n"
        "        binaryAliases[key]?.let { alias -> binarySets[alias]?.let { return it } }\n"
        "        generalCategoryAliases[key]?.let { alias ->\n"
        "            generalCategorySets[alias]?.let { return it }\n"
        "        }\n"
        "        return null\n"
        "    }\n\n"
        "    private fun isAliasText(text: String): Boolean {\n"
        "        for (c in text) {\n"
        "            val valid = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'\n"
        "            if (!valid) return false\n"
        "        }\n"
        "        return true\n"
        "    }\n\n"
    )
    handle.write(emit_map("binaryAliases", binary_aliases, False) + "\n\n")
    handle.write(emit_map("generalCategoryAliases", gc_value_aliases, False) + "\n\n")
    handle.write(emit_map("scriptAliases", script_value_aliases, False) + "\n\n")
    handle.write(emit_map("binaryData", {name.upper(): values for name, values in binary_data.items()}, True) + "\n\n")
    handle.write(emit_map("generalCategoryData", {name.upper(): values for name, values in gc_data.items()}, True) + "\n\n")
    handle.write(emit_map("scriptData", {name.upper(): values for name, values in script_data.items()}, True) + "\n\n")
    handle.write(emit_map("scriptExtensionData", {name.upper(): values for name, values in script_ext_sets.items()}, True) + "\n\n")
    handle.write(
        "    private val binarySets: Map<String, UnicodePropertySet> by lazy {\n"
        "        binaryData.mapValues { (_, encoded) -> UnicodePropertySet(encoded) }\n"
        "    }\n\n"
        "    private val generalCategorySets: Map<String, UnicodePropertySet> by lazy {\n"
        "        generalCategoryData.mapValues { (_, encoded) -> UnicodePropertySet(encoded) }\n"
        "    }\n\n"
        "    private val scriptSets: Map<String, UnicodePropertySet> by lazy {\n"
        "        scriptData.mapValues { (_, encoded) -> UnicodePropertySet(encoded) }\n"
        "    }\n\n"
        "    private val scriptExtensionSets: Map<String, UnicodePropertySet> by lazy {\n"
        "        scriptExtensionData.mapValues { (_, encoded) -> UnicodePropertySet(encoded) }\n"
        "    }\n"
        "}\n\n"
        "/** `\\p{...}` 对应的码点区间集合（惰性解码）。 */\n"
        "internal class UnicodePropertySet(private val encoded: String) {\n"
        "    private val ranges: IntArray by lazy(LazyThreadSafetyMode.NONE) { decode(encoded) }\n\n"
        "    /** 供 v 模式集合运算使用的只读区间数组（不可修改）。 */\n"
        "    fun rangeData(): IntArray = ranges\n\n"
        "    fun contains(codePoint: Int): Boolean {\n"
        "        val data = ranges\n"
        "        var low = 0\n"
        "        var high = data.size / 2 - 1\n"
        "        while (low <= high) {\n"
        "            val mid = (low + high) ushr 1\n"
        "            val start = data[mid * 2]\n"
        "            val end = data[mid * 2 + 1]\n"
        "            when {\n"
        "                codePoint < start -> high = mid - 1\n"
        "                codePoint > end -> low = mid + 1\n"
        "                else -> return true\n"
        "            }\n"
        "        }\n"
        "        return false\n"
        "    }\n\n"
        "    private fun decode(text: String): IntArray {\n"
        "        if (text.isEmpty()) return IntArray(0)\n"
        "        val ranges = text.split(',')\n"
        "        val data = IntArray(ranges.size * 2)\n"
        "        for (index in ranges.indices) {\n"
        "            val separator = ranges[index].indexOf('-')\n"
        "            data[index * 2] = ranges[index].substring(0, separator).toInt(16)\n"
        "            data[index * 2 + 1] = ranges[index].substring(separator + 1).toInt(16)\n"
        "        }\n"
        "        return data\n"
        "    }\n"
        "}\n"
    )

print("wrote", OUT, os.path.getsize(OUT), "bytes")
