#!/usr/bin/env node
'use strict';

// Regenerates rust/periph/README.md -- the file crates.io renders as the
// crate's package page -- from rust/periph/Cargo.toml (name/version/
// description/repository) and the chip driver modules actually shipped
// under rust/periph/src/chips, so the README on crates.io always matches
// reality instead of drifting out of sync (it had gone stale enough to list
// 12 of the 52 shipped chips before this script existed).
//
// A source file counts as a chip driver only if its normalized filename
// matches a spec under specs/<category>/*.md -- this is what excludes
// mod.rs, shared base modules (neopixel_rgb_base.rs, color.rs, ...), and
// anything else that isn't an actual chip.
//
// Usage:
//   node rust/scripts/generate-readme.js          # write rust/periph/README.md
//   node rust/scripts/generate-readme.js --check  # exit 1 if README is stale

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const SPECS_DIR = path.join(ROOT, 'specs');
const CHIPS_DIR = path.join(ROOT, 'rust', 'periph', 'src', 'chips');
const CARGO_TOML_PATH = path.join(ROOT, 'rust', 'periph', 'Cargo.toml');
const README_PATH = path.join(ROOT, 'rust', 'periph', 'README.md');

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

function parseCargoField(text, field) {
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

// First //! doc-comment line, with the leading "<Name> — " or "<Name> "
// restated-name prefix stripped so it reads naturally in a table cell.
function describeChip(src, display) {
    const lines = src.split('\n');
    const docLines = [];
    for (const line of lines) {
        const m = line.match(/^\s*\/\/!\s?(.*)$/);
        if (!m) break;
        docLines.push(m[1]);
    }
    if (docLines.length === 0 || docLines[0].trim() === '') return null;

    let first = docLines[0].trim();
    const dashMatch = first.match(/^\S.*?\s[—–-]\s(.+)$/);
    if (dashMatch) {
        first = dashMatch[1];
    } else {
        const nameRe = new RegExp(`^${display.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s+`, 'i');
        first = first.replace(nameRe, '');
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
            if (!file.endsWith('.rs')) continue;
            const stem = file.replace(/\.rs$/, '');
            const entry = registry.get(norm(stem));
            if (!entry) continue; // not a spec'd chip (mod.rs, base modules, ...)
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
    const { version, description, repository } = pkg;
    let body = `# periph\n\n`;
    body += `${description}\n\n`;
    body += `- **\`no_std\` compatible** — runs on bare-metal targets (ESP32-S3, STM32, …) and Linux\n`;
    body += `- **Generic over [\`embedded-hal\`](https://crates.io/crates/embedded-hal) 1.0** — bring your own connection\n`;
    body += `- **Two-tier API** — \`*Minimal\` for the primary use case, \`*Full\` for complete chip functionality\n\n`;
    body += `## Install\n\n`;
    body += `\`\`\`sh\n`;
    body += `cargo add periph\n`;
    body += `\`\`\`\n\n`;
    body += `Or in \`Cargo.toml\`:\n\n`;
    body += `\`\`\`toml\n`;
    body += `[dependencies]\n`;
    body += `periph = "${version}"\n`;
    body += `\`\`\`\n\n`;
    body += `## Example\n\n`;
    body += `\`\`\`rust\n`;
    body += `use linux_embedded_hal::I2cdev;\n`;
    body += `use periph::chips::power::Ina226Minimal;\n\n`;
    body += `fn main() {\n`;
    body += `    let i2c = I2cdev::new("/dev/i2c-1").unwrap();\n`;
    body += `    // addr=0x40, 0.1 Ω shunt, 2.0 A max expected current\n`;
    body += `    let mut sensor = Ina226Minimal::new(i2c, 0x40, 0.1, 2.0).unwrap();\n`;
    body += `    println!("{:.3} W", sensor.power().unwrap());\n`;
    body += `}\n`;
    body += `\`\`\`\n\n`;
    body += `Each chip exposes two structs:\n\n`;
    body += `- \`*Minimal\` — primary use case, works out of the box with sensible defaults\n`;
    body += `- \`*Full\` — complete chip functionality, extends Minimal\n\n`;
    body += `## Supported chips\n\n`;
    body += `${buildChipTable(rows)}\n\n`;
    body += `## Links\n\n`;
    body += `- [GitHub](${repository})\n`;
    body += `- [Docs](https://docs.rs/periph)\n`;
    return body;
}

function main() {
    const checkOnly = process.argv.includes('--check');
    const cargoText = fs.readFileSync(CARGO_TOML_PATH, 'utf8');
    const pkg = {
        version: parseCargoField(cargoText, 'version'),
        description: parseCargoField(cargoText, 'description'),
        repository: parseCargoField(cargoText, 'repository'),
    };
    const registry = buildChipRegistry();
    const rows = collectChipRows(registry);
    const readme = buildReadme(pkg, rows);
    const existing = fs.existsSync(README_PATH) ? fs.readFileSync(README_PATH, 'utf8') : null;

    if (existing === readme) return;

    if (checkOnly) {
        console.error(`stale or missing: ${path.relative(process.cwd(), README_PATH)}`);
        console.error('Run `node rust/scripts/generate-readme.js` and commit the result.');
        process.exit(1);
    }

    fs.writeFileSync(README_PATH, readme);
    console.log(`wrote ${path.relative(process.cwd(), README_PATH)} (${rows.length} chips)`);
}

main();
