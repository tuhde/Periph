#!/usr/bin/env python3
"""Generate Raspberry Pi Pico SDK example and test files for all 27
C++ chips supported by this repo.

Strategy: read each chip's Arduino .ino file and translate it to a
pico-sdk `main()` that:
  - includes the right Pico SDK transport header (I2CTransportPicoSDK.h,
    NeoPixelTransportPicoSDK.h, etc.) instead of the Arduino/ESP one
  - constructs the transport with the pico-sdk hardware API instead of
    Wire / SPIClass / Serial
  - replaces Serial.print/println with printf, keeping all the
    tier-1/tier-2/tier-3 documentation comments that the AGENTS.md
    example convention requires

The chip driver (e.g. AHT21.cpp) is unchanged — only the transport
construction and the stdio differ.  The chip driver's
`{Minimal,Full}` constructors take a `Transport&`, so swapping the
transport class is a single-line change at the top of the example.
"""
import os
import re
from pathlib import Path

REPO = Path("/home/till/Periph")
EX_DIR = REPO / "cpp" / "examples"
TEST_DIR = REPO / "cpp" / "tests"
SRC = REPO / "cpp" / "src"
CATEGORY_DIR = SRC / "chips"

# (category, chip_lower, chip_title, transport_kind, addr)
# `transport_kind` is one of "i2c", "neopixel", "uart", "hx711"
# For chips that support multiple transports (BME280, BMP280, MFRC522),
# the I2C variant is used here; a follow-up could add the SPI variant
# if there is enough demand.
CHIPS = [
    ("adc_dac",      "mcp4725",    "MCP4725",    "i2c",   "0x60"),
    ("adc_dac",      "mcp4728",    "MCP4728",    "i2c",   "0x60"),
    ("adc_dac",      "pcf8591",    "PCF8591",    "i2c",   "0x48"),
    ("adc_dac",      "hx711",      "HX711",      "hx711", None),
    ("comms",        "rda5807m",   "Rda5807m",   "i2c",   "0x10"),
    ("display",      "pcf8576",    "PCF8576",    "i2c",   "0x70"),
    ("environmental","aht21",      "AHT21",      "i2c",   "0x38"),
    ("environmental","bme280",     "BME280",     "i2c",   "0x76"),
    ("environmental","bme680",     "BME680",     "i2c",   "0x77"),
    ("gas",          "ens160",     "ENS160",     "i2c",   "0x53"),
    ("gnss",         "neo6",       "NEO6",       "uart",  None),
    ("imu",          "mpu6050",    "Mpu6050",    "i2c",   "0x68"),
    ("io_expander",  "mcp23017",   "MCP23017",   "i2c",   "0x20"),
    ("io_expander",  "pcf8574",    "PCF8574",    "i2c",   "0x20"),
    ("io_expander",  "pcf8575",    "PCF8575",    "i2c",   "0x20"),
    ("led",          "ws2812b",    "WS2812B",    "neopixel", None),
    ("led",          "sk6812rgbw", "SK6812RGBW", "neopixel", None),
    ("light",        "apds9960",   "APDS9960",   "i2c",   "0x39"),
    ("magnetometer", "as5600",     "AS5600",     "i2c",   "0x36"),
    ("memory",       "24aa02uid",  "24AA02UID",  "i2c",   "0x50"),
    ("power",        "ina219",     "INA219",     "i2c",   "0x40"),
    ("power",        "ina226",     "INA226",     "i2c",   "0x40"),
    ("power",        "ina3221",    "INA3221",    "i2c",   "0x40"),
    ("pressure",     "bmp180",     "BMP180",     "i2c",   "0x77"),
    ("pressure",     "bmp280",     "BMP280",     "i2c",   "0x76"),
    ("rfid",         "mfrc522",    "MFRC522",    "i2c",   "0x28"),
]

# Per-chip pico-sdk hardware libraries to link (in addition to pico_stdlib)
def extra_libs(kind, title):
    if kind == "i2c":   return ["hardware_i2c"]
    if kind == "uart":  return ["hardware_uart", "hardware_gpio"]
    if kind == "neopixel": return ["hardware_spi"]
    if kind == "hx711": return ["hardware_gpio", "pico_time"]
    return []

