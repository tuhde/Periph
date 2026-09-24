#!/usr/bin/env node
'use strict';

// Regenerates cpp/keywords.txt (Arduino IDE syntax highlighting) from the
// public API declared in the Arduino-facing headers: chip drivers under
// cpp/src/chips plus the platform-neutral / Arduino connection headers.
//
//   KEYWORD1  public classes, structs and enums (types)
//   KEYWORD2  public methods
//   LITERAL1  public static constexpr constants and enumerators
//
// Protected/private members, underscore-prefixed internals, constructors and
// Linux/Zephyr/ESP-IDF/Pico SDK/mock connection headers are skipped.
//
// Usage:
//   node cpp/scripts/generate-keywords.js          # write cpp/keywords.txt
//   node cpp/scripts/generate-keywords.js --check  # exit 1 if keywords.txt is stale

const fs = require('fs');
const path = require('path');

const CPP_DIR = path.join(__dirname, '..');
const SRC_DIR = path.join(CPP_DIR, 'src');
const KEYWORDS_PATH = path.join(CPP_DIR, 'keywords.txt');

// Connection headers for other platforms never reach an Arduino sketch.
const NON_ARDUINO_HEADER = /(Linux|Zephyr|ESPIDF|PicoSDK|Mock)\.h$/;

const KIND_PRIORITY = { KEYWORD1: 3, LITERAL1: 2, KEYWORD2: 1 };

function listHeaders(dir) {
    const out = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) out.push(...listHeaders(full));
        else if (entry.name.endsWith('.h') && !NON_ARDUINO_HEADER.test(entry.name)) out.push(full);
    }
    return out.sort();
}

// Removes comments, string/char literals and preprocessor lines so the
// brace/statement walker only ever sees declarations.
function stripNoise(src) {
    return src
        .replace(/\/\*[\s\S]*?\*\//g, ' ')
        .replace(/\/\/.*$/gm, '')
        .replace(/"(?:\\.|[^"\\\n])*"/g, '""')
        .replace(/'(?:\\.|[^'\\\n])*'/g, "''")
        .replace(/^\s*#.*(?:\\\n.*)*$/gm, '');
}

function matchingBrace(text, open) {
    let depth = 0;
    for (let i = open; i < text.length; i++) {
        if (text[i] === '{') depth++;
        else if (text[i] === '}' && --depth === 0) return i;
    }
    return text.length - 1;
}

function isInternal(name) {
    return name.startsWith('_');
}

// Walks one scope (file, namespace, class or struct body) statement by
// statement, tracking access specifiers and recursing into nested types.
function parseScope(body, access, className, add) {
    let stmt = '';
    const flush = () => {
        handleDeclaration(stmt, access, className, add);
        stmt = '';
    };

    for (let i = 0; i < body.length; i++) {
        const c = body[i];
        if (c === ';') {
            flush();
        } else if (c === '{') {
            const close = matchingBrace(body, i);
            const inner = body.slice(i + 1, close);
            const head = stmt.trim();
            i = close;

            const typeMatch = head.match(/(?:^|\s)(class|struct|namespace)\s+(\w+)(?:\s+final)?\s*(?::[^{]*)?$/);
            const enumMatch = head.match(/(?:^|\s)enum(?:\s+(?:class|struct))?\s+(\w+)\s*(?::\s*[\w:\s]+)?$/);

            if (typeMatch) {
                const [, keyword, name] = typeMatch;
                if (keyword === 'namespace') {
                    parseScope(inner, 'public', null, add);
                } else if (access === 'public') {
                    // Internal bases (e.g. _RFM9xBase) are not advertised as
                    // types, but their public methods are inherited.
                    if (!isInternal(name)) add(name, 'KEYWORD1');
                    parseScope(inner, keyword === 'struct' ? 'public' : 'private', name, add);
                }
                stmt = '';
            } else if (enumMatch) {
                if (access === 'public' && !isInternal(enumMatch[1])) {
                    add(enumMatch[1], 'KEYWORD1');
                    for (const item of inner.split(',')) {
                        const m = item.trim().match(/^(\w+)/);
                        if (m && !isInternal(m[1])) add(m[1], 'LITERAL1');
                    }
                }
                stmt = '';
            } else if (head === '' || /\)[\w\s&]*$/.test(head) || /\)\s*:/.test(head)) {
                // Inline function body (or constructor initializer list).
                handleDeclaration(stmt, access, className, add);
                stmt = '';
            } else {
                // Brace initializer, e.g. `uint8_t x {0};` — keep reading to `;`.
                stmt += '{}';
            }
        } else {
            stmt += c;
            const label = stmt.trim().match(/^(public|protected|private)\s*:$/);
            if (label) {
                access = label[1];
                stmt = '';
            }
        }
    }
    flush();
}

