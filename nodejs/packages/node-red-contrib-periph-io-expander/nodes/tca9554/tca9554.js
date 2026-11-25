'use strict';

module.exports = function(RED) {
    const { I2CConnection } = require('periph/src/connection/i2c');
    const { Tca9554Full } = require('periph/src/chips/io_expander/tca9554');

    function Tca9554DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            node.connection = new I2CConnection(parseInt(config.bus), parseInt(config.address, 16));
            node.chip = new Tca9554Full(node.connection);
            node.chip.ready.catch((e) => node.error('TCA9554 init failed: ' + e.message));
            node.status({ fill: 'green', shape: 'dot', text: 'I2C' });
        } catch (e) {
            node.error('TCA9554 init failed: ' + e.message);
        }

        // Optional INT line (otherwise nothing is emitted unless polled): emit { port, changed, value }.
        if (node.chip && config.intPin !== '' && config.intPin !== undefined) {
            (async () => {
                try {
                    const { Default } = require('opengpio');
                    const { PollingInputPin } = require('periph/src/connection/input_pin');
                    const line = Default.input({ chip: parseInt(config.gpioChip || 0, 10), line: parseInt(config.intPin, 10) });
                    const intPin = new PollingInputPin(() => (line.value ? 1 : 0), 5);
                    await node.chip.ready;
                    await node.chip.onInterrupt(async (changed) => {
                        const value = await node.chip.readPort();
                        node.emit('periph-data', { payload: { port: 0, changed, value } });
                    }, { intPin });
                } catch (e) {
                    node.error('TCA9554 INT setup failed: ' + e.message);
                }
            })();
        }

        node.on('close', async function() {
            if (node.chip) await node.chip.offInterrupt();
            if (node.connection) await node.connection.close();
        });
    }
    RED.nodes.registerType('tca9554-device', Tca9554DeviceNode);

    function Tca9554ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device) || {};
        if (deviceNode.on) {
            const onData = (m) => node.send(m);
            deviceNode.on('periph-data', onData);
            node.on('close', () => deviceNode.removeListener('periph-data', onData));
        }

        node.on('input', async function(msg, send, done) {
            if (!deviceNode.chip) { done(new Error('TCA9554 not initialised')); return; }
            try {
                const p = msg.payload;
                if (p && typeof p.pin === 'number' && typeof p.value === 'number') {
                    const pin = deviceNode.chip.pin(p.pin);
                    if ((deviceNode.chip._direction >> p.pin) & 1) await pin.setDirection('out');
                    await pin.write(p.value ? 1 : 0);
                    done();
                } else if (p && typeof p.pin === 'number') {
                    msg.payload = { pin: p.pin, value: ((await deviceNode.chip.readPort()) >> p.pin) & 1 };
                    send(msg);
                    done();
                } else if (p && typeof p.port === 'number') {
                    msg.payload = { port: p.port, value: await deviceNode.chip.readPort() };
                    send(msg);
                    done();
                } else {
                    done(new Error('msg.payload must be { pin, value }, { pin }, or { port }'));
                }
            } catch (e) { done(e); }
        });
    }
    RED.nodes.registerType('tca9554', Tca9554ReadNode);
};
