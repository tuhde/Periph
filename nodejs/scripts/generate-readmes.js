#!/usr/bin/env node
'use strict';

// Regenerates README.md for every node-red-contrib-periph-* package from the
// nodes it actually ships (parsed out of each node's .html editor file), and
// refreshes the "Supported chips" table in the core periph package's README
// from the chip driver modules it actually ships, so published npm READMEs
// always match reality instead of drifting out of sync or falling back to a
// generic placeholder.
//
// Usage:
//   node nodejs/scripts/generate-readmes.js          # write READMEs
//   node nodejs/scripts/generate-readmes.js --check   # exit 1 if any README is stale

const fs = require('fs');
const path = require('path');

const PACKAGES_DIR = path.join(__dirname, '..', 'packages');
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

function decodeEntities(s) {
    return s
        .replace(/&amp;/g, '&')
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'");
}

function stripTags(html) {
    return decodeEntities(html.replace(/<[^>]+>/g, ' ')).replace(/\s+/g, ' ').trim();
}

function parseRegistrations(html) {
    const registrations = [];
    const re = /RED\.nodes\.registerType\(\s*'([^']+)'\s*,\s*\{([\s\S]*?)\n\s*\}\);/g;
    let m;
    while ((m = re.exec(html))) {
        const [, id, body] = m;
        const category = (body.match(/category:\s*'([^']+)'/) || [])[1] || null;
        const inputs = (body.match(/inputs:\s*(\d+)/) || [])[1];
        const outputs = (body.match(/outputs:\s*(\d+)/) || [])[1];
        const hasAddress = /\baddress:\s*\{/.test(body);
        const hasSpiBus = /\bspiBus:\s*\{/.test(body);
        registrations.push({
            id,
            category,
            inputs: inputs === undefined ? null : parseInt(inputs, 10),
            outputs: outputs === undefined ? null : parseInt(outputs, 10),
            hasAddress,
            hasSpiBus,
        });
    }
    return registrations;
}

function parseHelpText(html) {
    const help = {};
    const re = /data-help-name="([^"]+)"\s*>([\s\S]*?)<\/script>/g;
    let m;
    while ((m = re.exec(html))) {
        const [, id, body] = m;
        const firstP = body.match(/<p>([\s\S]*?)<\/p>/);
        help[id] = firstP ? stripTags(firstP[1]) : '';
    }
    return help;
}

function chipNameFromDeviceId(id) {
    return id.replace(/^periph-/, '').replace(/-device$/, '').toUpperCase();
}

function article(word) {
    return /^[AEIOU]/.test(word) ? 'an' : 'a';
}

function describeConfigNode(reg) {
    const chip = chipNameFromDeviceId(reg.id);
    const a = article(chip);
    if (reg.hasAddress) return `I²C bus and address for ${a} ${chip}`;
    if (reg.hasSpiBus) return `SPI bus and device index for ${a} ${chip}`;
    return `Bus configuration for ${a} ${chip}`;
}

function kindOf(reg) {
    if (reg.category === 'config') return 'config';
    if (reg.outputs === 0) return 'output';
    return 'input';
}

function collectNodeRows(pkgDir) {
    const nodesDir = path.join(pkgDir, 'nodes');
    if (!fs.existsSync(nodesDir)) return [];

    const rows = [];
    for (const chip of fs.readdirSync(nodesDir).sort()) {
        const htmlFile = path.join(nodesDir, chip, chip + '.html');
        if (!fs.existsSync(htmlFile)) continue;
        const html = fs.readFileSync(htmlFile, 'utf8');
        const help = parseHelpText(html);
        for (const reg of parseRegistrations(html)) {
            const kind = kindOf(reg);
            const description = kind === 'config' ? describeConfigNode(reg) : (help[reg.id] || '');
            rows.push({ node: reg.id, kind, description });
        }
    }
    return rows;
}

