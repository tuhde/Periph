'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HDC1008Full } = require('periph/src/chips/humidity/hdc1008');

    const RESOLUTIONS = { '14/14': [14, 14], '14/11': [14, 11], '14/8': [14, 8], '11/14': [11, 14], '11/11': [11, 11], '11/8': [11, 8] };

    function HDC1008Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x40;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new HDC1008Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const res = RESOLUTIONS[config.resolution] || [14, 14];
                await node.driver.setResolution(res[0], res[1]);
            })();
            node.ready.catch((e) => {
                node.error('HDC1008 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('HDC1008 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('HDC1008 not initialised', msg);
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
    RED.nodes.registerType('periph-hdc1008', HDC1008Node);
};
