"use strict";

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { SRAM23X1024Full } = require('periph/src/chips/memory/_23x1024');

    const MODES = { byte: SRAM23X1024Full.MODE_BYTE, page: SRAM23X1024Full.MODE_PAGE, sequential: SRAM23X1024Full.MODE_SEQUENTIAL };
    const MODE_NAMES = { [SRAM23X1024Full.MODE_BYTE]: 'byte', [SRAM23X1024Full.MODE_PAGE]: 'page', [SRAM23X1024Full.MODE_SEQUENTIAL]: 'sequential' };

    function Sram23X1024DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = null;
        try {
            const connection = new SPIConnection(parseInt(config.bus), parseInt(config.device), {
                mode: 0,
                maxSpeedHz: parseInt(config.speed) || 1000000,
            });
            node.driver     = new SRAM23X1024Full(connection);
            node.connection = connection;
            node.ready      = node.driver.init().catch(function(e) {
                node.error('23X1024 init failed: ' + e.message);
                node.driver = null;
            });
        } catch (e) {
            node.error('23X1024 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('23x1024-device', Sram23X1024DeviceNode);

    function Sram23X1024Node(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (node.device && node.device.ready) await node.device.ready;
            if (!node.device || !node.device.driver) {
                node.error('No 23X1024 device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const op = (msg.op || config.op || 'read');
                if (op === 'read') {
                    const address = (msg.address != null) ? msg.address : 0;
                    const length  = (msg.length  != null) ? msg.length  : 4;
                    const data = await d.read(address, length);
                    msg.payload = { data: Array.from(data) };
                } else if (op === 'write') {
                    const address = (msg.address != null) ? msg.address : 0;
                    const data    = (msg.data    != null) ? msg.data    : [];
                    await d.write(address, Buffer.from(data));
                    msg.payload = { ok: true };
                } else if (op === 'fill') {
                    const address = (msg.address != null) ? msg.address : 0;
                    const length  = (msg.length  != null) ? msg.length  : 0;
                    const value   = (msg.value   != null) ? msg.value   : 0;
                    await d.fill(address, length, value);
                    msg.payload = { ok: true };
                } else if (op === 'mode') {
                    if (msg.mode != null) {
                        if (!(msg.mode in MODES)) throw new Error('Unknown mode: ' + msg.mode);
                        await d.setMode(MODES[msg.mode]);
                    }
                    msg.payload = { mode: MODE_NAMES[await d.getMode()] };
                } else {
                    node.error('Unknown op: ' + op, msg);
                    done();
                    return;
                }
                send(msg);
                done();
            } catch (e) {
                done(e);
            }
        });
    }
    RED.nodes.registerType('23x1024', Sram23X1024Node);
};
