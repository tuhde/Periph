'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LSM303Full }    = require('periph/src/chips/accelerometer/lsm303');

    const ACCEL_ADDR = 0x19;
    const MAG_ADDR   = 0x1E;

    /** Normalise an (x, y, z) result that may be an array or an object. */
    function toXYZ(v) {
        if (Array.isArray(v)) return { x: v[0], y: v[1], z: v[2] };
        return { x: v.x, y: v.y, z: v.z };
    }

    function LSM303Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;

        let accelConn = null;
        let magConn   = null;
        let driver    = null;

        try {
            const bus = parseInt(config.bus, 10);
            accelConn = new I2CConnection(bus, ACCEL_ADDR);
            magConn   = new I2CConnection(bus, MAG_ADDR);
            driver    = new LSM303Full(accelConn, magConn);
        } catch (e) {
            node.error('LSM303 init failed: ' + e.message);
        }

        // Apply configuration once; constructor is sync, setters may be async.
        const ready = (async function() {
            if (!driver) throw new Error('LSM303 not initialised');
            if (config.accelRange) await driver.setRange(parseInt(config.accelRange, 10));
            if (config.accelRate)  await driver.setDataRate(parseInt(config.accelRate, 10));
            if (config.magRange)   await driver.setMagRange(parseInt(config.magRange, 10));
            if (config.magRate)    await driver.setMagDataRate(parseFloat(config.magRate));
        })();
        // Avoid unhandled-rejection noise; the error is re-raised per message.
        ready.catch(function() {});

        node.on('input', async function(msg, send, done) {
            if (!driver) {
                node.error('LSM303 not initialised', msg);
                done();
                return;
            }
            try {
                await ready;
                const acceleration = toXYZ(await driver.readAcceleration());
                const magnetic     = toXYZ(await driver.readMagnetic());
                const heading      = await driver.heading(true);
                const temperature  = await driver.readTemperatureDelta();

                msg.payload = {
                    acceleration,
                    magnetic,
                    heading,
                    temperature_delta: temperature
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (accelConn) await accelConn.close();
                if (magConn)   await magConn.close();
            } catch (e) {
                // Ignore close errors during shutdown.
            }
            done();
        });
    }
    RED.nodes.registerType('periph-lsm303', LSM303Node);
};