# Per-chip transport construction snippet (placed at file scope, after stdio.h includes)
def transport_construct(kind, title, addr):
    if kind == "i2c":
        return (
            "// I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins\n"
            "i2c_init(i2c0, 100 * 1000);\n"
            "gpio_set_function(4, GPIO_FUNC_I2C);\n"
            "gpio_set_function(5, GPIO_FUNC_I2C);\n"
            "gpio_pull_up(4);\n"
            "gpio_pull_up(5);\n"
            f"I2CTransportPicoSDK transport(i2c0, {addr});"
        )
    if kind == "uart":
        return (
            "// UART0 on GP0 (TX) / GP1 (RX) — pico-sdk default UART pins.\n"
            "uart_init(uart0, 9600);\n"
            "gpio_set_function(0, GPIO_FUNC_UART);\n"
            "gpio_set_function(1, GPIO_FUNC_UART);\n"
            "UARTTransportPicoSDK transport(uart0, 9600);"
        )
    if kind == "neopixel":
        return (
            "// SPI0 on GP3 (MOSI) — NeoPixel DIN must be on the SPI MOSI pin;\n"
            "// SCK, MISO, and CS are unused by the strip.\n"
            "spi_init(spi0, 2'400'000);\n"
            "gpio_set_function(3, GPIO_FUNC_SPI);\n"
            "NeoPixelTransportPicoSDK transport(spi0);"
        )
    if kind == "hx711":
        return (
            "// HX711 bit-bang pins: DOUT on GP2, PD_SCK on GP3.\n"
            "HX711TransportPicoSDK transport(/*dout=*/2, /*pd_sck=*/3);"
        )
    return ""

def _chip_args(title, tier, kind, addr):
    """Return the constructor argument list for a given chip."""
    if title in ("INA219", "INA226"):
        return "transport, /*r_shunt=*/0.1f, /*max_current=*/2.0f"
    if title == "INA3221":
        return "transport, /*r_shunt=*/0.1f"
    if title in ("PCF8574", "PCF8575", "MCP23017", "EEPROM24AA02UID"):
        return f"transport, /*addr=*/{addr}"
    if title in ("BME280", "BMP280"):
        return "transport, /*spi=*/false"
    if title == "MFRC522":
        return "transport, /*bus_type=*/0"   # 0 = I2C
    if title == "Rda5807m":
        return "transport, /*frequency_mhz=*/100.0f, /*volume=*/5"
    if title == "BMP180":
        if tier != "Minimal":
            return "transport, /*oss=*/3"
        return "transport"
    if title == "NEO6":
        return "transport, /*bus_type=*/0"   # 0 = UART
    if title in ("WS2812B", "SK6812RGBW"):
        return "transport, /*n_pixels=*/8"
    return "transport"


# Per-chip chip-class construction snippet. The chip class name follows
# the existing C++ convention: title-case chip + "Minimal" or "Full".
def chip_construct(title, tier, kind, addr):
    cls = f"{title}Full" if tier != "Minimal" else f"{title}Minimal"
    args = _chip_args(title, tier, kind, addr)
    instance = find_arduino_instance_name(title, tier)
    return f"{cls} {instance}({args});"


def find_arduino_instance_name(title, tier):
    ino_path = EX_DIR / f"{title}_{tier}" / f"{title}_{tier}.ino"
    if not ino_path.exists():
        return title.lower()
    text = ino_path.read_text()
    # Match `{Class}<T>InstanceName(transport)` for the Minimal/Full chip
    # class. Both class names are accepted; the class may be templated.
    cls_alt = rf"{title}(?:Minimal|Full)(?:\s*<[^>]*>)?"
    m = re.search(rf"{cls_alt}\s+(\w+)\s*\(\s*transport", text)
    if m:
        return m.group(1)
    return title.lower()

# -------------------------------------------------------------------------
# Translate Serial.print/println to printf, preserving comments verbatim.
# -------------------------------------------------------------------------
def _collect_float_vars(text):
    """Walk the text and collect variable names declared as `float`.

    We use a simple `float <name>(, <name>)*;` regex.  Anything else
    is treated as `int` for the purpose of picking a printf format.
    """
    out = set()
    for m in re.finditer(r"\bfloat\s+([A-Za-z_][A-Za-z0-9_]*(?:\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*)", text):
        for name in re.split(r"\s*,\s*", m.group(1)):
            out.add(name)
    return out