function buildReadme(pkg, rows) {
    const { name, description } = pkg;
    let body = `# ${name}\n\n`;
    body += `${description} — part of the [Periph](${REPO_URL}) library.\n\n`;
    body += `## Install\n\n`;
    body += `Open Node-RED, go to **Manage Palette → Install** and search for \`${name}\`.\n\n`;
    body += `Or from the command line in your Node-RED user directory:\n\n`;
    body += `\`\`\`sh\nnpm install ${name}\n\`\`\`\n\n`;
    body += `## Nodes\n\n`;
    if (rows.length === 0) {
        body += `> **Coming soon.** Nodes will be added as chips in this category are implemented.\n\n`;
    } else {
        body += `| Node | Kind | Description |\n|------|------|-------------|\n`;
        for (const r of rows) {
            body += `| \`${r.node}\` | ${r.kind} | ${r.description} |\n`;
        }
        body += `\n`;
    }
    body += `## Links\n\n`;
    body += `- [GitHub](${REPO_URL})\n`;
    body += `- [periph JS driver](https://www.npmjs.com/package/periph)\n`;
    return body;
}

// A chip driver module exports at least one `*Minimal` class; internal
// helpers (e.g. led/_color.js) don't and are excluded.
function isChipDriverFile(file) {
    const src = fs.readFileSync(file, 'utf8');
    return /module\.exports\s*=\s*\{[^}]*\w+Minimal\b/.test(src);
}

function collectPeriphChipRows(periphDir) {
    const chipsDir = path.join(periphDir, 'src', 'chips');
    const rows = [];
    for (const category of fs.readdirSync(chipsDir).sort()) {
        const categoryDir = path.join(chipsDir, category);
        if (!fs.statSync(categoryDir).isDirectory()) continue;
        const label = CATEGORY_LABELS[category] || category;
        for (const file of fs.readdirSync(categoryDir).sort()) {
            if (!file.endsWith('.js')) continue;
            const full = path.join(categoryDir, file);
            if (!isChipDriverFile(full)) continue;
            const base = file.replace(/\.js$/, '');
            const chip = base.replace(/^_/, '').toUpperCase();
            rows.push({ chip, category: label, requirePath: `periph/src/chips/${category}/${base}` });
        }
    }
    rows.sort((a, b) => a.chip.localeCompare(b.chip));
    return rows;
}

function buildChipTable(rows) {
    let table = `| Chip | Category | Require path |\n|------|----------|-------------|\n`;
    for (const r of rows) {
        table += `| ${r.chip} | ${r.category} | \`${r.requirePath}\` |\n`;
    }
    return table.trimEnd();
}

function updatePeriphReadme(periphDir, checkOnly) {
    const readmePath = path.join(periphDir, 'README.md');
    const existing = fs.readFileSync(readmePath, 'utf8');
    const rows = collectPeriphChipRows(periphDir);
    const table = buildChipTable(rows);

    const sectionRe = /(## Supported chips\n\n)(\|[\s\S]*?)\n\n(?=##\s)/;
    const match = existing.match(sectionRe);
    if (!match) {
        console.error(`could not find "## Supported chips" table in ${readmePath}`);
        process.exit(1);
    }
    if (match[2] === table) return false;

    const updated = existing.replace(sectionRe, `$1${table}\n\n`);
    if (checkOnly) {
        console.error(`stale: ${path.relative(process.cwd(), readmePath)}`);
        return true;
    }
    fs.writeFileSync(readmePath, updated);
    console.log(`wrote ${path.relative(process.cwd(), readmePath)}`);
    return false;
}

function main() {
    const checkOnly = process.argv.includes('--check');
    const pkgDirs = fs
        .readdirSync(PACKAGES_DIR)
        .filter((d) => d.startsWith('node-red-contrib-periph-'))
        .map((d) => path.join(PACKAGES_DIR, d));

    let drift = false;
    for (const dir of pkgDirs) {
        const pkg = JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8'));
        const rows = collectNodeRows(dir);
        const readme = buildReadme(pkg, rows);
        const readmePath = path.join(dir, 'README.md');
        const existing = fs.existsSync(readmePath) ? fs.readFileSync(readmePath, 'utf8') : null;

        if (existing === readme) continue;

        if (checkOnly) {
            console.error(`stale or missing: ${path.relative(process.cwd(), readmePath)}`);
            drift = true;
        } else {
            fs.writeFileSync(readmePath, readme);
            console.log(`wrote ${path.relative(process.cwd(), readmePath)}`);
        }
    }

    drift = updatePeriphReadme(path.join(PACKAGES_DIR, 'periph'), checkOnly) || drift;

    if (checkOnly && drift) {
        console.error('\nREADMEs are out of date. Run `node nodejs/scripts/generate-readmes.js` and commit the result.');
        process.exit(1);
    }
}

main();
