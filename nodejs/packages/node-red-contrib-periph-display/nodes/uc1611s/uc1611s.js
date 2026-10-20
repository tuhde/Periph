'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const U = require('periph/src/chips/display/uc1611s');

    function UC1611sNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const autoShow = config.autoShow !== false && config.autoShow !== 'false';

        try {
            const bus = parseInt(config.i2cBus || 1, 10);
            const addr = parseInt(config.address || '0x38', 16);
            node.command = new I2CConnection(bus, addr);
            node.data = new I2CConnection(bus, addr + 1);
            node.driver = new U.UC1611sFull(node.command, node.data,
                parseInt(config.width || 240, 10), parseInt(config.height || 128, 10),
                config.topView === true || config.topView === 'true',
                parseInt(config.contrast === undefined || config.contrast === '' ? 143 : config.contrast, 10));
            node.driver.ready().then(() => node.status({ fill: 'green', shape: 'dot', text: 'ready' }))
                .catch((e) => node.error('UC1611s init failed: ' + e.message));
        } catch (e) {
            node.error('UC1611s init failed: ' + e.message);
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
                if (p.scroll !== undefined) await d.setScrollLine(Number(p.scroll));
                if (p.flip) await d.setFlip(!!p.flip.x, !!p.flip.y);
                if (p.show || (draw && autoShow)) await d.show();
                if (p.status) {
                    const s = await d.readStatus();
                    send({ payload: { status: [s[0], s[1], s[2]] } });
                }
                node.status({ fill: d.ok() ? 'green' : 'yellow', shape: 'dot', text: d.ok() ? 'ready' : 'errors ' + d.errorCount() });
                done();
            } catch (e) {
                node.status({ fill: 'red', shape: 'ring', text: e.message });
                done(e);
            }
        }

        node.on('close', function() {
            if (node.command) node.command.close();
            if (node.data) node.data.close();
        });
    }
    RED.nodes.registerType('periph-uc1611s', UC1611sNode);
};
