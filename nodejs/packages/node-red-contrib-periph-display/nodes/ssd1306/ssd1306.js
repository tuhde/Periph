'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { DisplayConnection } = require('periph/src/connection/display');
    const { GpioOutputPin } = require('periph/src/connection/output_pin');
    const S = require('periph/src/chips/display/ssd1306');

    function SSD1306Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const autoShow = config.autoShow !== false && config.autoShow !== 'false';

        try {
            const width = parseInt(config.width || 128, 10);
            const height = parseInt(config.height || 64, 10);
            const externalVcc = config.externalVcc === true || config.externalVcc === 'true';
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
                node.driver = new S.SSD1306SpiFull(node.connection, width, height, externalVcc);
            } else {
                const bus = parseInt(config.i2cBus || 1, 10);
                const addr = parseInt(config.address || '0x3C', 16);
                node.i2c = new I2CConnection(bus, addr);
                node.driver = new S.SSD1306Full(node.i2c, width, height, externalVcc);
            }
            node.driver.ready().then(() => node.status({ fill: 'green', shape: 'dot', text: 'ready' }))
                .catch((e) => node.error('SSD1306 init failed: ' + e.message));
        } catch (e) {
            node.error('SSD1306 init failed: ' + e.message);
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
                if (p.startLine !== undefined) await d.setStartLine(Number(p.startLine));
                if (p.scroll === false) {
                    await d.scrollStop();
                } else if (p.scroll && typeof p.scroll === 'object') {
                    const sc = p.scroll;
                    const right = sc.direction !== 'left';
                    const start = Number(sc.startPage || 0);
                    const end = Number(sc.endPage === undefined ? 7 : sc.endPage);
                    const interval = Number(sc.interval === undefined ? 7 : sc.interval);
                    await d.scrollStop();
                    if (Number(sc.rows) > 0) await d.setScrollVertical(right, start, end, interval, Number(sc.rows));
                    else await d.setScrollHorizontal(right, start, end, interval);
                    await d.scrollStart();
                }
                if (p.show || (draw && autoShow)) await d.show();
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
    RED.nodes.registerType('periph-ssd1306', SSD1306Node);
};
