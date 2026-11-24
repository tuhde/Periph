//! Demo for the HTS221 — calibrated hygrometer: dew point, data rates, averaging noise, heater and DRDY.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::humidity::HTS221Full;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5F);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create HTS221 driver, (i2c, addr=0x5F) → HTS221
    let mut chip = HTS221Full::new(dev, addr);
    // Read the calibration and start one-shot mode, () → Result<()>
    chip.init().expect("init");

    // --- Identify the chip and show the factory calibration ---
    // Every HTS221 stores its own two-point calibration; init() read it and every conversion uses it.
    // Read WHO_AM_I, () → Result<u8>
    println!("who am i 0x{:02X}", chip.who_am_i().expect("who_am_i"));
    // Read calibration, () → Calibration
    println!("calibration {:?}", chip.read_calibration());

    // --- One-shot hygrometer: 10 readings, 2 s apart ---
    // Each read starts a conversion; the dew point shows what the pair means for condensation risk.
    for _ in 0..10 {
        // Read temperature and humidity, (delay) → Result<(f32, f32)>
        let (t, rh) = chip.read(&mut delay).expect("read");
        // Dew point, (delay) → Result<f32> °C
        let dew = chip.dew_point(&mut delay).expect("dew_point");
        println!("{:.2} C  {:.1} %rH  dew {:.2} C", t, rh, dew);
        // Read raw outputs, (delay) → Result<i16>
        println!("raw {} {}", chip.read_raw_humidity(&mut delay).expect("raw h"), chip.read_raw_temperature(&mut delay).expect("raw t"));
        // Read single values, (delay) → Result<f32>
        println!("h {:.1} t {:.2}", chip.read_humidity(&mut delay).expect("h"), chip.read_temperature(&mut delay).expect("t"));
        sleep_ms!(2000);
    }

    // --- Continuous sampling at 1 Hz and 12.5 Hz ---
    // data_ready() paces the loop; the sample count per second follows the rate.
    // Set block data update, (enabled) → Result<()>
    chip.set_block_data_update(true).expect("bdu");
    for hz in [1.0f32, 12.5] {
        // Set output data rate, (hz 0|1|7|12.5) → Result<()>
        chip.set_output_data_rate(hz).expect("odr");
        // Get output data rate, () → Result<f32> Hz
        println!("rate {} Hz", chip.get_output_data_rate().expect("get odr"));
        let mut count = 0u32;
        for _ in 0..600 {
            // Check data available, () → Result<bool>
            if chip.data_ready().expect("data_ready") {
                // Read latest sample, () → Result<(f32, f32)>
                let _ = chip.read_continuous().expect("read_continuous");
                count += 1;
            }
            sleep_ms!(5);
        }
        println!("{:.1} samples/s", count as f32 / 3.0);
    }
    // Set output data rate, (hz 0|1|7|12.5) → Result<()>
    chip.set_output_data_rate(0.0).expect("odr");

    // --- Noise at lowest and highest averaging ---
    // Ten one-shot samples each; the spread of the raw humidity shrinks as the averaging grows.
    for (ta, ha) in [(2u16, 4u16), (256, 512)] {
        // Set averaging, (temperature_samples, humidity_samples) → Result<()>
        chip.set_averaging(ta, ha).expect("averaging");
        // Get averaging, () → Result<(u16, u16)>
        println!("averaging {:?}", chip.get_averaging().expect("get averaging"));
        let mut lo = i16::MAX;
        let mut hi = i16::MIN;
        for _ in 0..10 {
            // Read raw humidity, (delay) → Result<i16>
            let raw = chip.read_raw_humidity(&mut delay).expect("raw");
            if raw < lo { lo = raw; }
            if raw > hi { hi = raw; }
        }
        println!("raw humidity spread {}", hi as i32 - lo as i32);
    }
    // Set averaging, (temperature_samples, humidity_samples) → Result<()>
    chip.set_averaging(16, 32).expect("averaging");

    // --- Heater: drive off condensation for 10 s ---
    // Humidity falls and temperature rises while the heater runs; measure before and after, never during.
    // Read temperature and humidity, (delay) → Result<(f32, f32)>
    let before = chip.read(&mut delay).expect("before");
    // Switch the heater, (on) → Result<()>
    chip.set_heater(true).expect("heater on");
    // Heater state, () → Result<bool>
    println!("heater on {}", chip.is_heater_on().expect("is_heater_on"));
    sleep_ms!(10000);
    chip.set_heater(false).expect("heater off");
    let after = chip.read(&mut delay).expect("after");
    println!("before {:?} after {:?}", before, after);

    // --- Data-ready flag for 5 s ---
    // The Rust driver exposes poll_interrupt(); wire it into an ISR or poll it as here.
    // Configure DRDY pin, (enabled, active_high, open_drain) → Result<()>
    chip.configure_drdy(true, true, false).expect("drdy");
    chip.set_output_data_rate(1.0).expect("odr");
    for _ in 0..5 {
        // Poll interrupt status, () → Result<u8>
        println!("DRDY status 0x{:02X}", chip.poll_interrupt().expect("poll"));
        sleep_ms!(1000);
    }
    chip.configure_drdy(false, true, false).expect("drdy off");
    chip.set_output_data_rate(0.0).expect("odr");

    // --- Power cycle and reload ---
    // Power down, () → Result<()>
    chip.power_down().expect("power_down");
    // Power up, () → Result<()>
    chip.power_up().expect("power_up");
    // Reload trimming, (delay) → Result<()>
    chip.reboot(&mut delay).expect("reboot");
    let (t, _) = chip.read(&mut delay).expect("read");
    println!("done: {:.2} C", t);
}
