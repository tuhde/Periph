'use strict';

module.exports = function(RED) {
    const { UARTConnection } = require('periph/src/connection/uart');
    const { TMC2208Full } = require('periph/src/chips/motor/tmc2208');

    function TMC2208Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = false;

        (async () => {
            try {
                const connection = new UARTConnection(config.port, {
                    baudRate: parseInt(config.baudrate, 10) || 115200,
                    timeoutMs: 100,
                });
                await connection.open();
                node.connection = connection;
                let enPin = null;
                if (config.enGpio) {
                    // "<chip>:<line>", e.g. "0:17" -- gpiod chip number and line offset
                    const [chip, line] = String(config.enGpio).split(':').map((v) => parseInt(v, 10));
                    const { Default } = require('opengpio');
                    const { GpioOutputPin } = require('periph/src/connection/output_pin');
                    enPin = new GpioOutputPin(Default.output({ chip, line }));
                }
                node.enPin = enPin;
                node.driver = new TMC2208Full(connection, {
                    rSenseOhm: parseFloat(config.rsense) || 0.11,
                    enPin,
                });
                await node.driver.init();
                const current = parseFloat(config.current);
                if (current > 0) await node.driver.setCurrent(current);
                const microsteps = parseInt(config.microsteps, 10);
                if (microsteps > 0) await node.driver.setMicrosteps(microsteps);
                node.ready = true;
            } catch (e) {
                node.error('TMC2208 init failed: ' + e.message);
            }
        })();

        async function snapshot(extra) {
            const status = await node.driver.readStatus();
            const fault = await node.driver.readFault();
            return Object.assign({
                standstill: status.stst,
                stealth: status.stealth,
                fault,
                cs_actual: status.cs_actual,
            }, extra);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.driver || !node.ready) { done(); return; }
            try {
                const p = msg.payload;
                let handled = false;
                let extra = {};
                if (p && typeof p === 'object') {
                    if (typeof p.microsteps === 'number') { await node.driver.setMicrosteps(p.microsteps); handled = true; }
                    if (typeof p.current === 'number') { await node.driver.setCurrent(p.current); handled = true; }
                    if (typeof p.enable === 'boolean') {
                        if (p.enable) await node.driver.enable(); else await node.driver.disable();
                        handled = true;
                    }
                    if (p.stop === true) { await node.driver.stop(); extra.speed = 0; handled = true; }
                    if (typeof p.speed === 'number') { await node.driver.run(p.speed); extra.speed = p.speed; handled = true; }
                }
                if (handled) {
                    msg.payload = await snapshot(extra);
                } else {
                    const status = await node.driver.readStatus();
                    msg.payload = {
                        standstill: status.stst,
                        stealth: status.stealth,
                        fault: await node.driver.readFault(),
                        cs_actual: status.cs_actual,
                        tstep: await node.driver.readTstep(),
                        temperature_flags: await node.driver.readTemperatureFlags(),
                    };
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
                // port may already be gone; nothing more to do on close
            } finally {
                done();
            }
        });
    }
    RED.nodes.registerType('periph-tmc2208', TMC2208Node);
};
