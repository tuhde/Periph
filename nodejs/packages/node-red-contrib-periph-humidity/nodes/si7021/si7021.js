'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Si7021Full } = require('periph/src/chips/humidity/si7021');

    const RESOLUTIONS = { '12/14': [12, 14], '8/12': [8, 12], '10/13': [10, 13], '11/11': [11, 11] };

    function Si7021Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x40;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new Si7021Full(connection);
            node.ready = (async () => {
                const res = RESOLUTIONS[config.resolution] || RESOLUTIONS['12/14'];
                await node.driver.setResolution(res[0], res[1]);
                await node.driver.setHeater(config.heater === true || config.heater === 'true');
            })();
            node.ready.catch((e) => {
                node.error('Si7021 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('Si7021 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('Si7021 not initialised', msg);
                done();
                return;
            }
            try {
                await node.ready;
                const [temperature_c, humidity_pct] = await node.driver.read();
                msg.payload = { temperature_c, humidity_pct };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('periph-si7021', Si7021Node);
};
