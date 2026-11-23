'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Pcf8575Full }   = require('periph/src/chips/io_expander/pcf8575');

    function Pcf8575DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(
                parseInt(config.bus),
                parseInt(config.address, 16)
            );
            node.chip       = new Pcf8575Full(connection);
            node.connection = connection;
        } catch (e) {
            node.error('PCF8575 init failed: ' + e.message);
        }

        node.on('close', async function() {
            if (node.connection) await node.connection.close();
        });
    }
    RED.nodes.registerType('pcf8575-device', Pcf8575DeviceNode);

    function Pcf8575ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.chip) { done(new Error('PCF8575 not initialised')); return; }
            try {
                const p = msg.payload;
                if (p && typeof p.pin === 'number' && typeof p.value === 'number') {
                    await deviceNode.chip._setPin(p.pin, p.value ? 1 : 0);
                    done();
                } else if (p && typeof p.pin === 'number') {
                    const port = Math.floor(p.pin / 8);
                    const bit = p.pin % 8;
                    msg.payload = { pin: p.pin, value: ((await deviceNode.chip.readPort(port)) >> bit) & 1 };
                    send(msg);
                    done();
                } else if (p && typeof p.port === 'number') {
                    msg.payload = { port: p.port, value: await deviceNode.chip.readPort(p.port) };
                    send(msg);
                    done();
                } else {
                    done(new Error('msg.payload must be { pin, value }, { pin }, or { port }'));
                }
            } catch (e) { done(e); }
        });
    }
    RED.nodes.registerType('pcf8575', Pcf8575ReadNode);
};
