"use strict";

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { MX25L12845EFull } = require('periph/src/chips/memory/mx25l12845e');

    function Mx25l12845eDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.ready = null;
        try {
            const connection = new SPIConnection(parseInt(config.bus), parseInt(config.device), {
                mode: 0,
                maxSpeedHz: parseInt(config.speed) || 8000000,
            });
            node.driver     = new MX25L12845EFull(connection, { autoErase: true });
            node.connection = connection;
            node.ready      = node.driver.init().catch(function(e) {
                node.error('MX25L12845E init failed: ' + e.message);
                node.driver = null;
            });
        } catch (e) {
            node.error('MX25L12845E init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mx25l12845e-device', Mx25l12845eDeviceNode);

    function Mx25l12845eNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', async function(msg, send, done) {
            if (node.device && node.device.ready) await node.device.ready;
            if (!node.device || !node.device.driver) {
                node.error('No MX25L12845E device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const op = (msg.op || config.op || 'read');
                const address = (msg.address != null) ? msg.address : 0;
                if (op === 'read') {
                    msg.payload = await d.readBytes(address, (msg.length != null) ? msg.length : 16);
                } else if (op === 'write') {
                    const data = Buffer.isBuffer(msg.payload) ? msg.payload : Buffer.from(msg.data || []);
                    await d.writeBytes(address, data);
                    msg.payload = { ok: true };
                } else if (op === 'erase') {
                    await d.eraseSector(address);
                    msg.payload = { ok: true };
                } else if (op === 'id') {
                    msg.payload = { jedecId: (await d.jedecId()).toString('hex'), serial: (await d.serialNumber()).toString('hex') };
                } else if (op === 'status') {
                    msg.payload = { status: await d.readStatus(), protection: await d.protection(), security: await d.readSecurity(), busy: await d.isBusy() };
                } else if (op === 'info') {
                    msg.payload = d.info();
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
    RED.nodes.registerType('mx25l12845e', Mx25l12845eNode);
};
