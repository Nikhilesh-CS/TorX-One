"""Reject unsafe application logging sinks before release.

Legacy Android Log calls accept only fixed message literals and two arguments.
Dynamic correlation belongs to DeliveryDiagnostics' allowlisted, hashed builder.
This is a conservative source guard, not an audit of third-party native logging.
"""

from dataclasses import dataclass
from pathlib import Path
import argparse
import re


APP_SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java"
DIAGNOSTICS = "com/torxone/app/transport/DeliveryDiagnostics.kt"
DIAGNOSTIC_ARGS = "line(name,relationship,conversation,delivery,sequence,transport,state,attempt,elapsedMs,present)"


@dataclass(frozen=True)
class Token:
    kind: str
    text: str
    start: int
    end: int


@dataclass(frozen=True)
class LogCall:
    level: str
    start: int
    end: int
    arguments: tuple[str, ...]


def _skip_comment(source: str, offset: int) -> int:
    if source.startswith("//", offset):
        end = source.find("\n", offset + 2)
        return len(source) if end < 0 else end
    depth = 1
    offset += 2
    while offset < len(source) and depth:
        if source.startswith("/*", offset):
            depth += 1
            offset += 2
        elif source.startswith("*/", offset):
            depth -= 1
            offset += 2
        else:
            offset += 1
    return offset


def _skip_template(source: str, offset: int) -> int:
    depth = 1
    while offset < len(source) and depth:
        if source.startswith(("//", "/*"), offset):
            offset = _skip_comment(source, offset)
        elif source[offset] in ('"', "'"):
            offset = _skip_string(source, offset)
        elif source[offset] == "{":
            depth += 1
            offset += 1
        elif source[offset] == "}":
            depth -= 1
            offset += 1
        else:
            offset += 1
    return offset


def _skip_string(source: str, offset: int) -> int:
    quote = source[offset]
    raw = source.startswith('"""', offset)
    delimiter = '"""' if raw else quote
    offset += len(delimiter)
    while offset < len(source):
        if source.startswith(delimiter, offset):
            return offset + len(delimiter)
        if not raw and source[offset] == "\\":
            offset += 2
        elif quote == '"' and source.startswith("${", offset):
            offset = _skip_template(source, offset + 2)
        else:
            offset += 1
    return offset


def tokens(source: str) -> list[Token]:
    result = []
    offset = 0
    while offset < len(source):
        if source[offset].isspace():
            offset += 1
        elif source.startswith(("//", "/*"), offset):
            offset = _skip_comment(source, offset)
        elif source[offset] in ('"', "'"):
            end = _skip_string(source, offset)
            result.append(Token("string" if source[offset] == '"' else "char", source[offset:end], offset, end))
            offset = end
        elif source[offset].isalpha() or source[offset] == "_":
            end = offset + 1
            while end < len(source) and (source[end].isalnum() or source[end] == "_"):
                end += 1
            result.append(Token("identifier", source[offset:end], offset, end))
            offset = end
        else:
            result.append(Token("symbol", source[offset], offset, offset + 1))
            offset += 1
    return result


def log_calls(source: str) -> list[LogCall]:
    parsed = tokens(source)
    result = []
    for index in range(len(parsed) - 3):
        head = parsed[index:index + 4]
        if [token.text for token in head[:2]] != ["Log", "."] or head[3].text != "(":
            continue
        if head[2].text not in {"v", "d", "i", "w", "e", "wtf", "println"}:
            continue
        depth = 1
        argument_start = head[3].end
        arguments = []
        cursor = index + 4
        while cursor < len(parsed):
            token = parsed[cursor]
            if token.kind == "symbol":
                if token.text in "([{":
                    depth += 1
                elif token.text in ")]}":
                    depth -= 1
                    if depth == 0:
                        arguments.append(source[argument_start:token.start].strip())
                        break
                elif token.text == "," and depth == 1:
                    arguments.append(source[argument_start:token.start].strip())
                    argument_start = token.end
            cursor += 1
        start = head[0].start
        if index >= 4 and [token.text for token in parsed[index - 4:index]] == ["android", ".", "util", "."]:
            start = parsed[index - 4].start
        end = parsed[cursor].end if cursor < len(parsed) else len(source)
        result.append(LogCall(head[2].text, start, end, tuple(arguments)))
    return result


def fixed_literal(expression: str) -> bool:
    parsed = tokens(expression)
    if len(parsed) != 1 or parsed[0].kind != "string":
        return False
    literal = parsed[0].text
    if literal.startswith('"""'):
        return "$" not in literal[3:-3]
    # Even escaped dollars are rejected conservatively: no message-shaped data.
    return "$" not in literal[1:-1]


def findings(source: str, relative_path: str = "Fixture.kt") -> list[str]:
    issues = []
    parsed = tokens(source)
    fixed_tags = set()
    for index in range(len(parsed) - 4):
        if (parsed[index].text == "const" and parsed[index + 1].text == "val" and
                parsed[index + 3].text == "=" and fixed_literal(parsed[index + 4].text)):
            fixed_tags.add(parsed[index + 2].text)
    for call in log_calls(source):
        line = source.count("\n", 0, call.start) + 1
        # Match the formatter irrespective of whitespace, but no other expression.
        if (relative_path.replace("\\", "/") == DIAGNOSTICS and call.level == "i" and
                len(call.arguments) == 2 and call.arguments[0] == '"TORX_DIAG"' and
                re.sub(r"\s+", "", call.arguments[1]) == DIAGNOSTIC_ARGS):
            continue
        if len(call.arguments) != 2 or not fixed_literal(call.arguments[1]):
            issues.append(f"{relative_path}:{line}: Log.{call.level} requires a fixed message literal and no Throwable")
        elif not fixed_literal(call.arguments[0]) and call.arguments[0] not in fixed_tags:
            issues.append(f"{relative_path}:{line}: Android log tags must be fixed literals or constant literals")
    for index, token in enumerate(parsed):
        if token.kind == "identifier" and token.text in {"print", "println", "printStackTrace"} and index + 1 < len(parsed) and parsed[index + 1].text == "(":
            issues.append(f"{relative_path}:{source.count(chr(10), 0, token.start) + 1}: console/stack logging is forbidden")
    if re.search(r"import\s+android\.util\.Log(?:\.[\w]+)?\s+as\s+", source):
        issues.append(f"{relative_path}: aliased Android logging imports are forbidden")
    if re.search(r"import\s+(?:timber\.log|org\.slf4j|java\.util\.logging)\b", source):
        issues.append(f"{relative_path}: an unreviewed logging sink is forbidden")
    return issues


def scan(source_root: Path = APP_SOURCE) -> list[str]:
    issues = []
    for path in sorted(source_root.rglob("*")):
        if path.suffix in {".kt", ".java"}:
            issues.extend(findings(path.read_text(encoding="utf-8-sig"), path.relative_to(source_root).as_posix()))
    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=APP_SOURCE)
    args = parser.parse_args()
    issues = scan(args.source_root)
    if issues:
        print("\n".join(issues))
        return 1
    print("Application logging privacy source check passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
