'use strict';

module.exports = function(RED) {
    const opengpio = require('opengpio');
    const { SiPoConnection } = require('periph/src/connection/sipo');
    const { Tpic6b595Full }   = require('periph/src/chips/io_expander/tpic6b595');

    function Tpic6b595Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const rck   = new opengpio.Output(parseInt(config.rckPin,  10));
            const srclr = (config.srclrPin !== undefined && config.srclrPin !== '' && parseInt(config.srclrPin, 10) >= 0)
                            ? new opengpio.Output(parseInt(config.srclrPin, 10)) : null;
            const g     = (config.gPin !== undefined && config.gPin !== '' && parseInt(config.gPin, 10) >= 0)
                            ? new opengpio.Output(parseInt(config.gPin, 10))     : null;
            const serIn = config.serInPin ? new opengpio.Output(parseInt(config.serInPin, 10)) : null;
            const srck  = config.srckPin  ? new opengpio.Output(parseInt(config.srckPin,  10)) : null;

            const opts = { srclr, g };
            if (serIn && srck) { opts.serIn = serIn; opts.srck = srck; }
            const connection = new SiPoConnection(rck, opts);
            node.connection  = connection;
            node.chip        = new Tpic6b595Full(connection, parseInt(config.numDevices, 10) || 1);
        } catch (e) {
            node.error('TPIC6B595 init failed: ' + e.message);
        }

        node.on('input', async function(msg, send, done) {
            if (!node.chip) { done(new Error('TPIC6B595 not initialised')); return; }
            try {
                const p = msg.payload;
                if (p && typeof p.pin === 'number' && typeof p.value === 'number') {
                    await node.chip.pin(p.pin).write(p.value ? 1 : 0);
                    done();
                } else if (p && typeof p.port === 'number' && typeof p.value === 'number') {
                    await node.chip.writePort(p.port, p.value & 0xFF);
                    done();
                } else if (p && p.command === 'off') {
                    await node.chip.off();
                    done();
                } else if (p && typeof p.outputEnable === 'boolean') {
                    node.chip.setOutputEnable(p.outputEnable);
                    done();
                } else if (p && Array.isArray(p.values)) {
                    node.chip.writeAll(p.values);
                    done();
                } else {
                    done(new Error('msg.payload must be {pin,value}, {port,value}, {command:"off"}, {outputEnable:true|false}, or {values:[...]}.'));
                }
            } catch (e) { done(e); }
        });

        node.on('close', async function() {
            if (node.connection) await node.connection.close();
        });
    }

    RED.nodes.registerType('periph-tpic6b595', Tpic6b595Node);
};
