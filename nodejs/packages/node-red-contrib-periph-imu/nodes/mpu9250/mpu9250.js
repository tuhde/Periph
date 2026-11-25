'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { MPU9250Full } = require('periph/src/chips/imu/mpu9250');

    function MPU9250DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.enableMag = !!config.enableMag;
        try {
            const bus = parseInt(config.bus, 10);
            const connection = new I2CConnection(bus, parseInt(config.addr, 16) || 0x68);
            // The AK8963 magnetometer sits behind the bypass mux at 0x0C and needs its own connection.
            const magConnection = node.enableMag ? new I2CConnection(bus, 0x0C) : null;
            node.connection = connection;
            node.magConnection = magConnection;
            node.driver = new MPU9250Full(connection, magConnection);
            node.ready = (async () => {
                await node.driver.configureGyro(parseInt(config.gyroFs, 10) || 0);
                await node.driver.configureAccel(parseInt(config.accelFs, 10) || 0);
                if (node.enableMag) await node.driver.enableMag();

            })();
            node.ready.catch((e) => node.error('MPU9250 init failed: ' + e.message));
        } catch (e) {
            node.error('MPU9250 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
            if (node.magConnection) node.magConnection.close();
        });
    }
    RED.nodes.registerType('mpu9250-device', MPU9250DeviceNode);

    function MPU9250ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.driver) { done(new Error('No MPU9250 device configured')); return; }
            try {
                await deviceNode.ready;
                const d = deviceNode.driver;
                const [ax, ay, az] = await d.accel();
                const [gx, gy, gz] = await d.gyro();
                const out = { ax, ay, az, gx, gy, gz };
                if (deviceNode.enableMag) {
                    const [mx, my, mz] = await d.mag();
                    Object.assign(out, { mx, my, mz });
                }

                msg.payload = out;
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('mpu9250', MPU9250ReadNode);
};
