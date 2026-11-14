'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HTU21DFFull } = require('periph/src/chips/humidity/htu21df');

    const RESOLUTIONS = { '12/14': 0, '8/12': 1, '10/13': 2, '11/11': 3 };

    function HTU21DFNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x40;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new HTU21DFFull(connection);
            node.ready = (async () => {
                await node.driver.init();
                const res = RESOLUTIONS[config.resolution];
                await node.driver.setResolution(res === undefined ? 0 : res);
                await node.driver.setHeater(config.heater === true || config.heater === 'true');
            })();
            node.ready.catch((e) => {
                node.error('HTU21D-F init failed: ' + e.message);
            });
        } catch (e) {
            node.error('HTU21D-F init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('HTU21D-F not initialised', msg);
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
    RED.nodes.registerType('periph-htu21df', HTU21DFNode);
};