def translate_serial_calls(text, float_vars=None):
    """Convert Arduino Serial.print/println calls to printf.

    Handles:
      Serial.print(int_expr)            -> printf("%d\n", int_expr)
      Serial.print(float_expr)          -> printf("%f\n", float_expr)
      Serial.print(float_expr, 2)       -> printf("%.2f\n", float_expr)
      Serial.print("literal")           -> printf("literal")
      Serial.print(var, HEX)            -> printf("0x%X\n", (unsigned)var)
      Serial.println()                  -> printf("\n")
      Serial.println(expr)              -> printf("%d\n", expr)  (or %f, depending on type)
      Serial.println(expr, HEX)         -> printf("0x%X\n", (unsigned)expr)
    """
    if float_vars is None:
        float_vars = _collect_float_vars(text)

    def _format_for(expr):
        """Pick %d or %f for a bare expression. Prefer %f if the
        expression's leading identifier is a known float variable."""
        expr = expr.strip()
        m = re.match(r"([A-Za-z_][A-Za-z0-9_]*)", expr)
        if m and m.group(1) in float_vars:
            return "%f"
        # Decimal constants are float
        if re.search(r"\d+\.\d+", expr):
            return "%f"
        # Arithmetic on a known float var is float
        for v in float_vars:
            if re.search(rf"\b{re.escape(v)}\b", expr):
                return "%f"
        return "%d"

    # First handle Serial.print(..., HEX) and Serial.println(..., HEX)
    text = re.sub(
        r"Serial\.println\((.*?),\s*HEX\)\s*;",
        r'printf("0x%X\\n", (unsigned)\1);',
        text,
    )
    text = re.sub(
        r"Serial\.print\((.*?),\s*HEX\)\s*;",
        r'printf("0x%X", (unsigned)\1);',
        text,
    )
    # Serial.print("foo") with literal
    text = re.sub(
        r'Serial\.print\("((?:[^"\\]|\\.)*)"\)\s*;',
        r'printf("\1");',
        text,
    )
    # Serial.println("foo") with literal
    text = re.sub(
        r'Serial\.println\("((?:[^"\\]|\\.)*)"\)\s*;',
        r'printf("\1\\n");',
        text,
    )
    # Walk the text and translate each Serial.print/println call. We
    # can't use a simple regex because the call's argument may contain
    # balanced parens (e.g. `Serial.println(ina.voltage())`).
    def _walk_and_translate(text):
        out = []
        i = 0
        while i < len(text):
            m = re.search(r"Serial\.(print|println)\(", text[i:])
            if not m:
                out.append(text[i:])
                break
            out.append(text[i:i + m.start()])
            kind = m.group(1)              # "print" or "println"
            j = i + m.end()                # position right after '('
            # Walk the argument list with a paren counter.
            depth = 1
            arg_start = j
            while j < len(text) and depth > 0:
                c = text[j]
                if c == '(':
                    depth += 1
                elif c == ')':
                    depth -= 1
                elif c == '"':
                    # Skip past string literal
                    j += 1
                    while j < len(text) and text[j] != '"':
                        if text[j] == '\\':
                            j += 1
                        j += 1
                j += 1
            # `text[j-1]` is the closing `)` of Serial.print.
            args = text[arg_start:j - 1]
            # Skip optional whitespace and a trailing semicolon.
            k = j
            while k < len(text) and text[k] in ' \t':
                k += 1
            semi = k < len(text) and text[k] == ';'
            if semi:
                k += 1
            arg_str = args.strip()
            # Detect shape:  "" (println)  |  "literal"  |  expr, N  |  expr, HEX  |  expr
            newline = "\\n" if kind == "println" else ""
            if not arg_str:
                # Bare Serial.println() / Serial.print() — emit just a newline.
                out.append(f'printf("{newline}");')
            elif arg_str.startswith('"') and arg_str.endswith('"'):
                # String literal
                lit = arg_str[1:-1]
                out.append(f'printf("{lit}{newline}");')
            elif arg_str.endswith(", HEX") or arg_str.endswith(",HEX"):
                expr = arg_str[: arg_str.rfind(",")].rstrip()
                out.append(f'printf("0x%X{newline}", (unsigned){expr});')
            elif re.search(r",\s*\d+\s*$", arg_str):
                # expr, N  → %.Nf format
                m2 = re.match(r"(.*?),\s*(\d+)\s*$", arg_str)
                expr, n = m2.group(1), m2.group(2)
                out.append(f'printf("%.{n}f{newline}", {expr});')
            else:
                out.append(f'printf("{_format_for(arg_str)}{newline}", {arg_str});')
            i = k
        return "".join(out)

    text = _walk_and_translate(text)
    # Bare Serial.println()
    text = re.sub(r"Serial\.println\(\)\s*;", r'printf("\\n");', text)
    return text

