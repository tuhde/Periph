'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HTS221Full } = require('periph/src/chips/humidity/hts221');

    function HTS221DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x5F;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new HTS221Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const t = parseInt(config.temperatureSamples) || 16;
                const h = parseInt(config.humiditySamples) || 32;
                await node.driver.setAveraging(t, h);
                await node.driver.setHeater(config.heater === true || config.heater === 'true');
            })();
            node.ready.catch((e) => {
                node.error('HTS221 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('HTS221 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('hts221-device', HTS221DeviceNode);

    function HTS221ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('HTS221 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const { temperature_c, humidity_pct } = await deviceNode.driver.read();
                msg.payload = { temperature_c, humidity_pct };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('hts221', HTS221ReadNode);
};
