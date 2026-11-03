'use strict';

module.exports = function(RED) {
    const { Default }           = require('opengpio');
    const { DisplayConnection } = require('periph/src/connection/display');
    const { GpioOutputPin }     = require('periph/src/connection/output_pin');
    const S = require('periph/src/chips/display/ssd1331');

    const COLORS = {
        black: S.BLACK, white: S.WHITE, red: S.RED, green: S.GREEN, blue: S.BLUE,
        yellow: S.YELLOW, cyan: S.CYAN, magenta: S.MAGENTA,
    };
    const MODES = { normal: S.MODE_NORMAL, on: S.MODE_ALL_ON, off: S.MODE_ALL_OFF, inverse: S.MODE_INVERSE };

    // A colour is a name, an RGB565 number, or [r, g, b] with 0..255 components.
    function color(c, fallback = S.WHITE) {
        if (c === undefined || c === null) return fallback;
        if (typeof c === 'number') return c;
        if (Array.isArray(c)) return S.rgb(c[0], c[1], c[2]);
        const v = COLORS[String(c).toLowerCase()];
        if (v === undefined) throw new Error(`unknown colour "${c}" (name, RGB565 number or [r, g, b])`);
        return v;
    }

    function SSD1331Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.autoShow = !!config.autoShow;

        try {
            const chip = parseInt(config.gpioChip || 0, 10);
            const dc = new GpioOutputPin(Default.output({ chip, line: parseInt(config.dcPin, 10) }));
            const res = new GpioOutputPin(Default.output({ chip, line: parseInt(config.resPin, 10) }));
            node.connection = new DisplayConnection({
                busNumber: parseInt(config.spiBus || 0, 10),
                deviceNumber: parseInt(config.spiDevice || 0, 10),
                dc, res,
            });
            node.driver = new S.SSD1331Full(node.connection);
            node.driver.ready().catch((e) => node.error('SSD1331 init failed: ' + e.message));
        } catch (e) {
            node.error('SSD1331 init failed: ' + e.message);
        }

        // Messages are handled one at a time: two overlapping command streams would corrupt each other.
        let queue = Promise.resolve();
        node.on('input', function(msg, send, done) {
            queue = queue.then(() => handle(msg, send, done));
        });

        async function handle(msg, send, done) {
            if (!node.driver) { done(); return; }
            const d = node.driver;
            const p = msg.payload || {};
            try {
                if (p.clear !== undefined) d.clear(color(p.clear, S.BLACK));
                if (p.pixel) d.setPixel(p.pixel.x, p.pixel.y, color(p.pixel.color));
                if (p.line) {
                    const l = p.line;
                    d.drawLine(l.x0, l.y0, l.x1, l.y1, color(l.color));
                }
                if (p.rect) {
                    const r = p.rect;
                    d.drawRect(r.x, r.y, r.w, r.h, color(r.color), !!r.fill);
                }
                if (p.bitmap) {
                    const b = p.bitmap;
                    d.blit(b.x, b.y, b.data, b.w, b.h);
                }
                if (p.show || (node.autoShow && (p.clear !== undefined || p.pixel || p.line || p.rect || p.bitmap))) {
                    await d.show();
                    msg.payload = { updated: true };
                    send(msg);
                }
                if (p.hwLine) {
                    const l = p.hwLine;
                    await d.hwLine(l.x0, l.y0, l.x1, l.y1, color(l.color));
                }
                if (p.hwRect) {
                    const r = p.hwRect;
                    await d.hwRect(r.x, r.y, r.w, r.h, color(r.color), color(r.fillColor, S.BLACK), !!r.fill);
                }
                if (p.scroll) {
                    const s = p.scroll;
                    await d.scroll(s.horizontal || 0, s.startRow || 0, s.rows ?? 64, s.vertical || 0, s.interval || 0);
                }
                if (p.scrollStop) await d.scrollStop();
                if (p.contrast) await d.setContrast(p.contrast[0], p.contrast[1], p.contrast[2]);
                if (p.mode !== undefined) {
                    const m = MODES[p.mode];
                    if (m === undefined) throw new Error('mode must be "normal", "on", "off" or "inverse"');
                    await d.setDisplayMode(m);
                }
                if (p.on !== undefined) await (p.on ? d.displayOn() : d.displayOff());
                done();
            } catch (e) {
                node.status({ fill: 'red', shape: 'ring', text: e.message });
                done(e);
            }
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-ssd1331', SSD1331Node);
};
