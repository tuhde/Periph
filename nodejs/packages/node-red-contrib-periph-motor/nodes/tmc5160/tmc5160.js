'use strict';

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { UARTConnection } = require('periph/src/connection/uart');
    const { TMC5160Full } = require('periph/src/chips/motor/tmc5160');

    function gpio(spec, input) {
        // "<chip>:<line>", e.g. "0:17" -- gpiod chip number and line offset
        const [chip, line] = String(spec).split(':').map((v) => parseInt(v, 10));
        const { Default } = require('opengpio');
        if (input) {
            const { PollingInputPin } = require('periph/src/connection/input_pin');
            const pin = Default.input({ chip, line });
            return new PollingInputPin(() => (pin.value ? 1 : 0), 1000);
        }
        const { GpioOutputPin } = require('periph/src/connection/output_pin');
        return new GpioOutputPin(Default.output({ chip, line }));
    }

    function TMC5160Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = false;
        node.speed = parseFloat(config.speed) || 200;

        (async () => {
            try {
                const uart = config.bus === 'uart';
                let connection;
                if (uart) {
                    connection = new UARTConnection(config.port, {
                        baudRate: parseInt(config.baudrate, 10) || 115200, timeoutMs: 100,
                    });
                    await connection.open();
                } else {
                    connection = new SPIConnection(parseInt(config.spiBus, 10) || 0, parseInt(config.spiDevice, 10) || 0, {
                        mode: 3, maxSpeedHz: 1000000,
                    });
                }
                node.connection = connection;
                node.driver = new TMC5160Full(connection, {
                    busType: uart ? 'uart' : 'spi',
                    rSenseOhm: parseFloat(config.rsense) || 0.075,
                    fclkHz: parseInt(config.fclk, 10) || 12000000,
                    enPin: config.enGpio ? gpio(config.enGpio, false) : null,
                    address: parseInt(config.address, 10) || 0,
                    echo: config.echo !== false,
                    diag0Pin: !uart && config.diag0Gpio ? gpio(config.diag0Gpio, true) : null,
                    diag1Pin: !uart && config.diag1Gpio ? gpio(config.diag1Gpio, true) : null,
                });
                await node.driver.init();
                const current = parseFloat(config.current);
                if (current > 0) await node.driver.setCurrent(current);
                await node.driver.setSpeed(node.speed);
                const accel = parseFloat(config.acceleration);
                if (accel > 0) await node.driver.setAcceleration(accel);
                node.ready = true;
            } catch (e) {
                node.error('TMC5160 init failed: ' + e.message);
            }
        })();

        async function snapshot() {
            const status = await node.driver.readStatus();
            return {
                position: await node.driver.position(),
                velocity: await node.driver.velocity(),
                position_reached: await node.driver.isPositionReached(),
                standstill: status.stst,
                fault: await node.driver.readFault(),
                cs_actual: status.cs_actual,
                sg_result: status.sg_result,
            };
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver || !node.ready) { done(); return; }
            try {
                const p = msg.payload;
                let handled = false;
                if (p && typeof p === 'object') {
                    if (typeof p.current === 'number') { await node.driver.setCurrent(p.current); handled = true; }
                    if (typeof p.acceleration === 'number') { await node.driver.setAcceleration(p.acceleration); handled = true; }
                    if (typeof p.stall_threshold === 'number') { await node.driver.setStallThreshold(p.stall_threshold); handled = true; }
                    if (p.zero === true) { await node.driver.setPosition(0); handled = true; }
                    if (typeof p.enable === 'boolean') {
                        if (p.enable) await node.driver.enable(); else await node.driver.disable();
                        handled = true;
                    }
                    if (typeof p.speed === 'number' && typeof p.move !== 'number') {
                        if (p.speed === 0) await node.driver.stop(); else await node.driver.run(p.speed);
                        handled = true;
                    }
                    if (p.stop === true) { await node.driver.stop(); handled = true; }
                    if (typeof p.move_to === 'number') { await node.driver.moveTo(p.move_to); handled = true; }
                    if (typeof p.move === 'number') {
                        await node.driver.move(p.move, typeof p.speed === 'number' ? p.speed : node.speed);
                        handled = true;
                    }
                }
                if (handled) {
                    msg.payload = await snapshot();
                } else {
                    const snap = await snapshot();
                    const status = await node.driver.readStatus();
                    msg.payload = Object.assign(snap, {
                        tstep: await node.driver.readTstep(),
                        stallguard: status.stallguard,
                        otpw: status.otpw,
                        ot: status.ot,
                    });
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });

        node.on('close', async function(removed, done) {
            try {
                if (node.driver && node.ready) {
                    await node.driver.stop();
                    await node.driver.disable();
                }
                if (node.connection) await node.connection.close();
            } catch (e) {
                // bus may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-tmc5160', TMC5160Node);
};
