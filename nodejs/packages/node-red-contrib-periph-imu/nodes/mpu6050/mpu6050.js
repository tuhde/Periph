'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MPU6050Full }   = require('periph/src/chips/imu/mpu6050');

    function MPU6050DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.driver     = new MPU6050Full(connection);
            node.connection = connection;
            if (config.gyroFs) node.driver.configureGyro(parseInt(config.gyroFs));
            if (config.accelFs) node.driver.configureAccel(parseInt(config.accelFs));
        } catch (e) {
            node.error('MPU6050 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mpu6050-device', MPU6050DeviceNode);

    function MPU6050ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No MPU6050 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const [ax, ay, az] = await d.accel();
                const [gx, gy, gz] = await d.gyro();
                msg.payload = { ax, ay, az, gx, gy, gz };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('mpu6050', MPU6050ReadNode);
};
