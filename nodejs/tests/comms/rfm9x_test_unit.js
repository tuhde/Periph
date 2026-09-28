'use strict';
const { SPIConnectionMock } = require('../../packages/periph/src/connection/spi_mock');
const { OutputPin } = require('../../packages/periph/src/connection/output_pin');
const { InputPin } = require('../../packages/periph/src/connection/input_pin');
const {
    RFM95Minimal, RFM95Full, RFM96Minimal, RFM97Minimal, RFM97Full,
} = require('../../packages/periph/src/chips/comms/rfm9x');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function eq(a, b) {
    return Buffer.isBuffer(a) && Buffer.isBuffer(b) && Buffer.compare(a, b) === 0;
}

function has(conn, bytes) {
    const target = Buffer.from(bytes);
    return conn.writes.some((w) => eq(w, target));
}

class FakeOutputPin extends OutputPin {
    constructor(initial = false) { super(); this.state = initial; this.calls = []; }
    async set(high) { this.state = high; this.calls.push(high); }
}

class FakeInputPin extends InputPin {
    constructor() { super(); this._handlers = []; }
    async onEdge(callback, _trigger = 'falling') { this._handlers.push(callback); }
    async offEdge(callback) { this._handlers = this._handlers.filter((h) => h !== callback); }
    async fire() { for (const h of [...this._handlers]) await h(); }
}

const REG_VERSION = 0x42;
const EXPECTED_VERSION = 0x12;

function newConnection() {
    const c = new SPIConnectionMock();
    c.setRegister(REG_VERSION, [EXPECTED_VERSION]);
    return c;
}

