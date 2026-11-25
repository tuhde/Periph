'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { IS31FL3731Full }  = require('periph/src/chips/led/is31fl3731');

    function IS31FL3731DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new IS31FL3731Full(connection);
            node.connection = connection;
            node.driver.ready.catch(function(e) { node.error('IS31FL3731 init failed: ' + e.message); });
        } catch (e) {
            node.error('IS31FL3731 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('is31fl3731-device', IS31FL3731DeviceNode);

    function IS31FL3731Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No IS31FL3731 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = msg.payload || {};
                let handled = false;
                if (typeof p.fill === 'number') {
                    await d.fill(p.fill);
                    handled = true;
                }
                if (p.clear === true) {
                    await d.clear();
                    handled = true;
                }
                if (Array.isArray(p.pixels)) {
                    for (const px of p.pixels) await d.setPixel(px[0], px[1], px[2]);
                    handled = true;
                }
                if (handled) msg.payload = { ok: true };
                if (!handled) {
                    node.error('Payload must have pixels[], fill or clear', msg);
                } else {
                    send(msg);
                }
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('is31fl3731', IS31FL3731Node);
};
