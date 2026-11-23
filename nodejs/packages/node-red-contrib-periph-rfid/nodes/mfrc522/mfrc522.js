'use strict';

module.exports = function(RED) {
    const { SPIConnection } = require('periph/src/connection/spi');
    const { MFRC522Full }   = require('periph/src/chips/rfid/mfrc522');

    function MFRC522DeviceNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        try {
            const connection = new SPIConnection(
                parseInt(config.bus, 10),
                parseInt(config.device, 10),
                { maxSpeedHz: parseInt(config.speed, 10) || 1000000, mode: 0 }
            );
            node.driver     = new MFRC522Full(connection);
            node.connection = connection;
            node.pollMs    = parseInt(config.pollInterval, 10) || 500;
            node._timer    = null;
            node._lastUid  = null;

            const tick = async function() {
                if (!node.driver) return;
                try {
                    const present = await node.driver.isCardPresent();
                    let uid = null;
                    if (present) {
                        const buf = await node.driver.readUid();
                        if (buf) uid = buf.toString('hex');
                    }
                    if (uid !== node._lastUid) {
                        node._lastUid = uid;
                        const msg = { payload: { present: present, uid: uid } };
                        node.emit('periph-data', msg);
                    }
                } catch (e) {
                    node.error('MFRC522 poll failed: ' + e.message);
                }
            };

            node._timer = setInterval(tick, node.pollMs);
        } catch (e) {
            node.error('MFRC522 init failed: ' + e.message);
        }
        node.on('close', function() {
            if (node._timer) { clearInterval(node._timer); node._timer = null; }
            if (node.connection) node.connection.close();
        });
    }
    RED.nodes.registerType('mfrc522-device', MFRC522DeviceNode);

    // Reader node: forwards each card-present / card-removed event polled by the device node.
    function MFRC522ReadNode(config) {
        RED.nodes.createNode(this, config);
        const node = this;
        const deviceNode = RED.nodes.getNode(config.device);
        if (!deviceNode) {
            node.error('No MFRC522 device configured');
            return;
        }
        const onData = (msg) => node.send(msg);
        deviceNode.on('periph-data', onData);
        node.on('close', () => deviceNode.removeListener('periph-data', onData));
    }
    RED.nodes.registerType('mfrc522', MFRC522ReadNode);
};
