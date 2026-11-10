'use strict';

module.exports = function(RED) {
    const { UARTConnection } = require('periph/src/connection/uart');
    const { TMC2209Full } = require('periph/src/chips/motor/tmc2209');

    function TMC2209Node(config) {
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
                let diagPin = null;
                if (config.diagGpio) {
                    const [chip, line] = String(config.diagGpio).split(':').map((v) => parseInt(v, 10));
                    const { Default } = require('opengpio');
                    const { PollingInputPin } = require('periph/src/connection/input_pin');
                    const diagIn = Default.input({ chip, line });
                    diagPin = new PollingInputPin(() => (diagIn.value ? 1 : 0), 1000);
                }
                node.driver = new TMC2209Full(connection, {
                    rSenseOhm: parseFloat(config.rsense) || 0.11,
                    address: parseInt(config.address, 10) || 0,
                    enPin,
                    diagPin,
                });
                await node.driver.init();
                const current = parseFloat(config.current);
                if (current > 0) await node.driver.setCurrent(current);
                const microsteps = parseInt(config.microsteps, 10);
                if (microsteps > 0) await node.driver.setMicrosteps(microsteps);
                node.ready = true;
            } catch (e) {
                node.error('TMC2209 init failed: ' + e.message);
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
                sg_result: await node.driver.readStallguard(),
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
                    if (typeof p.stall_threshold === 'number') { await node.driver.setStallThreshold(p.stall_threshold); handled = true; }
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
                        sg_result: await node.driver.readStallguard(),
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
    RED.nodes.registerType('periph-tmc2209', TMC2209Node);
};
