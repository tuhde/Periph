#!/usr/bin/env node
'use strict';

// Regenerates python/README.md -- the file PyPI renders as the project
// description on the package page -- from python/pyproject.toml (name/
// version/description) and the chip driver modules actually shipped under
// python/periph/chips, so the description on PyPI always matches reality.
// python/pyproject.toml previously had no `readme` field at all, so PyPI
// showed no project description whatsoever.
//
// A source file counts as a chip driver only if its normalized filename
// matches a spec under specs/<category>/*.md -- this is what excludes the
// shared _neopixel_rgb_base.py / _neopixel_rgbw_base.py base modules.
//
// Usage:
//   node python/scripts/generate-readme.js          # write python/README.md
//   node python/scripts/generate-readme.js --check  # exit 1 if README is stale

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const SPECS_DIR = path.join(ROOT, 'specs');
const CHIPS_DIR = path.join(ROOT, 'python', 'periph', 'chips');
const PYPROJECT_PATH = path.join(ROOT, 'python', 'pyproject.toml');
const README_PATH = path.join(ROOT, 'python', 'README.md');
const REPO_URL = 'https://github.com/tuhde/Periph';

// Mirrors the category table in the repo's CLAUDE.md.
const CATEGORY_LABELS = {
    accelerometer: 'Accelerometer',
    adc_dac: 'ADC/DAC',
    color: 'Color sensor',
    comms: 'Comms',
    display: 'Display driver',
    environmental: 'Environmental sensor',
    gas: 'Gas sensor',
    gnss: 'GNSS/GPS',
    gpio: 'GPIO expander',
    gyroscope: 'Gyroscope',
    humidity: 'Humidity sensor',
    imu: 'IMU',
    io_expander: 'IO expander',
    led: 'LED driver',
    light: 'Light sensor',
    magnetometer: 'Magnetometer',
    memory: 'Memory',
    motor: 'Motor driver',
    other: 'Other',
    power: 'Power monitor',
    pressure: 'Pressure sensor',
    rfid: 'RFID/NFC',
    rtc: 'RTC',
    temperature: 'Temperature sensor',
    tof: 'Time-of-flight',
};

// A few spec stems don't title-case cleanly from the filename alone.
const DISPLAY_OVERRIDES = {
    mpu6050: 'MPU-6050',
    mpu9250: 'MPU-9250',
    mpu9255: 'MPU-9255',
    rfm9x: 'RFM9x',
};

function norm(s) {
    return s.replace(/[-_]/g, '').toLowerCase();
}

function parsePyprojectField(text, field) {
    const m = text.match(new RegExp(`^${field}\\s*=\\s*"([^"]*)"`, 'm'));
    return m ? m[1] : null;
}

// specs/<category>/<chip>.md (skipping _template*/_*) is the source of
// truth for "what is a real, spec'd chip" -- reused here so this script
// can't accidentally pick up a shared base module as if it were a chip.
function buildChipRegistry() {
    const registry = new Map(); // norm(stem) -> { category, display }
    for (const category of fs.readdirSync(SPECS_DIR).sort()) {
        const categoryDir = path.join(SPECS_DIR, category);
        if (!fs.statSync(categoryDir).isDirectory()) continue;
        for (const file of fs.readdirSync(categoryDir).sort()) {
            if (!file.endsWith('.md') || file.startsWith('_')) continue;
            const stem = file.replace(/\.md$/, '');
            const display = DISPLAY_OVERRIDES[stem.toLowerCase()] || stem.toUpperCase();
            registry.set(norm(stem), { category, display });
        }
    }
    return registry;
}

function escapeRe(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

// Finds the nearest docstring following `class <Chip>Minimal:` and returns
// its first line as a plain description, with the chip's own name and the
// "minimal interface"/"minimal driver" boilerplate stripped out -- neither
// is consistently placed (some docstrings lead with it, some trail with it,
// some skip it entirely), so this tries a few shapes rather than one fixed
// pattern.
function describeChip(src, display) {
    const classMatch = src.match(/class\s+\w+Minimal\b[^:]*:/);
    if (!classMatch) return null;

    const after = src.slice(classMatch.index + classMatch[0].length);
    const docMatch = after.match(/^\s*"""(.*?)(?:\n|""")/);
    if (!docMatch) return null;

    let first = docMatch[1].trim();

    // "<Chip> minimal interface|driver — <description>."
    const leadMatch = first.match(/^\S+\s+minimal (?:interface|driver)\s*[—-]\s*(.+)$/i);
    if (leadMatch) {
        first = leadMatch[1].trim();
    } else {
        // "<description> — minimal interface|driver." (trailing, if present)
        first = first.replace(/\s*[—-]+\s*minimal (?:interface|driver)\.?\s*$/i, '').trim();
        // restated chip name at the very start (if present)
        first = first.replace(new RegExp(`^${escapeRe(display)}\\s+`, 'i'), '');
    }
    first = first.trim();
    return first.charAt(0).toUpperCase() + first.slice(1);
}

