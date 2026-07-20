#!/usr/bin/env node
'use strict';

// Regenerates cpp/README.md from library.properties (name/sentence/paragraph/url)
// and the chip drivers actually shipped under cpp/src/chips, so the README that
// ends up on the arduino orphan branch (and in the Library Manager listing)
// always matches reality instead of drifting out of sync.
//
// Usage:
//   node cpp/scripts/generate-readme.js          # write cpp/README.md
//   node cpp/scripts/generate-readme.js --check  # exit 1 if README is stale

const fs = require('fs');
const path = require('path');

const CPP_DIR = path.join(__dirname, '..');
const CHIPS_DIR = path.join(CPP_DIR, 'src', 'chips');
const PROPERTIES_PATH = path.join(CPP_DIR, 'library.properties');
const README_PATH = path.join(CPP_DIR, 'README.md');

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

function parseProperties(text) {
    const props = {};
    for (const line of text.split('\n')) {
        const m = line.match(/^(\w+)=(.*)$/);
        if (m) props[m[1]] = m[2];
    }
    return props;
}

// Finds the nearest /** @brief ... */ block preceding `class <Chip>Minimal`
// and returns its first sentence, stripped of the boilerplate
// "— minimal interface." suffix used throughout the chip headers.
function describeChip(src) {
    const classIdx = src.search(/class\s+\w+Minimal\b/);
    if (classIdx === -1) return null;

    const before = src.slice(0, classIdx);
    const briefs = [...before.matchAll(/\/\*\*\s*@brief\s+([\s\S]*?)\*\//g)];
    if (briefs.length === 0) return null;

    const raw = briefs[briefs.length - 1][1];
    const firstLine = raw.split('\n')[0].replace(/\*/g, '').trim();
    return firstLine.replace(/\s*[—-]+\s*minimal interface\.?\s*$/i, '');
}

function collectChipRows() {
    const rows = [];
    for (const category of fs.readdirSync(CHIPS_DIR).sort()) {
        const categoryDir = path.join(CHIPS_DIR, category);
        if (!fs.statSync(categoryDir).isDirectory()) continue;
        const label = CATEGORY_LABELS[category] || category;
        for (const file of fs.readdirSync(categoryDir).sort()) {
            if (!file.endsWith('.h')) continue;
            const full = path.join(categoryDir, file);
            const src = fs.readFileSync(full, 'utf8');
            const description = describeChip(src);
            if (description === null) continue; // not a chip driver (e.g. NeoPixelColor.h)
            const chip = file.replace(/\.h$/, '');
            rows.push({ chip, category: label, header: `chips/${category}/${file}`, description });
        }
    }
    rows.sort((a, b) => a.chip.localeCompare(b.chip));
    return rows;
}

function buildChipTable(rows) {
    let table = `| Chip | Category | Header |\n|------|----------|--------|\n`;
    for (const r of rows) {
        table += `| ${r.chip} | ${r.category} | \`${r.header}\` |\n`;
    }
    return table.trimEnd();
}

function buildReadme(props, rows) {
    const { name, sentence, paragraph, url } = props;
    let body = `# ${name}\n\n`;
    body += `${sentence}\n\n`;
    body += `${paragraph}\n\n`;
    body += `## Install\n\n`;
    body += `Arduino IDE: **Sketch → Include Library → Manage Libraries…**, search for \`${name}\`.\n\n`;
    body += `Or manually: clone/download this repository into your \`libraries/${name}\` folder.\n\n`;
    body += `## Usage\n\n`;
    body += `\`\`\`cpp\n`;
    body += `#include <Wire.h>\n`;
    body += `#include "I2CTransport.h"\n`;
    body += `#include "INA219.h"\n\n`;
    body += `I2CTransport transport(Wire, 0x40);\n`;
    body += `INA219Minimal ina(transport);\n\n`;
    body += `void setup() {\n    Wire.begin();\n}\n\n`;
    body += `void loop() {\n    Serial.println(ina.power());  // watts\n    delay(1000);\n}\n`;
    body += `\`\`\`\n\n`;
    body += `Each chip exposes two classes:\n\n`;
    body += `- \`*Minimal\` — primary use case, works out of the box with sensible defaults\n`;
    body += `- \`*Full\` — complete chip functionality, extends Minimal\n\n`;
    body += `## Supported chips\n\n`;
    body += `${buildChipTable(rows)}\n\n`;
    body += `## Examples\n\n`;
    body += `Each chip ships three examples under \`examples/\`: \`<Chip>_Minimal\`, \`<Chip>_Complete\`, and \`<Chip>_Demo\`.\n\n`;
    body += `## Links\n\n`;
    body += `- [GitHub](${url})\n`;
    return body;
}

function main() {
    const checkOnly = process.argv.includes('--check');
    const props = parseProperties(fs.readFileSync(PROPERTIES_PATH, 'utf8'));
    const rows = collectChipRows();
    const readme = buildReadme(props, rows);
    const existing = fs.existsSync(README_PATH) ? fs.readFileSync(README_PATH, 'utf8') : null;

    if (existing === readme) return;

    if (checkOnly) {
        console.error(`stale or missing: ${path.relative(process.cwd(), README_PATH)}`);
        console.error('Run `node cpp/scripts/generate-readme.js` and commit the result.');
        process.exit(1);
    }

    fs.writeFileSync(README_PATH, readme);
    console.log(`wrote ${path.relative(process.cwd(), README_PATH)}`);
}

main();
