'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { ICM20948Full } = require('periph/src/chips/imu/icm20948');

    function ICM20948Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const address = parseInt(config.address, 16) || 0x68;
            const connection = new I2CConnection(parseInt(config.bus), address);
            node.connection = connection;
            node.driver = new ICM20948Full(connection);
            // The driver initialises itself asynchronously; configure it once ready.
            node.ready = (async () => {
                await node.driver.init();
                await node.driver.configureAccel(parseInt(config.accelRange) || 0);
                await node.driver.configureGyro(parseInt(config.gyroRange) || 0);
                if (config.mag) await node.driver.enableMag();
            })();
            node.ready.catch((e) => {
                node.error('ICM20948 init failed: ' + e.message);
            });
        } catch (e) {
            node.error('ICM20948 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('ICM20948 not initialised', msg);
                done();
                return;
            }
            try {
                await node.ready;
                const [accel, gyro, temperature] = await node.driver.readAll();
                const payload = {
                    accel: { x: accel[0], y: accel[1], z: accel[2] },
                    gyro: { x: gyro[0], y: gyro[1], z: gyro[2] },
                    temperature,
                };
                if (config.mag) {
                    const [x, y, z] = await node.driver.mag();
                    payload.mag = { x, y, z };
                }
                msg.payload = payload;
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
    RED.nodes.registerType('periph-icm20948', ICM20948Node);
};
