'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { CCS811Full } = require('periph/src/chips/gas/ccs811');

    function CCS811DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x5A;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new CCS811Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const mode = parseInt(config.mode);
                if (!isNaN(mode) && mode !== 1) await node.driver.setMode(mode);
            })();
            node.ready.catch((e) => {
                node.error('CCS811 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('CCS811 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('ccs811-device', CCS811DeviceNode);

    function CCS811ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('CCS811 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                if (typeof msg.humidity === 'number' && typeof msg.temperature === 'number') {
                    await deviceNode.driver.setEnvironment(msg.humidity, msg.temperature);
                }
                const { eco2_ppm, tvoc_ppb } = await deviceNode.driver.read();
                msg.payload = { eco2_ppm, tvoc_ppb };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('ccs811', CCS811ReadNode);
};
