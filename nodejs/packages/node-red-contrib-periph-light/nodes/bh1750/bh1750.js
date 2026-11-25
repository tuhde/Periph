'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BH1750Full } = require('periph/src/chips/light/bh1750');

    function BH1750DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x23;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new BH1750Full(connection);
            const mode = parseInt(config.mode);
            node.mode = isNaN(mode) ? BH1750Full.MODE_H : mode;
            node.ready = (async () => {
                await node.driver.init();
                const mtreg = parseInt(config.mtreg);
                await node.driver.setMeasurementTime(isNaN(mtreg) ? 69 : mtreg);
            })();
            node.ready.catch((e) => {
                node.error('BH1750 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('BH1750 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('bh1750-device', BH1750DeviceNode);

    function BH1750ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('BH1750 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const lux = await deviceNode.driver.measureOnce(deviceNode.mode);
                msg.payload = { lux };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('bh1750', BH1750ReadNode);
};
