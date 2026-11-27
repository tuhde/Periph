'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { RRH46410Full } = require('periph/src/chips/gas/rrh46410');

    function RRH46410DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), 0x38);
            node.connection = connection;
            node.driver = new RRH46410Full(connection);
            node.ready = (async () => {
                await node.driver.init();
                const humidity = parseFloat(config.humidity);
                if (!isNaN(humidity) && humidity !== 50) await node.driver.setHumidity(humidity);
                const mode = parseInt(config.mode);
                if (!isNaN(mode) && mode !== RRH46410Full.MODE_IAQ_2ND_GEN) await node.driver.setMode(mode);
            })();
            node.ready.catch((e) => {
                node.error('RRH46410 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('RRH46410 init failed: ' + e.message);
        }

        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('rrh46410-device', RRH46410DeviceNode);

    function RRH46410ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) {
                node.error('RRH46410 not initialised', msg);
                done();
                return;
            }
            try {
                await deviceNode.ready;
                const driver = deviceNode.driver;
                if (typeof msg.humidity === 'number') await driver.setHumidity(msg.humidity);
                let r = await driver.readResults();
                if (r === null) r = await driver.read();
                const { iaq, tvoc_mg_m3, etoh_ppm, eco2_ppm, relative_iaq, warmed_up } = r;
                msg.payload = { iaq, tvoc_mg_m3, etoh_ppm, eco2_ppm, relative_iaq, warmed_up };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('rrh46410', RRH46410ReadNode);
};