function collectChipRows(registry) {
    const rows = [];
    for (const category of fs.readdirSync(CHIPS_DIR).sort()) {
        const categoryDir = path.join(CHIPS_DIR, category);
        if (!fs.statSync(categoryDir).isDirectory()) continue;
        for (const file of fs.readdirSync(categoryDir).sort()) {
            if (!file.endsWith('.py')) continue;
            const stem = file.replace(/\.py$/, '');
            const entry = registry.get(norm(stem));
            if (!entry) continue; // not a spec'd chip (base modules, ...)
            const src = fs.readFileSync(path.join(categoryDir, file), 'utf8');
            const description = describeChip(src, entry.display) || '';
            rows.push({
                chip: entry.display,
                category: CATEGORY_LABELS[entry.category] || entry.category,
                description,
            });
        }
    }
    rows.sort((a, b) => a.chip.localeCompare(b.chip));
    return rows;
}

function buildChipTable(rows) {
    let table = `| Chip | Category | Description |\n|------|----------|-------------|\n`;
    for (const r of rows) {
        table += `| ${r.chip} | ${r.category} | ${r.description} |\n`;
    }
    return table.trimEnd();
}

function buildReadme(pkg, rows) {
    const { description } = pkg;
    let body = `# periph\n\n`;
    body += `${description}\n\n`;
    body += `- **Three targets** — MicroPython, CircuitPython, and Linux (\`/dev/i2c-N\` via \`smbus2\`)\n`;
    body += `- **Same driver code everywhere** — only the connection class changes per platform\n`;
    body += `- **Two-tier API** — \`*Minimal\` for the primary use case, \`*Full\` for complete chip functionality\n\n`;
    body += `## Install\n\n`;
    body += `\`\`\`sh\n`;
    body += `pip install periph[linux]   # Linux host: also installs smbus2, spidev, gpiod, pyserial\n`;
    body += `\`\`\`\n\n`;
    body += `On MicroPython/CircuitPython, copy the \`periph/\` package onto the device's filesystem (or freeze it into the firmware) instead.\n\n`;
    body += `## Example\n\n`;
    body += `\`\`\`python\n`;
    body += `from periph.connection.i2c_auto import I2CConnection\n`;
    body += `from periph.chips.power.ina226 import INA226Minimal\n`;
    body += `import time\n\n`;
    body += `connection = I2CConnection(0x40)\n`;
    body += `ina = INA226Minimal(connection)\n\n`;
    body += `while True:\n`;
    body += `    print(ina.voltage(), ina.current(), ina.power())\n`;
    body += `    time.sleep(1)\n`;
    body += `\`\`\`\n\n`;
    body += `\`I2CConnection\` above resolves to the right platform implementation automatically (MicroPython \`machine.I2C\`, CircuitPython \`busio.I2C\`, or Linux \`/dev/i2c-N\`).\n\n`;
    body += `Each chip exposes two classes:\n\n`;
    body += `- \`*Minimal\` — primary use case, works out of the box with sensible defaults\n`;
    body += `- \`*Full\` — complete chip functionality, extends Minimal\n\n`;
    body += `## Supported chips\n\n`;
    body += `${buildChipTable(rows)}\n\n`;
    body += `## Links\n\n`;
    body += `- [GitHub](${REPO_URL})\n`;
    return body;
}

function main() {
    const checkOnly = process.argv.includes('--check');
    const pyprojectText = fs.readFileSync(PYPROJECT_PATH, 'utf8');
    const pkg = {
        description: parsePyprojectField(pyprojectText, 'description'),
    };
    const registry = buildChipRegistry();
    const rows = collectChipRows(registry);
    const readme = buildReadme(pkg, rows);
    const existing = fs.existsSync(README_PATH) ? fs.readFileSync(README_PATH, 'utf8') : null;

    if (existing === readme) return;

    if (checkOnly) {
        console.error(`stale or missing: ${path.relative(process.cwd(), README_PATH)}`);
        console.error('Run `node python/scripts/generate-readme.js` and commit the result.');
        process.exit(1);
    }

    fs.writeFileSync(README_PATH, readme);
    console.log(`wrote ${path.relative(process.cwd(), README_PATH)} (${rows.length} chips)`);
}

main();
