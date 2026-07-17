'use strict';

module.exports = function(RED) {
    const { UARTTransport } = require('periph/src/transport/uart');
    const { I2CTransport }  = require('periph/src/transport/i2c');
    const { SPITransport }  = require('periph/src/transport/spi');
    const { NEO6Full }      = require('periph/src/chips/gnss/neo6');

    function NEO6Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.busType = config.busType || 'uart';
        node.ready = false;

        (async () => {
            try {
                let transport;
                if (node.busType === 'i2c') {
                    const addr = parseInt(config.addr) || 0x42;
                    transport = new I2CTransport(parseInt(config.bus) || 1, addr);
                } else if (node.busType === 'spi') {
                    transport = new SPITransport(parseInt(config.bus) || 0, parseInt(config.device) || 0,
                        { mode: 0, maxSpeedHz: 200_000 });
                } else {
                    transport = new UARTTransport(config.port || '/dev/ttyS0',
                        { baudRate: parseInt(config.baud) || 9600 });
                    await transport.open();
                }
                node.transport = transport;
                node.driver = new NEO6Full(transport, node.busType);
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
                // transport's read timeout, so an unbounded iteration count
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
            if (node.transport) {
                try { await node.transport.close(); } catch (_) { /* already closed */ }
            }
            done();
        });
    }

    RED.nodes.registerType('periph-neo6', NEO6Node);
};