function handleDeclaration(stmt, access, className, add) {
    const s = stmt.replace(/\s+/g, ' ').trim();
    if (!s || access !== 'public') return;
    if (/^(typedef|friend|static_assert)\b/.test(s)) return;

    const using = s.match(/^using [\w:]*::(\w+)$/);
    if (using) {
        if (!isInternal(using[1])) add(using[1], 'KEYWORD2');
        return;
    }
    if (/^using\b/.test(s)) return;

    const constant = s.match(/\bstatic (?:inline )?const(?:expr)?\b[^=({]*?\b(\w+)\s*(?:\[[^\]]*\])?\s*(?:=|\{\}|$)/);
    if (constant) {
        if (!isInternal(constant[1])) add(constant[1], 'LITERAL1');
        return;
    }
    if (/^(?:static )?constexpr\b/.test(s) && className === null) {
        const m = s.match(/\b(\w+)\s*=/);
        if (m && !isInternal(m[1])) add(m[1], 'LITERAL1');
        return;
    }

    const fn = s.match(/(~?\b\w+)\s*\(/);
    if (!fn) return;
    const name = fn[1];
    if (name.startsWith('~') || name === className || name === 'operator') return;
    if (/\boperator\b/.test(s.slice(0, fn.index + name.length))) return;
    if (/^(if|for|while|switch|return|sizeof|decltype|alignas)$/.test(name)) return;
    if (isInternal(name)) return;
    add(name, 'KEYWORD2');
}

function collectKeywords() {
    const keywords = new Map();
    const add = (name, kind) => {
        const prev = keywords.get(name);
        if (!prev || KIND_PRIORITY[kind] > KIND_PRIORITY[prev]) keywords.set(name, kind);
    };
    for (const header of listHeaders(SRC_DIR)) {
        parseScope(stripNoise(fs.readFileSync(header, 'utf8')), 'public', null, add);
    }
    return keywords;
}

function buildKeywordsFile(keywords) {
    const sections = [
        ['KEYWORD1', 'Datatypes (KEYWORD1)'],
        ['KEYWORD2', 'Methods and Functions (KEYWORD2)'],
        ['LITERAL1', 'Constants (LITERAL1)'],
    ];
    let out = '#######################################\n';
    out += '# Syntax Coloring Map For Periph\n';
    out += '# Generated by cpp/scripts/generate-keywords.js - do not edit by hand.\n';
    out += '#######################################\n';
    for (const [kind, title] of sections) {
        const names = [...keywords].filter(([, k]) => k === kind).map(([n]) => n).sort();
        out += `\n#######################################\n# ${title}\n#######################################\n\n`;
        for (const name of names) out += `${name}\t${kind}\n`;
    }
    return out;
}

function main() {
    const checkOnly = process.argv.includes('--check');
    const content = buildKeywordsFile(collectKeywords());
    const existing = fs.existsSync(KEYWORDS_PATH) ? fs.readFileSync(KEYWORDS_PATH, 'utf8') : null;

    if (existing === content) return;

    if (checkOnly) {
        console.error(`stale or missing: ${path.relative(process.cwd(), KEYWORDS_PATH)}`);
        console.error('Run `node cpp/scripts/generate-keywords.js` and commit the result.');
        process.exit(1);
    }

    fs.writeFileSync(KEYWORDS_PATH, content);
    console.log(`wrote ${path.relative(process.cwd(), KEYWORDS_PATH)}`);
}

main();