async function main() {
    // --- RFM95Minimal: constructor + init(), version check, frequency, default config ---
    const connection = newConnection();
    const sensor = new RFM95Minimal(connection, 915000000);
    await sensor.init();
    checkTrue('init_frf_written', has(connection, [0x86, 0xE4]) && has(connection, [0x87, 0xC0]) && has(connection, [0x88, 0x00]));
    checkTrue('init_default_modem_config', has(connection, [0x9D, 0x72]) && has(connection, [0x9E, 0x77]));
    checkTrue('init_default_tx_power', has(connection, [0x89, 0x8F]));

    try {
        const badVersionConn = new SPIConnectionMock();
        badVersionConn.setRegister(REG_VERSION, [0x99]);
        const bad = new RFM95Minimal(badVersionConn, 915000000);
        await bad.init();
        checkTrue('init_rejects_wrong_version', false);
    } catch (e) {
        checkTrue('init_rejects_wrong_version', true);
    }

    try {
        new RFM95Minimal(connection, 433000000);  // outside RFM95's HF range
        checkTrue('init_rejects_out_of_range_frequency', false);
    } catch (e) {
        checkTrue('init_rejects_out_of_range_frequency', true);
    }

    const lfConnection = newConnection();
    const lfSensor = new RFM96Minimal(lfConnection, 433000000);
    await lfSensor.init();
    checkTrue('lf_variant_constructs', true);

    // --- send(): FIFO write, payload length, TX mode, IRQ poll+clear, back to standby ---
    connection.setRegister(0x12, [0x08]);  // IRQ_FLAGS: TX_DONE set, poll succeeds immediately
    connection.writes.length = 0;
    await sensor.send(Buffer.from([0xDE, 0xAD, 0xBE]));
    // send() calls _standby() first (writes[0]), then the FIFO/TX sequence.
    checkTrue('send_fifo_addr_ptr', eq(connection.writes[1], Buffer.from([0x8D, 0x80])));
    checkTrue('send_fifo_payload', eq(connection.writes[2], Buffer.from([0x80, 0xDE, 0xAD, 0xBE])));
    checkTrue('send_payload_length', eq(connection.writes[3], Buffer.from([0xA2, 0x03])));
    checkTrue('send_dio_mapping', eq(connection.writes[4], Buffer.from([0xC0, 0x40])));
    checkTrue('send_tx_mode', eq(connection.writes[5], Buffer.from([0x81, 0x83])));
    checkTrue('send_clears_irq', has(connection, [0x92, 0x08]));
    checkTrue('send_returns_to_standby', eq(connection.writes[connection.writes.length - 1], Buffer.from([0x81, 0x81])));

    try {
        await sensor.send(Buffer.alloc(256));
        checkTrue('send_rejects_over_255_bytes', false);
    } catch (e) {
        checkTrue('send_rejects_over_255_bytes', true);
    }

    // --- receive(): RX mode, IRQ poll, FIFO read ---
    connection.setRegister(0x12, [0x40]);
    connection.setRegister(0x10, [0x00]);
    connection.setRegister(0x13, [0x03]);
    connection.setRegister(0x00, [0xAA, 0xBB, 0xCC]);
    connection.writes.length = 0;
    const payload = await sensor.receive(100);
    checkTrue('receive_rx_mode', eq(connection.writes[2], Buffer.from([0x81, 0x86])));
    checkTrue('receive_returns_payload', eq(payload, Buffer.from([0xAA, 0xBB, 0xCC])));
    checkTrue('receive_clears_irq', has(connection, [0x92, 0x40]));

    connection.setRegister(0x12, [0x00]);
    const timeoutResult = await sensor.receive(10);
    checkTrue('receive_timeout_returns_null', timeoutResult === null);

    // --- RFM95Full: configure, setFrequency, setTxPower, standby/sleep, telemetry, reset ---
    const fullConnection = newConnection();
    const full = new RFM95Full(fullConnection, 915000000);
    await full.init();

    fullConnection.writes.length = 0;
    await full.configure(9, 125.0, 5, true);
    checkTrue('configure_detection_opt', has(fullConnection, [0xB1, 0x03]));
    checkTrue('configure_modem_config_1', has(fullConnection, [0x9D, 0x72]));
    checkTrue('configure_modem_config_2', has(fullConnection, [0x9E, 0x97]));

    // NOTE: unlike Python (ValueError) and unlike this driver's own AD7705
    // (throws for invalid params), RFM9x's Node.js configure() silently
    // clamps/defaults out-of-range params instead of rejecting them --
    // same no-exceptions-style pattern as this file's C++ port. Left as-is
    // (ambiguous design choice, not a clear regression) -- tested against
    // actual behavior rather than an assumed throw.
    fullConnection.writes.length = 0;
    await full.configure(9, 999.0, 5);  // invalid bandwidth -> silently defaults to bwCode=0x07 (125 kHz)
    checkTrue('configure_bad_bandwidth_defaults_silently', has(fullConnection, [0x9D, 0x72]));

    const rfm97Connection = newConnection();
    const rfm97 = new RFM97Full(rfm97Connection, 915000000);
    await rfm97.init();
    rfm97Connection.writes.length = 0;
    await rfm97.configure(10, 125.0, 5);  // sf=10 > maxSF=9 -> silently clamped to 9
    checkTrue('configure_clamps_sf_to_variant_max_silently', has(rfm97Connection, [0x9E, 0x97]));

    fullConnection.writes.length = 0;
    await full.setFrequency(868000000);
    checkTrue('set_frequency_writes_frf', fullConnection.writes.length === 3 &&
        eq(fullConnection.writes[0], Buffer.from([0x86, 0xD9])) &&
        eq(fullConnection.writes[1], Buffer.from([0x87, 0x00])) &&
        eq(fullConnection.writes[2], Buffer.from([0x88, 0x00])));

    fullConnection.writes.length = 0;
    await full.setTxPower(20, true);
    checkTrue('set_tx_power_high_power_dac', has(fullConnection, [0xCD, 0x87]));
    checkTrue('set_tx_power_high_power_ocp', has(fullConnection, [0x8B, 0x3B]));
    checkTrue('set_tx_power_high_power_config', has(fullConnection, [0x89, 0x8F]));

    fullConnection.writes.length = 0;
    await full.setTxPower(10, false);
    checkTrue('set_tx_power_rfo_config', has(fullConnection, [0x89, 0x7A]));

    fullConnection.writes.length = 0;
    await full.standby();
    checkTrue('standby', eq(fullConnection.writes[fullConnection.writes.length - 1], Buffer.from([0x81, 0x81])));
    fullConnection.writes.length = 0;
    await full.sleep();
    checkTrue('sleep', eq(fullConnection.writes[fullConnection.writes.length - 1], Buffer.from([0x81, 0x80])));

    checkTrue('version', (await full.version()) === 0x12);
    fullConnection.setRegister(0x1B, [100]);
    checkTrue('rssi', (await full.rssi()) === -137 + 100);
    fullConnection.setRegister(0x1A, [90]);
    checkTrue('last_packet_rssi', (await full.lastPacketRssi()) === -137 + 90);
    fullConnection.setRegister(0x19, [20]);
    checkTrue('last_packet_snr_positive', Math.abs((await full.lastPacketSnr()) - 5.0) < 1e-9);
    fullConnection.setRegister(0x19, [0xF4]);
    checkTrue('last_packet_snr_negative', Math.abs((await full.lastPacketSnr()) - (-3.0)) < 1e-9);

    fullConnection.writes.length = 0;
    await full.receiveContinuous();
    checkTrue('receive_continuous_rx_cont_mode', eq(fullConnection.writes[fullConnection.writes.length - 1], Buffer.from([0x81, 0x85])));
    fullConnection.setRegister(0x12, [0x40]);
    fullConnection.setRegister(0x10, [0x00]);
    fullConnection.setRegister(0x13, [0x02]);
    fullConnection.setRegister(0x00, [0x11, 0x22]);
    checkTrue('read_packet_returns_payload', eq(await full.readPacket(), Buffer.from([0x11, 0x22])));
    fullConnection.setRegister(0x12, [0x00]);
    checkTrue('read_packet_null_when_not_ready', (await full.readPacket()) === null);
    fullConnection.writes.length = 0;
    await full.stopReceive();
    checkTrue('stop_receive_returns_to_standby', eq(fullConnection.writes[fullConnection.writes.length - 1], Buffer.from([0x81, 0x81])));

    try {
        await full.receive(2000, true);
        checkTrue('receive_interrupt_requires_dio0_pin', false);
    } catch (e) {
        checkTrue('receive_interrupt_requires_dio0_pin', true);
    }

    const dio0 = new FakeInputPin();
    const dio0Connection = newConnection();
    dio0Connection.setRegister(0x12, [0x40]);
    dio0Connection.setRegister(0x10, [0x00]);
    dio0Connection.setRegister(0x13, [0x01]);
    dio0Connection.setRegister(0x00, [0x99]);
    const fullWithDio0 = new RFM95Full(dio0Connection, 915000000, null, dio0);
    await fullWithDio0.init();
    const interruptPromise = fullWithDio0.receive(2000, true);
    // Let the driver's three awaited register writes (standby, DIO mapping,
    // RX_SINGLE mode) finish and register the onEdge handler before firing --
    // a macrotask yield guarantees pending microtask chains have drained.
    await new Promise((r) => setTimeout(r, 0));
    await dio0.fire();  // simulate the DIO0 rising edge
    checkTrue('receive_interrupt_returns_payload', eq(await interruptPromise, Buffer.from([0x99])));

    // --- reset(): requires resetPin, pulses it low then high, re-runs init ---
    try {
        await full.reset();
        checkTrue('reset_without_pin_raises', false);
    } catch (e) {
        checkTrue('reset_without_pin_raises', true);
    }

    const resetPin = new FakeOutputPin(true);
    const resetConnection = newConnection();
    const withReset = new RFM95Full(resetConnection, 915000000, resetPin);
    await withReset.init();
    checkTrue('init_with_reset_pin_pulses', resetPin.calls.length === 2 && resetPin.calls[0] === false && resetPin.calls[1] === true);
    resetPin.calls.length = 0;
    await withReset.reset();
    checkTrue('reset_pulses_pin', resetPin.calls.length === 2 && resetPin.calls[0] === false && resetPin.calls[1] === true);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
