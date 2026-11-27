'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { PN532Full } = require('periph/src/chips/rfid/pn532');

    function PN532DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus, 10), 0x24);
            node.connection = connection;
            node.driver = new PN532Full(connection);
            node.ready = node.driver.init();
            node.ready.catch((e) => {
                node.error('PN532 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('PN532 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('pn532-device', PN532DeviceNode);

    function PN532ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};
        const timeout = parseFloat(config.timeout) || 1.0;

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('PN532 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const uid = await deviceNode.driver.readUid(timeout);
                msg.payload = { uid: uid ? uid.toString('hex').toUpperCase() : null };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('pn532', PN532ReadNode);
};
