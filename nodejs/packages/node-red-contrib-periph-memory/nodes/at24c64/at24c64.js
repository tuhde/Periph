'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { AT24C64Full } = require('periph/src/chips/memory/at24c64');

    function AT24C64DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16), null, null, 2);
            node.driver     = new AT24C64Full(connection);
            node.connection = connection;
        } catch (e) {
            node.error('AT24C64 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('at24c64-device', AT24C64DeviceNode);

    function AT24C64Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No AT24C64 device configured', msg);
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
    RED.nodes.registerType('periph-at24c64', AT24C64Node);
};
