'use strict';

module.exports = function(RED) {
    const { I2CTransport } = require('periph/src/transport/i2c');
    const { EEPROM24AA02UIDFull } = require('periph/src/chips/memory/_24aa02uid');

    function Eeprom24AA02UIDDeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const transport = new I2CTransport(parseInt(config.bus), parseInt(config.address, 16));
            node.driver    = new EEPROM24AA02UIDFull(transport);
            node.transport = transport;
        } catch (e) {
            node.error('24AA02UID init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node.transport) node.transport.close();
        });
    }
    RED.nodes.registerType('24aa02uid-device', Eeprom24AA02UIDDeviceNode);

    function Eeprom24AA02UIDReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        node.device = RED.nodes.getNode(config.device);

        node.on('input', function(msg, send, done) {
            if (!node.device || !node.device.driver) {
                node.error('No 24AA02UID device configured', msg);
                done();
                return;
            }
            try {
                const d = node.device.driver;
                const op = (msg.op || config.op || 'read_uid');
                if (op === 'read_uid') {
                    const uid = d.readUid();
                    msg.payload = {
                        uid:     uid.toString('hex').toUpperCase(),
                        uid_int: uid.readUInt32BE(0)
                    };
                } else if (op === 'read') {
                    const address = (msg.address != null) ? msg.address : 0;
                    const length  = (msg.length  != null) ? msg.length  : 4;
                    const data = d.read(address, length);
                    msg.payload = { data: Array.from(data) };
                } else if (op === 'write') {
                    const address = (msg.address != null) ? msg.address : 0;
                    const data    = (msg.data    != null) ? msg.data    : [];
                    d.write(address, Buffer.from(data));
                    msg.payload = { ok: true };
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
    RED.nodes.registerType('24aa02uid', Eeprom24AA02UIDReadNode);
};
