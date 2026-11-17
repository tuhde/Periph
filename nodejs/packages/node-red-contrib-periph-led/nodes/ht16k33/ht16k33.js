'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HT16K33Full }  = require('periph/src/chips/led/ht16k33');

    function HT16K33DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new HT16K33Full(connection);
            node.connection = connection;
            node.driver.ready.catch(function(e) { node.error('HT16K33 init failed: ' + e.message); });
        } catch (e) {
            node.error('HT16K33 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ht16k33-device', HT16K33DeviceNode);

    function HT16K33Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No HT16K33 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = msg.payload || {};
                let handled = false;
                if (Array.isArray(p.pixels)) {
                    for (const px of p.pixels) d.setPixel(px[0], px[1], !!px[2]);
                    await d.show();
                    handled = true;
                }
                if (Array.isArray(p.raw)) {
                    await d.writeRaw(p.raw, 0);
                    handled = true;
                }
                if (typeof p.brightness === 'number') {
                    await d.setBrightness(p.brightness);
                    handled = true;
                }
                if (typeof p.blink === 'number') {
                    await d.setBlink(p.blink);
                    handled = true;
                }
                if (p.keys === true) {
                    msg.payload = { keys: await d.readKeys() };
                    handled = true;
                }
                if (!handled) {
                    node.error('Payload must have pixels[], raw[], brightness, blink or keys', msg);
                } else {
                    send(msg);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-ht16k33', HT16K33Node);
};
