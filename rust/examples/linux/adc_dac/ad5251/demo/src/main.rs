// Voltage-divider level setter: steps channel 0 as a divider (A at VDD, B at
// GND) through several ratios and prints the expected output voltage at
// VDD = 3.3 V; sets channel 1 as a 20 kΩ rheostat and reports the
// quantisation error; demonstrates the quick commands/resume and reset.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::adc_dac::{AD5251Full, AD5251_I2C_ADDRESS};
use std::thread::sleep;
use std::time::Duration;

const VDD: f32 = 3.3;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut pot = AD5251Full::new(dev, AD5251_I2C_ADDRESS, 50000.0, 75.0); // Create AD5251 Full driver, (i2c, addr=0x2C, r_ab=50000.0 Ω, r_wiper=75.0 Ω)

    // --- Step channel 0 through divider ratios ---
    // With A at VDD and B at GND the wiper voltage is ratio × VDD; the code is
    // round(ratio × 64), so 0.999 lands on the top code 63.
    for ratio in [0.0f32, 0.25, 0.5, 0.75, 0.999] {
        pot.set_ratio(0, ratio).expect("set_ratio"); // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → ()
        let code = pot.get_wiper(0).expect("get_wiper"); // Read wiper position, (channel 0–1) → u8 0–63
        let actual = pot.get_ratio(0).expect("get_ratio"); // Read divider ratio, (channel 0–1) → f32
        println!("ratio {:.3} -> code {:3} ({:.4}) -> {:.3} V", ratio, code, actual, actual * VDD);
        sleep(Duration::from_millis(500));
    }

    // --- Use channel 1 as a 20 kΩ rheostat ---
    // The 64 steps quantise the resistance (~781 Ω per code on a 50 kΩ part), so
    // the readback differs slightly from the request.
    pot.set_resistance(1, 20000.0).expect("set_resistance"); // Set W–B resistance, (channel 0–1, ohms Ω) → ()
    let r = pot.get_resistance(1).expect("get_resistance"); // Read W–B resistance, (channel 0–1) → f32 Ω
    println!("rheostat {:.1} Ω, error {:.1} Ω", r, r - 20000.0);

    // --- Step and scale the wiper with the quick commands ---
    // increment/decrement move one code, double/halve change the level by 6 dB.
    pot.set_wiper(0, 16).expect("set_wiper"); // Set wiper position, (channel 0–1, code 0–63) → ()
    pot.increment(Some(0)).expect("increment"); // Quick command, (channel Option 0–1, None = both) → ()
    println!("increment -> {}", pot.get_wiper(0).expect("get_wiper"));
    pot.decrement(Some(0)).expect("decrement"); // Quick command, (channel Option 0–1, None = both) → ()
    println!("decrement -> {}", pot.get_wiper(0).expect("get_wiper"));
    pot.double(Some(0)).expect("double"); // Quick command, (channel Option 0–1, None = both) → ()
    println!("double -> {}", pot.get_wiper(0).expect("get_wiper"));
    pot.halve(Some(0)).expect("halve"); // Quick command, (channel Option 0–1, None = both) → ()
    println!("halve -> {}", pot.get_wiper(0).expect("get_wiper"));

    // --- Make the settings persistent ---
    // store() writes the wipers to EEMEM (≈ 26 ms, ACK polling); after a change,
    // restore() brings the stored values back.
    pot.store(None, &mut Delay).expect("store"); // Store wipers in EEMEM, (channel Option 0–1, delay) → ()
    pot.set_both(1, 2).expect("set_both"); // Write both wipers, (code0 0–63, code1 0–63) → ()
    pot.restore(None).expect("restore"); // Restore wipers from EEMEM, (channel Option 0–1) → ()
    println!("restored: {} {}", pot.get_wiper(0).expect("get_wiper"), pot.get_wiper(1).expect("get_wiper"));

    // --- User EEMEM bytes and the factory tolerance ---
    pot.write_eemem(4, 0x40, &mut Delay).expect("write_eemem"); // Write user EEMEM byte, (index 4–15, value, delay) → ()
    println!("eemem 4: {}", pot.read_eemem(4).expect("read_eemem")); // Read user EEMEM byte, (index 4–15) → u8
    println!("tolerance {:.3} %", pot.read_tolerance(0).expect("read_tolerance")); // Read stored RAB tolerance, (channel 0–1) → f32 %
    println!("RAB {:.1} Ω", pot.rab_actual(0).expect("rab_actual")); // Actual RAB of a channel, (channel 0–1) → f32 Ω

    // --- Restore the stored state ---
    // reset() restores both wipers from EEMEM and waits for the write cycle.
    pot.reset(&mut Delay).expect("reset"); // Reset from EEMEM, (delay) → ()
}
