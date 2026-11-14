'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { BNO055Full } = require('periph/src/chips/imu/bno055');

    function BNO055Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x29;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new BNO055Full(connection);
            // The driver initialises itself asynchronously (it waits for the chip to boot).
            node.ready = (async () => {
                await node.driver.init();
                const mode = parseInt(config.mode);
                if (!Number.isNaN(mode) && mode !== 12) await node.driver.setMode(mode);
            })();
            node.ready.catch((e) => {
                node.error('BNO055 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('BNO055 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('BNO055 not initialised', msg);
                done();
                return;
            }
            try {
                await node.ready;
                const [heading, roll, pitch] = await node.driver.euler();
                const [sys, gyr, acc, mag] = await node.driver.calibrationStatus();
                msg.payload = { heading, roll, pitch, calibration: { sys, gyr, acc, mag } };
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
    RED.nodes.registerType('periph-bno055', BNO055Node);
};
