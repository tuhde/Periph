'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ST25DVFull } = require('periph/src/chips/rfid/st25dv');

    function toBytes(data) {
        if (typeof data === 'string') return Buffer.from(data.replace(/\s+/g, ''), 'hex');
        return Buffer.from(data || []);
    }

    function ST25DVDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const bus = parseInt(config.bus);
            const user = new I2CConnection(bus, parseInt(config.address, 16) || 0x53, null, null, 2);
            const system = new I2CConnection(bus, parseInt(config.systemAddress, 16) || 0x57, null, null, 2);
            node.connections = [user, system];
            node.driver = new ST25DVFull(user, system);
            node.ready = node.driver.init();
            node.ready.catch((e) => {
                node.error('ST25DV init failed: ' + e.message);
            });
        } catch (e) {
            node.error('ST25DV init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connections) node.connections.forEach((c) => c.close());
        });
    }
    RED.nodes.registerType('st25dv-device', ST25DVDeviceNode);

    function ST25DVNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('No ST25DV device configured', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const d = deviceNode.driver;
                const p = (msg.payload && typeof msg.payload === 'object' && !Array.isArray(msg.payload)) ? msg.payload : {};
                if ('ndef' in p) {
                    if (p.ndef !== true && p.ndef != null && toBytes(p.ndef).length) {
                        await d.writeNdef(toBytes(p.ndef));
                    }
                    msg.payload = { ndef: Array.from(await d.readNdef()) };
                } else if (p.data != null) {
                    const address = p.address || 0;
                    const data = toBytes(p.data);
                    await d.write(address, data);
                    msg.payload = { address, written: data.length };
                } else {
                    const address = p.address || 0;
                    const length = p.length != null ? p.length : 16;
                    msg.payload = { address, data: Array.from(await d.read(address, length)) };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('st25dv', ST25DVNode);
};
