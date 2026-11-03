'use strict';

module.exports = function(RED) {
    const { Default }           = require('opengpio');
    const { DisplayConnection, WireMode } = require('periph/src/connection/display');
    const { GpioOutputPin }     = require('periph/src/connection/output_pin');
    const { PollingInputPin }   = require('periph/src/connection/input_pin');
    const { IL0373Full, BLACK, WHITE } = require('periph/src/chips/display/il0373');

    const COLORS = { black: BLACK, white: WHITE };
    const WIRE_MODES = { simplex: WireMode.SIMPLEX, pin: WireMode.HALF_DUPLEX_PIN };

    function color(name, fallback = BLACK) {
        if (name === undefined || name === null) return fallback;
        if (typeof name === 'number') return name;
        const c = COLORS[String(name).toLowerCase()];
        if (c === undefined) throw new Error(`unknown colour "${name}" (black or white)`);
        return c;
    }

    function IL0373Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.autoShow = !!config.autoShow;

        try {
            const chip = parseInt(config.gpioChip || 0, 10);
            const dc = new GpioOutputPin(Default.output({ chip, line: parseInt(config.dcPin, 10) }));
            const res = new GpioOutputPin(Default.output({ chip, line: parseInt(config.resPin, 10) }));
            const busyIn = Default.input({ chip, line: parseInt(config.busyPin, 10) });
            const busy = new PollingInputPin(() => (busyIn.value ? 1 : 0), 1000);
            node.connection = new DisplayConnection({
                busNumber: parseInt(config.spiBus || 0, 10),
                deviceNumber: parseInt(config.spiDevice || 0, 10),
                dc, res, busy,
                wireMode: WIRE_MODES[config.wireMode] ?? WireMode.SIMPLEX,
            });
            node.driver = new IL0373Full(node.connection, {
                width: parseInt(config.width || 104, 10),
                height: parseInt(config.height || 212, 10),
                busyTimeoutMs: parseInt(config.busyTimeout || 30000, 10),
            });
            node.driver.ready().catch((e) => node.error('IL0373 init failed: ' + e.message));
        } catch (e) {
            node.error('IL0373 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) { done(); return; }
            const d = node.driver;
            const p = msg.payload || {};
            try {
                if (p.clear !== undefined) d.clear(color(p.clear, WHITE));
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
                    d.blit(b.x, b.y, Buffer.from(b.data), b.w, b.h, color(b.color));
                }
                if (p.wake) await d.wake();
                if (p.show || (node.autoShow && (p.clear !== undefined || p.pixel || p.line || p.rect || p.bitmap))) {
                    node.status({ fill: 'yellow', shape: 'ring', text: 'refreshing' });
                    await d.show();
                    node.status({});
                    msg.payload = { updated: true };
                    send(msg);
                }
                if (p.sleep) await d.sleep();
                if (p.read) {
                    let out;
                    if (p.read === 'temperature') out = { temperature: await d.readTemperature() };
                    else if (p.read === 'status') out = { status: await d.readStatus() };
                    else if (p.read === 'revision') {
                        out = { revision: await d.readRevision() };
                    } else {
                        throw new Error('read must be "temperature", "status" or "revision"');
                    }
                    msg.payload = out;
                    send(msg);
                }
                done();
            } catch (e) {
                node.status({ fill: 'red', shape: 'ring', text: e.message });
                done(e);
            }
        });

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-il0373', IL0373Node);
};
