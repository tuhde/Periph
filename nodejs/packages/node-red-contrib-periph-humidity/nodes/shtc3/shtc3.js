'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { SHTC3Full } = require('periph/src/chips/humidity/shtc3');

    function SHTC3DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x70;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new SHTC3Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                node.lowPower = config.lowPower === true || config.lowPower === 'true';
            })();
            node.ready.catch((e) => {
                node.error('SHTC3 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('SHTC3 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('shtc3-device', SHTC3DeviceNode);

    function SHTC3ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('SHTC3 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const lowPower = typeof msg.low_power === 'boolean' ? msg.low_power : deviceNode.lowPower === true;
                deviceNode.driver.setLowPower(lowPower);
                const { temperature_c, humidity_pct } = await deviceNode.driver.read();
                msg.payload = { temperature_c, humidity_pct };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('shtc3', SHTC3ReadNode);
};
