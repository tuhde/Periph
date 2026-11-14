'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AT24C02NFull } = require('periph/src/chips/memory/at24c02n');

    function AT24C02NDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new AT24C02NFull(connection);
            node.connection = connection;
        } catch (e) {
            node.error('AT24C02N init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('at24c02n-device', AT24C02NDeviceNode);

    function AT24C02NNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AT24C02N device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const p = (msg.payload && typeof msg.payload === 'object' && !Array.isArray(msg.payload)) ? msg.payload : {};
                const pick = (k, dflt) => (p[k] != null) ? p[k] : (msg[k] != null ? msg[k] : dflt);
                const op = pick('op', 'read');
                const address = pick('address', 0);
                if (op === 'read') {
                    const data = await d.read(address, pick('length', 1));
                    msg.payload = { data: Array.from(data) };
                } else if (op === 'write') {
                    await d.write(address, Buffer.from(pick('data', [])));
                    msg.payload = { ok: true };
                } else if (op === 'fill') {
                    await d.fill(address, pick('length', 0), pick('value', 0));
                    msg.payload = { ok: true };
                } else {
                    node.error('Unknown op: ' + op, msg);
                    done();
                    return;
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-at24c02n', AT24C02NNode);
};
