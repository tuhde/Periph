'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { SHT31Full } = require('periph/src/chips/humidity/sht31');

    function SHT31DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x44;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new SHT31Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                node.driver.setRepeatability(['low', 'medium', 'high'].includes(config.repeatability) ? config.repeatability : 'high');
            })();
            node.ready.catch((e) => {
                node.error('SHT31 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('SHT31 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('sht31-device', SHT31DeviceNode);

    function SHT31ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('SHT31 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const [temperature, humidity] = await deviceNode.driver.read();
                msg.payload = { temperature, humidity };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('sht31', SHT31ReadNode);
};
