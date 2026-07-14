'use strict';
const { I2CTransport } = require('../../../src/transport/i2c');
const { PCF8576Full } = require('../../../src/chips/display/pcf8576');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x38', 16);

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);

async function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

(async () => {
    // --- 4-digit countdown from 9999 to 0000 on a 1:4 multiplex 7-segment LCD ---
    // The PCF8576 drives four 7-segment digits from a single I2C bus; the host
    // encodes each digit using the chip's 1:4 multiplex bit layout (a/c/b/DP/f/e/g/d)
    // and writes all four with one writeRaw() call. The countdown runs once per
    // second and the terminal mirrors the value sent to the display.
    const lcd = new PCF8576Full(transport);                // Create PCF8576 driver, (transport)

    for (let n = 9999; n >= 0; n--) {
        const d0 = Math.floor(n / 1000) % 10;
        const d1 = Math.floor(n / 100) % 10;
        const d2 = Math.floor(n / 10) % 10;
        const d3 = n % 10;
        const out = [
            PCF8576Full.SEVEN_SEG[d0],                    // Encode 7-segment digit, (digit 0–9) → number
            PCF8576Full.SEVEN_SEG[d1],                    // Encode 7-segment digit, (digit 0–9) → number
            PCF8576Full.SEVEN_SEG[d2],                    // Encode 7-segment digit, (digit 0–9) → number
            PCF8576Full.SEVEN_SEG[d3],                    // Encode 7-segment digit, (digit 0–9) → number
        ];
        lcd.writeRaw(0, out);                             // Write all four digits, (address 0, 4 bytes) → void
        console.log(`countdown: ${String(n).padStart(4, '0')}`);
        await sleep(1000);
    }

    // --- Stop indicator: light only the middle segments (g) on every digit ---
    // When the counter reaches zero we replace the "0000" pattern with "----" to
    // signal that the demo has finished. Each digit's g segment is bit 1, so a
    // 0x02 byte lights just the bar across the middle.
    const dash = [0x02, 0x02, 0x02, 0x02];
    lcd.writeRaw(0, dash);                                // Write dash pattern, (address 0, 4 bytes) → void
    console.log('countdown complete');

    transport.close();
    console.log('===DONE: 0 passed, 0 failed===');
})();