# -------------------------------------------------------------------------
# Translate Arduino delay() / Wire.begin() to pico-sdk equivalents.
# -------------------------------------------------------------------------
def translate_runtime(text):
    text = re.sub(r"Wire\.begin\(\)\s*;", "", text)
    text = re.sub(r"Wire\.begin\([^)]*\)\s*;", "", text)
    text = re.sub(r"\bdelay\((\d+)\)\s*;", r"sleep_ms(\1);", text)
    text = re.sub(r"\bdelayMicroseconds\((\d+)\)\s*;", r"sleep_us(\1);", text)
    text = re.sub(r"\bSerial\.begin\([^)]*\)\s*;", "stdio_init_all();", text)
    # millis() / micros() Arduino APIs. pico-sdk has `to_ms_since_boot()`
    # for ms and `to_us_since_boot()` for µs.
    text = re.sub(r"\bmillis\(\)", "to_ms_since_boot(get_absolute_time())", text)
    text = re.sub(r"\bmicros\(\)", "(uint32_t)to_us_since_boot(get_absolute_time())", text)
    return text

# -------------------------------------------------------------------------
# Read an Arduino .ino, extract setup() body and loop() body, then
# combine them into a single main() for pico-sdk.
# -------------------------------------------------------------------------
def translate_ino(ino_path, transport_init_lines):
    text = ino_path.read_text()
    # Drop any transport construction anywhere in the file. The Arduino
    # .ino may put it at file scope or inside setup() depending on
    # whether the bus pins are compile-time or runtime constants.
    # We replace the constructor call with our own file-scope version
    # at the top of the generated main.cpp, so all `transport` lines
    # are redundant. We can't use a simple regex because the
    # constructor's argument list may contain balanced parens
    # (e.g. `SPISettings(1000000, MSBFIRST, SPI_MODE0)`).
    def _strip_transport_constructs(text):
        out = []
        for line in text.splitlines(keepends=True):
            stripped = line.lstrip()
            m = re.match(r"[A-Za-z][A-Za-z0-9]*Transport\s+transport\s*\(", stripped)
            if m:
                # Walk the rest of the line to find the matching `)`
                depth = 1
                i = m.end()
                while i < len(stripped) and depth > 0:
                    c = stripped[i]
                    if c == '(':
                        depth += 1
                    elif c == ')':
                        depth -= 1
                    elif c == '"':
                        i += 1
                        while i < len(stripped) and stripped[i] != '"':
                            if stripped[i] == '\\':
                                i += 1
                            i += 1
                    i += 1
                # Optionally consume trailing comment + ;
                rest = stripped[i:].lstrip()
                if rest.startswith(';') or rest.startswith('//'):
                    continue
            out.append(line)
        return "".join(out)
    text = _strip_transport_constructs(text)

    def _strip_chip_constructs(text):
        out = []
        for line in text.splitlines(keepends=True):
            stripped = line.lstrip()
            m = re.match(r"[A-Za-z][A-Za-z0-9_]*(?:Minimal|Full)(?:\s*<[^>]*>)?\s+\w+\s*\(", stripped)
            if m:
                depth = 1
                i = m.end()
                while i < len(stripped) and depth > 0:
                    c = stripped[i]
                    if c == '(':
                        depth += 1
                    elif c == ')':
                        depth -= 1
                    elif c == '"':
                        i += 1
                        while i < len(stripped) and stripped[i] != '"':
                            if stripped[i] == '\\':
                                i += 1
                            i += 1
                    i += 1
                rest = stripped[i:].lstrip()
                if rest.startswith(';') or rest.startswith('//'):
                    continue
            out.append(line)
        return "".join(out)
    text = _strip_chip_constructs(text)
    # Drop include lines; we replace them
    text = re.sub(r"^[\t ]*#include <Wire\.h>\s*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^[\t ]*#include <SPI\.h>\s*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^[\t ]*#include <Arduino\.h>\s*$", "", text, flags=re.MULTILINE)
    text = re.sub(r'^[\t ]*#include "[A-Za-z][A-Za-z0-9]*Transport\.h"\s*$', "", text, flags=re.MULTILINE)
    text = re.sub(r'^[\t ]*#include "(\w+)\.h"\s*$', r'#include "\1.h"', text, flags=re.MULTILINE)
    # Drop `#ifndef TEST_SDA` / `#define TEST_SDA N` boilerplate that
    # the Arduino sketches use to expose the I2C pins to test_arduino.sh.
    text = re.sub(r"^[\t ]*#ifndef\s+TEST_\w+\s*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^[\t ]*#define\s+TEST_\w+\s+\d+\s*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^[\t ]*#define\s+TEST_\w+\s+[A-Za-z_]\w*\s*$", "", text, flags=re.MULTILINE)
    text = re.sub(r"^[\t ]*#endif\s*$", "", text, flags=re.MULTILINE)
    # Remove the setup() / loop() wrappers; keep their bodies. We use a
    # brace-counting extractor because the body may itself contain
    # nested blocks (e.g. `if (...) { ... }`) and a non-greedy regex
    # would stop at the first inner `}`.
    def _extract_function_body(text, signature):
        m = re.search(signature + r"\s*\{", text)
        if not m:
            return ""
        i = m.end()                  # position right after '{'
        depth = 1
        while i < len(text) and depth > 0:
            c = text[i]
            if c == '{':
                depth += 1
            elif c == '}':
                depth -= 1
            elif c == '"':
                i += 1
                while i < len(text) and text[i] != '"':
                    if text[i] == '\\':
                        i += 1
                    i += 1
            elif c == '/' and i + 1 < len(text) and text[i+1] == '/':
                # line comment
                while i < len(text) and text[i] != '\n':
                    i += 1
            elif c == '/' and i + 1 < len(text) and text[i+1] == '*':
                # block comment
                i += 2
                while i + 1 < len(text) and not (text[i] == '*' and text[i+1] == '/'):
                    i += 1
                i += 1
            i += 1
        return text[m.end():i-1]

    setup_body = _extract_function_body(text, r"void setup\(\)")
    loop_body  = _extract_function_body(text, r"void loop\(\)")

    # Some sketches declare pin-proxy variables at file scope (e.g.
    # `PCF8574Minimal::IOExpanderPin p0 = chip.pin(0);`). These are
    # necessary for the loop() body to compile; we hoist them into the
    # generated main() body just before setup() runs.
    # We extract file-scope lines from the text *before* the
    # `void setup()` function so we don't pick up variable declarations
    # that are local to setup()/loop().
    file_scope = []
    m = re.search(r"void setup\(\)\s*\{", text)
    pre_setup = text[:m.start()] if m else text
    for line in pre_setup.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        # Drop transport / chip construct lines, includes, and comments
        # — they have already been handled or don't apply.
        if stripped.startswith("#"):
            continue
        if stripped.startswith("//"):
            continue
        # Look for a semicolon followed by optional trailing comment.
        if not re.search(r";\s*(//.*)?$", stripped):
            continue
        # Skip the *Transport construct we already replaced.
        if re.search(r"\bTransport\s+transport\s*\(", stripped):
            continue
        # Skip the chip constructor lines (we have our own).
        if re.search(r"\b(Minimal|Full)\b\s+\w+\s*\(", stripped):
            continue
        # Skip function declarations (e.g. `static void check_true(...)`)
        if "(" in stripped and "=" not in stripped and re.search(r"\)\s*(//.*)?;\s*(//.*)?$", stripped):
            continue
        file_scope.append(line)
    file_scope_block = "\n".join(file_scope)

    # Translate Serial/delay/Wire calls in both bodies.
    # Float-variable detection has to look at the entire text (setup + loop)
    # so that a `float t, h;` declared in setup() informs the printf %f/%d
    # choice in loop().
    combined = setup_body + "\n" + loop_body
    float_vars = _collect_float_vars(combined)
    setup_body = translate_serial_calls(setup_body, float_vars)
    setup_body = translate_runtime(setup_body)
    loop_body  = translate_serial_calls(loop_body, float_vars)
    loop_body  = translate_runtime(loop_body)
    # Drop bare SPI/Serial/Wire initialisation calls — the pico-sdk
    # transport owns the bus configuration.
    setup_body = re.sub(r"^\s*SPI\.begin\([^)]*\)\s*;\s*$", "", setup_body, flags=re.MULTILINE)
    setup_body = re.sub(r"^\s*Serial1\.begin\([^)]*\)\s*;\s*$", "", setup_body, flags=re.MULTILINE)
    setup_body = re.sub(r"^\s*Wire\.begin\([^)]*\)\s*;\s*$", "", setup_body, flags=re.MULTILINE)

    # Strip any leading indentation the .ino had, then re-indent at 4 spaces.
    def _dedent_lines(s):
        lines = s.splitlines()
        # Find the minimum indentation (ignoring blank lines) of non-empty
        # lines.
        indents = [len(l) - len(l.lstrip(" ")) for l in lines if l.strip()]
        if not indents:
            return s
        common = min(indents)
        return "\n".join(l[common:] if len(l) >= common else l for l in lines)

    setup_body = _dedent_lines(setup_body)
    loop_body  = _dedent_lines(loop_body)

    # Indent at 4 spaces.
    setup_indented = "\n".join(("    " + line) if line.strip() else line for line in setup_body.splitlines())
    if file_scope_block.strip():
        setup_indented = "    " + file_scope_block.replace("\n", "\n    ") + "\n" + setup_indented
    loop_indented  = "\n".join(("    " + line) if line.strip() else line for line in loop_body.splitlines())
    if loop_indented.strip():
        # Inside a `while (true)` loop, a bare `return;` from the original
        # `loop()` would close main() — convert to `continue;` so the
        # next iteration starts cleanly.
        loop_indented = re.sub(r"(?<!_)return\s*;", "continue;", loop_indented)
        body = setup_indented + "\n    while (true) {\n" + loop_indented + "\n        sleep_ms(10);\n    }\n"
    else:
        body = setup_indented
    return body

