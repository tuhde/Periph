//! Demo for the Si1132 — calibrated hygrometer: dew point, data rates, averaging noise, heater and DRDY.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::light::Si1132Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x60);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create Si1132 driver, (i2c, addr=0x60) → Si1132
    let mut chip = Si1132Full::new(dev, addr);
    // Verify PART_ID and start forced mode, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identify the chip and show the factory calibration ---
    // The calibration bytes belong to the Silicon Labs Si114x calibration
    // structure; they let you correct part-to-part variation of the UV index.
    // Read factory calibration, (delay) → Result<[u8; 12]>
    println!("calibration {:?}", chip.read_calibration(&mut delay).expect("calibration"));

    // --- UV and light monitor: 20 readings, 1 s apart ---
    // Index, visible and infrared counts side by side show whether the
    // light is sunlight (high infrared share) or artificial.
    for _ in 0..20 {
        // Read UV index and ALS counts, (delay) → Result<Reading>
        let r = chip.read(&mut delay).expect("read");
        println!("UV {:.2}  vis {}  ir {}", r.uv_index, r.visible, r.infrared);
        // Read data registers, () → Result<(u16, u16, u16)>
        println!("raw {:?}", chip.read_raw().expect("raw"));
        sleep_ms!(1000);
    }

    // --- Effect of gain and of the high-range bits on the same light ---
    // Each gain step doubles the integration time (and the count); the high
    // range divides the sensitivity by 14.5 for direct sunlight.
    for gain in [0u8, 2, 4] {
        // Set ADC gain, (delay, visible 0-7, infrared 0-7) → Result<()>
        chip.set_gain(&mut delay, gain, gain).expect("gain");
        let r = chip.read(&mut delay).expect("read");
        println!("gain {} visible {} infrared {}", gain, r.visible, r.infrared);
    }
    chip.set_gain(&mut delay, 0, 0).expect("gain");
    // Set high signal range, (delay, visible, infrared) → Result<()>
    chip.set_high_range(&mut delay, true, true).expect("range");
    let r = chip.read(&mut delay).expect("read");
    println!("high range visible {} infrared {}", r.visible, r.infrared);
    chip.set_high_range(&mut delay, false, false).expect("range");
    // Set data alignment, (delay, visible_low, infrared_low) → Result<()>
    chip.set_alignment(&mut delay, false, false).expect("alignment");
    // Set UV coefficients, (ucoef [u8; 4]) → Result<()>
    chip.set_uv_coefficients([0x7B, 0x6B, 0x01, 0x00]).expect("ucoef");

    // --- Auxiliary channels ---
    // The temperature value is relative only; the VDD count tracks the supply.
    // Read die temperature, (delay) → Result<u16>
    println!("temperature raw {}", chip.read_temperature_raw(&mut delay).expect("temperature"));
    // Read supply voltage, (delay) → Result<u16>
    println!("vdd raw {}", chip.read_vdd_raw(&mut delay).expect("vdd"));

    // --- Autonomous mode at 0.5 s with the interrupt ---
    // Every completed measurement raises INT; poll_interrupt() reports and
    // clears the status.
    // Start autonomous mode, (delay, interval_s 0-2.05) → Result<()>
    chip.start_autonomous(&mut delay, 0.5).expect("autonomous");
    // Enable the INT pin, () → Result<()>
    chip.enable_interrupt().expect("enable interrupt");
    for _ in 0..10 {
        sleep_ms!(500);
        // Poll interrupt status, () → Result<u8>
        let st = chip.poll_interrupt().expect("poll");
        if st != 0 {
            // Read data registers, () → Result<(u16, u16, u16)>
            let (vis, _ir, _aux) = chip.read_raw().expect("raw");
            println!("INT 0x{:02X} visible {}", st, vis);
        }
    }
    // Disable the INT pin, () → Result<()>
    chip.disable_interrupt().expect("disable interrupt");
    // Pause autonomous mode, (delay) → Result<()>
    chip.pause(&mut delay).expect("pause");

    // --- UV only, then shut down ---
    // Dropping the ALS channels shortens each measurement.
    // Select channels, (delay, uv, visible, infrared, aux) → Result<()>
    chip.set_channels(&mut delay, true, false, false, false).expect("channels");
    // Read UV index, (delay) → Result<f32>
    println!("UV only {:.2}", chip.read_uv_index(&mut delay).expect("uv"));
    // Set I2C address, (delay, address 7-bit) → Result<()>
    chip.set_i2c_address(&mut delay, 0x60).expect("address");
    // Reset the chip, (delay) → Result<()>
    chip.reset(&mut delay).expect("reset");
    println!("done");
    loop { sleep_ms!(1000); }
}
