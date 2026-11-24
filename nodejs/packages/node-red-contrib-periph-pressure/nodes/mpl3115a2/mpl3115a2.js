'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MPL3115A2Full } = require('periph/src/chips/pressure/mpl3115a2');

    function MPL3115A2DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x60;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new MPL3115A2Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const ratio = parseInt(config.oversample);
                await node.driver.setOversample(isNaN(ratio) ? 128 : ratio);
                const slp = parseFloat(config.seaLevel);
                await node.driver.setSeaLevelPressure(isNaN(slp) ? 101325 : slp);
            })();
            node.ready.catch((e) => {
                node.error('MPL3115A2 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('MPL3115A2 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mpl3115a2-device', MPL3115A2DeviceNode);

    function MPL3115A2ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('MPL3115A2 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                if (msg.altitude === true) {
                    const altitude_m = await deviceNode.driver.readAltitude();
                    const temperature_c = await deviceNode.driver.readTemperature();
                    msg.payload = { altitude_m, temperature_c };
                } else {
                    const { pressure_pa, temperature_c } = await deviceNode.driver.read();
                    msg.payload = { pressure_pa, temperature_c };
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('mpl3115a2', MPL3115A2ReadNode);
};