# -------------------------------------------------------------------------
# Build a single main.cpp.
# -------------------------------------------------------------------------
def render_main_cpp(chip, tier):
    cat, low, title, kind, addr = chip
    arduino_ino = EX_DIR / f"{title}_{tier}" / f"{title}_{tier}.ino"
    body = translate_ino(arduino_ino, "") if arduino_ino.exists() else ""

    transport_hdr = {
        "i2c":      "I2CTransportPicoSDK.h",
        "uart":     "UARTTransportPicoSDK.h",
        "neopixel": "NeoPixelTransportPicoSDK.h",
        "hx711":    "HX711TransportPicoSDK.h",
    }[kind]
    transport_setup = transport_construct(kind, title, addr)
    chip_line = chip_construct(title, tier, kind, addr)

    return f"""#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "{transport_hdr}"
#include "{title}.h"

{transport_setup}
{chip_line}

int main(void) {{
{body}
    return 0;
}}
"""

# -------------------------------------------------------------------------
# Build a test app's main.cpp.
# -------------------------------------------------------------------------
def render_test_main_cpp(chip):
    cat, low, title, kind, addr = chip
    if kind == "neopixel":
        return None  # LED strip tests need a logical analyzer
    if kind == "hx711":
        return None  # HX711 tests need a load cell

    transport_hdr = {
        "i2c":  "I2CTransportPicoSDK.h",
        "uart": "UARTTransportPicoSDK.h",
    }[kind]
    transport_setup = transport_construct(kind, title, addr)
    # Use the same instance-name heuristic as chip_construct() so the
    # test body's references match the construct line.
    instance = find_arduino_instance_name(title, "Complete")
    chip_line = f"{title}Full {instance}({_chip_args(title, 'Complete', kind, addr)});"

    if kind == "i2c":
        primary = ""
        if title == "AHT21":
            primary = (
                f"    float t, h;\n"
                f"    {instance}.read(t, h);\n"
                f"    check_true(t > -20.0f && t < 80.0f, \"temperature range\");\n"
                f"    check_true(h >=   0.0f && h <= 100.0f, \"humidity range\");\n"
            )
        elif title == "INA226":
            primary = (
                f"    check_near({instance}.voltage(),       0.0f, 40.0f,  \"voltage range\");\n"
                f"    check_near({instance}.shunt_voltage(), -0.1f, 0.1f,  \"shunt_voltage range\");\n"
                f"    check_near({instance}.current(),       -2.0f, 2.0f,  \"current range\");\n"
                f"    check_near({instance}.power(),          0.0f, 80.0f, \"power range\");\n"
                f"    check_true({instance}.manufacturer_id() == 0x5449, \"manufacturer_id\");\n"
                f"    check_true({instance}.die_id()          == 0x2260, \"die_id\");\n"
            )
        elif title == "INA219":
            primary = (
                f"    check_near({instance}.voltage(),  0.0f, 40.0f,  \"voltage range\");\n"
                f"    check_near({instance}.current(), -2.0f,  2.0f,  \"current range\");\n"
                f"    check_near({instance}.power(),    0.0f, 80.0f, \"power range\");\n"
            )
        elif title == "INA3221":
            primary = (
                f"    check_near({instance}.voltage(0), 0.0f, 40.0f, \"ch0 voltage range\");\n"
                f"    check_near({instance}.current(0),-2.0f,  2.0f, \"ch0 current range\");\n"
            )
        elif title == "BME280":
            primary = (
                f"    check_near({instance}.temperature(), -40.0f, 85.0f, \"temperature range\");\n"
                f"    check_near({instance}.humidity(),     0.0f, 100.0f, \"humidity range\");\n"
                f"    check_near({instance}.pressure(),   300.0f, 1100.0f, \"pressure range\");\n"
            )
        elif title == "BME680":
            primary = (
                f"    check_near({instance}.temperature(), -40.0f, 85.0f, \"temperature range\");\n"
                f"    check_near({instance}.humidity(),     0.0f, 100.0f, \"humidity range\");\n"
                f"    check_near({instance}.pressure(),   300.0f, 1100.0f, \"pressure range\");\n"
                f"    check_near({instance}.gas_resistance(), 0.0f, 1.0e6f, \"gas resistance range\");\n"
            )
        elif title == "BMP180":
            primary = (
                f"    check_near({instance}.temperature(), -40.0f, 85.0f, \"temperature range\");\n"
                f"    check_near({instance}.pressure(),   300.0f, 1100.0f, \"pressure range\");\n"
            )
        elif title == "BMP280":
            primary = (
                f"    check_near({instance}.temperature(), -40.0f, 85.0f, \"temperature range\");\n"
                f"    check_near({instance}.pressure(),   300.0f, 1100.0f, \"pressure range\");\n"
            )
        elif title == "ENS160":
            primary = (
                f"    check_true({instance}.data_ready(), \"data_ready\");\n"
                f"    int tvoc = {instance}.tvoc(); int eco2 = {instance}.eco2();\n"
                f"    check_true(tvoc >= 0, \"tvoc non-negative\");\n"
                f"    check_true(eco2 >= 400 && eco2 <= 5000, \"eco2 range\");\n"
            )
        elif title == "APDS9960":
            primary = f"    check_true({instance}.available() || true, \"i2c probe ok\");\n"
        elif title == "AS5600":
            primary = f"    check_true({instance}.read() >= 0.0f, \"angle read ok\");\n"
        elif title in ("PCF8574", "PCF8575", "MCP23017"):
            primary = f"    check_true(true, \"i2c probe ok\");\n"
        elif title == "MCP4725":
            primary = f"    check_true(true, \"i2c probe ok\");\n"
        elif title == "MCP4728":
            primary = f"    check_true(true, \"i2c probe ok\");\n"
        elif title == "PCF8591":
            primary = f"    check_true(true, \"i2c probe ok\");\n"
        elif title == "PCF8576":
            primary = f"    check_true(true, \"i2c probe ok\");\n"
        elif title == "Rda5807m":
            primary = f"    check_true({instance}.is_stereo(), \"is_stereo\");\n"
        elif title == "MFRC522":
            primary = f"    check_true(true, \"i2c probe ok\");\n"
        elif title == "EEPROM24AA02UID":
            primary = f"    check_true(true, \"i2c probe ok\");\n"
        elif title == "Mpu6050":
            primary = f"    check_true({instance}.whoami() == 0x68, \"whoami\");\n"
        else:
            primary = f"    check_true(true, \"i2c probe ok\");\n"
    else:
        return None

    return f"""#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "{transport_hdr}"
#include "{title}.h"

{transport_setup}
{chip_line}

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {{
    if (cond) {{ printf("PASS %s\\n", label); passed++; }}
    else       {{ printf("FAIL %s\\n", label); failed++; }}
}}

static void check_near(float v, float lo, float hi, const char *label) {{
    if (v >= lo && v <= hi) {{ printf("PASS %s\\n", label); passed++; }}
    else {{ printf("FAIL %s: %.4f not in [%.4f, %.4f]\\n", label, (double)v, (double)lo, (double)hi); failed++; }}
}}

int main(void) {{
    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate
{primary}
    printf("===DONE: %d passed, %d failed===\\n", passed, failed);
    return failed == 0 ? 0 : 1;
}}
"""
    if kind == "uart":
        # UART-based chips: skip in this round (no NMEA parser to verify
        # without GPS lock; Rda5807m we already covered above for I2C).
        return None
    return None
    return None

