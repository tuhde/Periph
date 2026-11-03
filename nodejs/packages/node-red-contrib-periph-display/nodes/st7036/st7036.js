'use strict';

module.exports = function(RED) {
    const { Default }           = require('opengpio');
    const { DisplayConnection } = require('periph/src/connection/display');
    const { GpioOutputPin }     = require('periph/src/connection/output_pin');
    const S = require('periph/src/chips/display/st7036');

    function ST7036Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        try {
            const chip = parseInt(config.gpioChip || 0, 10);
            const dc = new GpioOutputPin(Default.output({ chip, line: parseInt(config.rsPin, 10) }));
            const res = new GpioOutputPin(Default.output({ chip, line: parseInt(config.resPin, 10) }));
            node.connection = new DisplayConnection({
                busNumber: parseInt(config.spiBus || 0, 10),
                deviceNumber: parseInt(config.spiDevice || 0, 10),
                dc, res, maxSpeedHz: 1000000,
            });
            const lines = parseInt(config.lines || 2, 10);
            const supply = config.supply === '5v' ? S.SUPPLY_5V : S.SUPPLY_3V3;
            node.driver = new S.ST7036Full(node.connection, lines, null, supply);
            node.driver.ready().catch((e) => node.error('ST7036 init failed: ' + e.message));
        } catch (e) {
            node.error('ST7036 init failed: ' + e.message);
        }

        // Messages are handled one at a time: two overlapping command streams would corrupt each other.
        let queue = Promise.resolve();
        node.on('input', function(msg, send, done) {
            queue = queue.then(() => handle(msg, send, done));
        });

        async function handle(msg, send, done) {
            if (!node.driver) { done(); return; }
            const d = node.driver;
            let p = msg.payload;
            try {
                if (typeof p === 'string') p = { text: p };
                if (p === null || typeof p !== 'object') p = {};
                if (p.clear) await d.clear();
                if (p.home) await d.home();
                if (p.display !== undefined) await d.display(!!p.display);
                if (p.contrast !== undefined) await d.setContrast(Number(p.contrast));
                if (p.char) await d.createChar(p.char.index, p.char.rows);
                if (p.cursor && typeof p.cursor === 'object') {
                    if (p.cursor.col !== undefined || p.cursor.row !== undefined) {
                        await d.setCursor(p.cursor.col || 0, p.cursor.row || 0);
                    }
                    await d.cursor(!!p.cursor.on, !!p.cursor.blink);
                }
                if (p.shift !== undefined) {
                    if (p.shift !== 'left' && p.shift !== 'right') throw new Error('shift must be "left" or "right"');
                    await d.shiftDisplay(p.shift === 'right');
                }
                if (p.text !== undefined) {
                    if (p.col !== undefined || p.row !== undefined) await d.printAt(p.col || 0, p.row || 0, String(p.text));
                    else await d.write(String(p.text));
                }
                if (p.chars) for (const c of p.chars) await d.writeChar(c);
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
    RED.nodes.registerType('periph-st7036', ST7036Node);
};
