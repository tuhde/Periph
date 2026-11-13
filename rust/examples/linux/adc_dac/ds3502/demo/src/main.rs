// Demo: a VCOM-style voltage setter. With RH = 15.0 V and RL = 0 V (V+ = 15.5 V)
// prints the wiper loaded from the IVR at power-up, sets 6.0 V, sweeps the
// wiper in 16 steps, steps up and down, shows the MODE bit and finally
// persists the chosen code in the EEPROM (consuming one EEPROM write).

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{DS3502Full, DS3502_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = DS3502Full::new(dev, DS3502_I2C_ADDRESS, 15.0, 0.0); // Create DS3502 Full driver, (i2c, addr=0x28, v_high=1.0 V, v_low=0.0 V)
    pot.init(&mut Delay).expect("init");                               // Run initialization sequence, (delay) → ()

    // --- Power-up position ---
    // init leaves the wiper where the IVR put it (factory default 0x40).
    let up = pot.get_wiper().expect("get_wiper"); // Read wiper position, () → u8 0–127
    println!("power-up wiper {} -> {:.3} V", up, pot.get_voltage().expect("V"));

    // --- Set 6.0 V ---
    pot.set_voltage(6.0).expect("set_voltage"); // Set RW output voltage, (volts V) → ()
    let ratio = pot.get_ratio().expect("ratio"); // Read divider ratio, () → f32
    println!("code {} ratio {:.3} voltage {:.3} V", pot.get_wiper().expect("get"), ratio, pot.get_voltage().expect("V"));

    // --- Sweep in 16 steps ---
    // The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
    for code in (0..=127).step_by(8) {
        pot.set_wiper(code).expect("set_wiper"); // Set wiper position, (code 0–127) → ()
        println!("code {:3} -> {:.3} V", code, pot.get_voltage().expect("V")); // Read unloaded RW voltage, () → f32 V
        sleep(Duration::from_millis(200));
    }

    // --- Step up and down ---
    pot.set_voltage(6.0).expect("set_voltage");
    println!("+4 -> {}", pot.increment(4).expect("increment")); // Step the wiper, (steps) → u8 0–127
    println!("-4 -> {}", pot.increment(-4).expect("increment"));

    // --- MODE ---
    // MODE = 1 (the working mode) keeps wiper writes in SRAM; store_wiper switches
    // to MODE = 0 only for the duration of the EEPROM write.
    println!("eeprom mode {}", pot.get_mode().expect("get_mode")); // Read MODE, () → bool
    pot.set_mode(false).expect("set_mode"); // Select SRAM-only writes, (eeprom bool) → ()

    // --- Persist the chosen code ---
    // This consumes one of the EEPROM's limited write cycles.
    let code = pot.get_wiper().expect("get_wiper");
    pot.store_wiper(&mut Delay, Some(code)).expect("store_wiper"); // Store power-up position, (delay, code Option<u8>) → ()
    pot.wait_ready(&mut Delay, 30).expect("wait_ready"); // Acknowledge polling, (delay, timeout_ms) → ()
    println!("read back {}", pot.get_wiper().expect("get_wiper"));
}
