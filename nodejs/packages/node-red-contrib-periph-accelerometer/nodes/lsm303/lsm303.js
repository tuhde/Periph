'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LSM303Full }    = require('periph/src/chips/accelerometer/lsm303');

    // The LSM303 puts the accelerometer and the magnetometer behind two
    // independent I²C slave addresses, so the device needs both.
    function LSM303DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const accelConnection = new I2CConnection(parseInt(config.bus), 0x19);
            const magConnection = new I2CConnection(parseInt(config.bus), 0x1E);
            node.driver     = new LSM303Full(accelConnection, magConnection);
            node.accelConnection = accelConnection;
            node.magConnection = magConnection;
        } catch (e) {
            node.error('LSM303 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.accelConnection) node.accelConnection.close();
            if (node.magConnection) node.magConnection.close();
        });
    }
    RED.nodes.registerType('lsm303-device', LSM303DeviceNode);

    function LSM303ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No LSM303 device configured', msg);
                done();
                return;
            }
            try {
                const accel = await node.device.driver.readAcceleration();
                const mag = await node.device.driver.readMagnetic();
                let heading = null;
                try { heading = await node.device.driver.heading(false); } catch (e) { heading = null; }
                msg.payload = {
                    accel: { x: accel.x, y: accel.y, z: accel.z },
                    mag: { x: mag.x, y: mag.y, z: mag.z },
                    heading: heading
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('lsm303', LSM303ReadNode);
};
