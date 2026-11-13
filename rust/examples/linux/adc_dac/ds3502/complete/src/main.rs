// Complete example: exercise every method in the public API.
// Sets the wiper by code, voltage and ratio, reads it back, steps with
// increment, inspects the MODE bit, stores the power-up position in the
// EEPROM and changes the endpoint voltages.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{DS3502Full, DS3502_I2C_ADDRESS, Ds3502Terminal};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = DS3502Full::new(dev, DS3502_I2C_ADDRESS, 15.0, 0.0); // Create DS3502 Full driver, (i2c, addr=0x28, v_high=1.0 V, v_low=0.0 V)
    pot.init(&mut Delay).expect("init");                               // Run initialization sequence, (delay) → ()
    // writes CR = 0x80 (MODE = 1, SRAM-only) and caches WR

    pot.set_wiper(100).expect("set_wiper"); // Set wiper position, (code 0–127) → ()
    // writes WR only; the EEPROM is not touched
    println!("wiper {}", pot.get_wiper().expect("get_wiper")); // Read wiper position, () → u8 0–127
    // reads WR; always the live wiper, never the IVR
    pot.set_voltage(6.0).expect("set_voltage"); // Set RW output voltage, (volts V) → ()
    // nearest code of V = V_RL + (V_RH − V_RL)·code/127
    println!("voltage {}", pot.get_voltage().expect("get_voltage")); // Read unloaded RW voltage, () → f32 V

    pot.set_ratio(0.25).expect("set_ratio"); // Set divider ratio, (ratio 0.0–1.0) → ()
    // code = round(ratio × 127)
    println!("ratio {}", pot.get_ratio().expect("get_ratio")); // Read divider ratio, () → f32
    println!("R_WL {}", pot.get_resistance(Ds3502Terminal::Low).expect("RL")); // Read wiper-to-RL resistance, (terminal) → f32 Ω
    // nominal, RTOTAL = 10 kΩ ±20 %
    println!("R_WH {}", pot.get_resistance(Ds3502Terminal::High).expect("RH")); // Read wiper-to-RH resistance, (terminal) → f32 Ω
    println!("incremented to {}", pot.increment(5).expect("increment")); // Step the wiper, (steps) → u8 0–127
    // read, add, clamp, write; returns the new code

    println!("eeprom mode {}", pot.get_mode().expect("get_mode")); // Read MODE, () → bool
    // true if writes to 0x00 also program the EEPROM
    pot.set_mode(false).expect("set_mode"); // Select SRAM-only writes, (eeprom bool) → ()
    // CR = 0x80; true would write CR = 0x00

    pot.store_wiper(&mut Delay, Some(64)).expect("store_wiper"); // Store power-up position, (delay, code Option<u8>) → ()
    // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
    pot.wait_ready(&mut Delay, 30).expect("wait_ready"); // Acknowledge polling, (delay, timeout_ms) → ()
    // Err(Timeout) if the chip keeps NACKing

    pot.set_endpoints(12.0, 2.0); // Change RH / RL voltages, (v_high V, v_low V) → ()
    // affects the conversions only; no I²C traffic
}
