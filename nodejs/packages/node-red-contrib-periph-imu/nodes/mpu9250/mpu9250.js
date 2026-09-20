'use strict';

module.exports = function(RED) {
    function MPU9250Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        const connection = RED.nodes.getNode(config.connection);
        if (!connection) {
            node.error('No I2C connection configured');
            return;
        }

        const { MPU9250Minimal } = require('periph/src/chips/imu/mpu9250');
        const sensor = new MPU9250Minimal(connection);

        this.on('input', async function(msg) {
            try {
                const [ax, ay, az] = await sensor.accel();
                const [gx, gy, gz] = await sensor.gyro();
                msg.payload = { ax, ay, az, gx, gy, gz };
                node.send(msg);
            } catch (err) {
                node.error('MPU9250 read failed: ' + err.message, msg);
            }
        });
    }

    RED.nodes.registerType('periph-mpu9250', MPU9250Node);
};