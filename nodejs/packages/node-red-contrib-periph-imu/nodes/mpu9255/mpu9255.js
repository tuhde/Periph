'use strict';

module.exports = function(RED) {
    function MPU9255Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        const connection = RED.nodes.getNode(config.connection);
        if (!connection) {
            node.error('No I2C connection configured');
            return;
        }

        const enableMag  = !!config.enableMag;
        const enableWom  = !!config.enableWom;
        const womThreshold = parseInt(config.womThreshold, 10) || 64;

        let sensor;
        try {
            if (enableMag) {
                const { MPU9255Full } = require('periph/src/chips/imu/mpu9255');
                sensor = new MPU9255Full(connection, null);
                // Note: the magnetometer (AK8963 at 0x0C) lives on its own
                // connection bound to that address; this Minimal-only node
                // does not wire it. For full mag support, use the Full API
                // directly via a function node.
            } else {
                const { MPU9255Minimal } = require('periph/src/chips/imu/mpu9255');
                sensor = new MPU9255Minimal(connection);
            }
            if (enableWom) {
                sensor.configure_wake_on_motion && sensor.configure_wake_on_motion(womThreshold, 31.25);
            }
        } catch (err) {
            node.error('MPU9255 init failed: ' + err.message);
            return;
        }

        this.on('input', async function(msg) {
            try {
                const [ax, ay, az] = await sensor.accel();
                const [gx, gy, gz] = await sensor.gyro();
                const out = { ax, ay, az, gx, gy, gz };
                if (enableWom && typeof sensor.motion_detected === 'function') {
                    if (await sensor.motion_detected()) {
                        out.motion = true;
                    }
                }
                msg.payload = out;
                node.send(msg);
            } catch (err) {
                node.error('MPU9255 read failed: ' + err.message, msg);
            }
        });
    }

    RED.nodes.registerType('periph-mpu9255', MPU9255Node);
};