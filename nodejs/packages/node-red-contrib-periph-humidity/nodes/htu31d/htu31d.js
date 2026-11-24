'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { HTU31DFull } = require('periph/src/chips/humidity/htu31d');

    function HTU31DDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x40;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new HTU31DFull(connection);
            node.ready = (async () => {
                await node.driver.init();
                const rh = parseInt(config.osrRh);
                const t = parseInt(config.osrT);
                node.driver.setResolution(isNaN(rh) ? 3 : rh, isNaN(t) ? 3 : t);
                await node.driver.setHeater(config.heater === true || config.heater === 'true');
            })();
            node.ready.catch((e) => {
                node.error('HTU31D init failed: ' + e.message);
            });
        } catch (e) {
            node.error('HTU31D init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('htu31d-device', HTU31DDeviceNode);

    function HTU31DReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('HTU31D not initialised', msg);
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
    RED.nodes.registerType('htu31d', HTU31DReadNode);
};
