'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { SPIConnection } = require('periph/src/connection/spi');
    const { Mcp23008Full, Mcp23S08Full } = require('periph/src/chips/io_expander/mcp23008');

    function Mcp23008Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            if (config.transport === 'spi') {
                node.connection = new SPIConnection(parseInt(config.bus), parseInt(config.spiDevice || 0),
                    { maxSpeedHz: 5000000 });
                node.chip = new Mcp23S08Full(node.connection, parseInt(config.spiAddr || 0));
            } else {
                node.connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
                node.chip = new Mcp23008Full(node.connection);
            }
            node.chip.ready.catch((e) => node.error('MCP23008 init failed: ' + e.message));
            node.status({ fill: 'green', shape: 'dot', text: config.transport === 'spi' ? 'SPI' : 'I2C' });
        } catch (e) {
            node.error('MCP23008 init failed: ' + e.message);
        }

        // Optional INT line: arm the pins in the mask and emit { interrupt, capture } messages.
        if (node.chip && config.intPin !== '' && config.intPin !== undefined) {
            (async () => {
                try {
                    const { Default } = require('opengpio');
                    const { PollingInputPin } = require('periph/src/connection/input_pin');
                    const line = Default.input({ chip: parseInt(config.gpioChip || 0, 10), line: parseInt(config.intPin, 10) });
                    const intPin = new PollingInputPin(() => (line.value ? 1 : 0), 5);
                    await node.chip.ready;
                    await node.chip.configurePullup(parseInt(config.intMask || 'F0', 16));
                    await node.chip.enablePinInterrupt(parseInt(config.intMask || 'F0', 16));
                    await node.chip.onInterrupt(async (status) => {
                        const capture = await node.chip.readCapture();
                        node.send({ payload: { interrupt: status, capture } });
                    }, { intPin });
                } catch (e) {
                    node.error('MCP23008 INT setup failed: ' + e.message);
                }
            })();
        }

        node.on('input', async function(msg, send, done) {
            if (!node.chip) { done(new Error('MCP23008 not initialised')); return; }
            try {
                const p = msg.payload;
                if (p && typeof p.pin === 'number' && typeof p.value === 'number') {
                    const pin = node.chip.pin(p.pin);
                    if ((node.chip._direction >> p.pin) & 1) await pin.setDirection('out');
                    await pin.write(p.value ? 1 : 0);
                    done();
                } else if (p && typeof p.pin === 'number') {
                    msg.payload = { pin: p.pin, value: ((await node.chip.readPort()) >> p.pin) & 1 };
                    send(msg);
                    done();
                } else if (p && typeof p.port === 'number') {
                    msg.payload = { port: p.port, value: await node.chip.readPort() };
                    send(msg);
                    done();
                } else {
                    done(new Error('msg.payload must be { pin, value }, { pin }, or { port }'));
                }
            } catch (e) { done(e); }
        });

        node.on('close', async function() {
            if (node.chip) await node.chip.offInterrupt();
            if (node.connection) await node.connection.close();
        });
    }

    RED.nodes.registerType('periph-mcp23008', Mcp23008Node);
};
