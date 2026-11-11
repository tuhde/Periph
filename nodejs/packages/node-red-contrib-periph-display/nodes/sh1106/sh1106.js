'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { DisplayConnection } = require('periph/src/connection/display');
    const { GpioOutputPin } = require('periph/src/connection/output_pin');
    const S = require('periph/src/chips/display/sh1106');

    function SH1106Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const autoShow = config.autoShow !== false && config.autoShow !== 'false';

        try {
            const width = parseInt(config.width || 128, 10);
            const height = parseInt(config.height || 64, 10);
            const offset = parseInt(config.columnOffset === undefined || config.columnOffset === '' ? 2 : config.columnOffset, 10);
            if (config.transport === 'spi') {
                const { Default } = require('opengpio');
                const chip = parseInt(config.gpioChip || 0, 10);
                const dc = new GpioOutputPin(Default.output({ chip, line: parseInt(config.dcPin || 25, 10) }));
                const res = new GpioOutputPin(Default.output({ chip, line: parseInt(config.resPin || 17, 10) }));
                node.connection = new DisplayConnection({
                    busNumber: parseInt(config.spiBus || 0, 10),
                    deviceNumber: parseInt(config.spiDevice || 0, 10),
                    dc, res, maxSpeedHz: 2000000,
                });
                node.driver = new S.SH1106SpiFull(node.connection, width, height, offset);
            } else {
                const bus = parseInt(config.i2cBus || 1, 10);
                const addr = parseInt(config.address || '0x3C', 16);
                node.i2c = new I2CConnection(bus, addr);
                node.driver = new S.SH1106Full(node.i2c, width, height, offset);
            }
            node.driver.ready().then(() => node.status({ fill: 'green', shape: 'dot', text: 'ready' }))
                .catch((e) => node.error('SH1106 init failed: ' + e.message));
        } catch (e) {
            node.error('SH1106 init failed: ' + e.message);
        }

        // Messages are handled one at a time: two overlapping transfers would corrupt each other.
        let queue = Promise.resolve();
        node.on('input', function(msg, send, done) {
            queue = queue.then(() => handle(msg, send, done));
        });

        async function handle(msg, send, done) {
            if (!node.driver) { done(); return; }
            const d = node.driver;
            let p = msg.payload;
            try {
                await d.ready();
                if (p === null || typeof p !== 'object') p = {};
                let draw = false;
                if (p.clear) { d.clear(); draw = true; }
                if (p.pixel) { d.setPixel(p.pixel.x, p.pixel.y, p.pixel.on !== false); draw = true; }
                if (p.line) { d.drawLine(p.line.x0, p.line.y0, p.line.x1, p.line.y1, p.line.on !== false); draw = true; }
                if (p.rect) { d.drawRect(p.rect.x, p.rect.y, p.rect.w, p.rect.h, p.rect.on !== false, !!p.rect.fill); draw = true; }
                if (p.bitmap) { d.blit(p.bitmap.x, p.bitmap.y, p.bitmap.columns, p.bitmap.heightPages || 1); draw = true; }
                if (p.contrast !== undefined) await d.setContrast(Number(p.contrast));
                if (p.invert !== undefined) await d.setInvert(!!p.invert);
                if (p.display !== undefined) await d.setDisplay(!!p.display);
                if (p.scroll !== undefined) await d.setStartLine(Number(p.scroll));
                if (p.show || (draw && autoShow)) await d.show();
                if (p.status) {
                    if (config.transport === 'spi') throw new Error('status read is not available over SPI');
                    send({ payload: { status: await d.readStatus() } });
                }
                node.status({ fill: 'green', shape: 'dot', text: 'ready' });
                done();
            } catch (e) {
                node.status({ fill: 'red', shape: 'ring', text: e.message });
                done(e);
            }
        }

        node.on('close', function() {
            if (node.i2c) node.i2c.close();
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-sh1106', SH1106Node);
};
