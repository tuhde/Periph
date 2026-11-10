'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { SPIConnection } = require('periph/src/connection/spi');
    const { QMI8658AFull } = require('periph/src/chips/imu/qmi8658a');

    function QMI8658ANode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            let connection;
            if (config.transport === 'spi') {
                // 4-wire SPI mode 3, read bit 0x80, no multi-byte bit (burst needs CTRL1.ADDR_AI, set by the driver).
                connection = new SPIConnection(parseInt(config.bus), parseInt(config.device) || 0,
                    { mode: 3, readBit: 0x80, multiByteBit: null, maxSpeedHz: 10000000 });
            } else {
                const address = parseInt(config.address, 16) || 0x6B;
                connection = new I2CConnection(parseInt(config.bus), address);
            }
            node.connection = connection;
            node.driver = new QMI8658AFull(connection);
            // The driver initialises itself asynchronously; configure it once ready.
            node.ready = (async () => {
                await node.driver.init();
                const odr = parseInt(config.odr);
                const code = Number.isNaN(odr) ? 5 : odr;
                await node.driver.configureAccel(parseInt(config.accelRange) || 0, code);
                await node.driver.configureGyro(Number.isNaN(parseInt(config.gyroRange)) ? 4 : parseInt(config.gyroRange), code);
                await new Promise((r) => setTimeout(r, 165));
            })();
            node.ready.catch((e) => {
                node.error('QMI8658A init failed: ' + e.message);
            });
        } catch (e) {
            node.error('QMI8658A init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver) {
                node.error('QMI8658A not initialised', msg);
                done();
                return;
            }
            try {
                await node.ready;
                const [accel, gyro, temperature] = await node.driver.readAll();
                msg.payload = {
                    accel: { x: accel[0], y: accel[1], z: accel[2] },
                    gyro: { x: gyro[0], y: gyro[1], z: gyro[2] },
                    temperature,
                };
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
    RED.nodes.registerType('periph-qmi8658a', QMI8658ANode);
};
