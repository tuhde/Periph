'use strict';

module.exports = function(RED) {
    const { UARTConnection } = require('periph/src/connection/uart');
    const { I2CConnection }  = require('periph/src/connection/i2c');
    const { SPIConnection }  = require('periph/src/connection/spi');
    const { NEO6Full }      = require('periph/src/chips/gnss/neo6');

    function NEO6Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.busType = config.busType || 'uart';
        node.ready = false;

        (async () => {
            try {
                let connection;
                if (node.busType === 'i2c') {
                    const addr = parseInt(config.addr) || 0x42;
                    connection = new I2CConnection(parseInt(config.bus) || 1, addr);
                } else if (node.busType === 'spi') {
                    connection = new SPIConnection(parseInt(config.bus) || 0, parseInt(config.device) || 0,
                        { mode: 0, maxSpeedHz: 200_000 });
                } else {
                    connection = new UARTConnection(config.port || '/dev/ttyS0',
                        { baudRate: parseInt(config.baud) || 9600 });
                    await connection.open();
                }
                node.connection = connection;
                node.driver = new NEO6Full(connection, node.busType);
                node.ready = true;
            } catch (e) {
                node.error('NEO-6 init failed: ' + e.message);
            }
        })();

        node.on('input', async function(msg, send, done) {
            if (!node.ready || !node.driver) {
                node.error('NEO-6 not ready', msg);
                done();
                return;
            }
            try {
                // A GPS burst (~7 sentences at the module's 1 Hz output rate)
                // is far more than one byte; drain what's currently buffered
                // each time this node fires rather than reading a single
                // byte. Time-boxed rather than a fixed iteration count: on
                // UART a byte-less update() only resolves after the
                // connection's read timeout, so an unbounded iteration count
                // could block this node for a very long time once the
                // stream goes quiet between bursts.
                const deadline = Date.now() + 900;
                while (Date.now() < deadline) {
                    await node.driver.update();
                }
                const d = node.driver;
                msg.payload = {
                    lat: d.latitude(),
                    lon: d.longitude(),
                    alt: d.altitude(),
                    fix: d.fix(),
                    satellites: d.satellites(),
                    speed: d.speed(),
                    course: d.course(),
                    time: d.utcTime(),
                    date: d.utcDate(),
                    hdop: d.hdop()
                };
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(done) {
            if (node.connection) {
                try { await node.connection.close(); } catch (_) { /* already closed */ }
            }
            done();
        });
    }

    RED.nodes.registerType('periph-neo6', NEO6Node);
};
