'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { LSM303Full }   = require('periph/src/chips/accelerometer/lsm303');

    function LSM303Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const bus = parseInt(config.bus);
            // Both slave addresses are fixed by the module: accelerometer 0x19, magnetometer 0x1E.
            const accelConnection = new I2CConnection(bus, 0x19);
            const magConnection   = new I2CConnection(bus, 0x1E);
            node.driver     = new LSM303Full(accelConnection, magConnection);
            node.connections = [accelConnection, magConnection];
            node.ready = (async () => {
                if (config.accelRange) await node.driver.setRange(parseInt(config.accelRange));
                if (config.accelRate)  await node.driver.setDataRate(parseFloat(config.accelRate));
                if (config.magRange)   await node.driver.setMagRange(parseInt(config.magRange));
                if (config.magRate)    await node.driver.setMagDataRate(parseFloat(config.magRate));
            })();
            node.ready.catch(e => node.error('LSM303 config failed: ' + e.message));
        } catch (e) {
            node.error('LSM303 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connections) node.connections.forEach(c => c.close());
        });
        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('No LSM303 device configured', msg);
                done();
                return;
            }
            try {
                await node.ready;
                const [ax, ay, az] = await node.driver.readAcceleration();
                const [mx, my, mz] = await node.driver.readMagnetic();
                const heading = await node.driver.heading();
                let temperatureDelta;
                try { temperatureDelta = await node.driver.readTemperatureDelta(); } catch (e) { temperatureDelta = null; }
                msg.payload = {
                    acceleration: { x: ax, y: ay, z: az },
                    magnetic: { x: mx, y: my, z: mz },
                    heading: heading,
                    temperature_delta: temperatureDelta
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('periph-lsm303', LSM303Node);
};