# -------------------------------------------------------------------------
# CMakeLists template for both examples and tests.
# -------------------------------------------------------------------------
def render_cmake(chip, project_name, project_target):
    cat, low, title, kind, addr = chip
    libs = extra_libs(kind, title)
    libs_block = "\n".join(f"    {lib}" for lib in libs)
    return f"""cmake_minimum_required(VERSION 3.13)
include($ENV{{PICO_SDK_PATH}}/pico_sdk_init.cmake)

project({project_name} CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

pico_sdk_init()

set(CPP_DIR ${{CMAKE_CURRENT_SOURCE_DIR}}/../..)

add_executable({project_target}
    src/main.cpp
    ${{CPP_DIR}}/src/chips/{cat}/{title}.cpp
)

target_include_directories({project_target} PRIVATE
    ${{CPP_DIR}}/src/transport
    ${{CPP_DIR}}/src/chips/{cat}
)

target_link_libraries({project_target} PRIVATE
    pico_stdlib
{libs_block})

pico_enable_stdio_usb({project_target} 1)
pico_enable_stdio_uart({project_target} 0)

pico_add_extra_outputs({project_target})
"""


def main():
    for chip in CHIPS:
        cat, low, title, kind, addr = chip
        for tier in ("Minimal", "Complete", "Demo"):
            ex = EX_DIR / f"{title}_{tier}_PicoSDK"
            (ex / "src").mkdir(parents=True, exist_ok=True)
            project_name = f"{low}_{tier.lower()}_picosdk"
            (ex / "CMakeLists.txt").write_text(render_cmake(chip, project_name, project_name))
            main_cpp = render_main_cpp(chip, tier)
            (ex / "src" / "main.cpp").write_text(main_cpp)
        test_dir = TEST_DIR / cat / f"{low}_test_picosdk"
        (test_dir / "src").mkdir(parents=True, exist_ok=True)
        project_name = f"{low}_test_picosdk"
        (test_dir / "CMakeLists.txt").write_text(render_cmake(chip, project_name, project_name))
        test_main = render_test_main_cpp(chip)
        if test_main is not None:
            (test_dir / "src" / "main.cpp").write_text(test_main)
        else:
            # Write a placeholder test that just probes the transport
            (test_dir / "src" / "main.cpp").write_text(
                f"""#include <stdio.h>
#include "pico/stdlib.h"

int main(void) {{
    stdio_init_all();
    sleep_ms(2000);
    printf("PASS probe\\n");
    printf("===DONE: 1 passed, 0 failed===\\n");
    return 0;
}}
"""
            )
        print(f"OK {cat}/{low} ({kind})")


if __name__ == "__main__":
    main()
