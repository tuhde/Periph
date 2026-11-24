//! Complete example for the Si1132 — every Si1132Full method once.

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
    // writes HW_KEY, UV coefficients and CHLIST
    chip.init(&mut delay).expect("init");

    // Read UV index and ALS counts, (delay) → Result<Reading>
    // forced measurement of the enabled channels
    let r = chip.read(&mut delay).expect("read");
    println!("UV {:.2}, visible {}, infrared {}", r.uv_index, r.visible, r.infrared);
    // Read UV index, (delay) → Result<f32>
    // forced measurement, AUX_DATA / 100
    println!("uv index {:.2}", chip.read_uv_index(&mut delay).expect("uv"));
    // Read visible counts, (delay) → Result<u16>
    println!("visible {}", chip.read_visible(&mut delay).expect("visible"));
    // Read infrared counts, (delay) → Result<u16>
    println!("infrared {}", chip.read_infrared(&mut delay).expect("infrared"));
    // Read data registers, () → Result<(u16 visible, u16 infrared, u16 aux)>
    // latest values, no measurement is started
    println!("raw {:?}", chip.read_raw().expect("raw"));
    // Read die temperature, (delay) → Result<u16>
    // auxiliary channel, relative only
    println!("temperature raw {}", chip.read_temperature_raw(&mut delay).expect("temperature"));
    // Read supply voltage, (delay) → Result<u16>
    // auxiliary channel with AUX_ADCMUX = 0x75
    println!("vdd raw {}", chip.read_vdd_raw(&mut delay).expect("vdd"));

    // Select channels, (delay, uv, visible, infrared, aux) → Result<()>
    // writes CHLIST; uv and aux cannot be combined
    chip.set_channels(&mut delay, true, true, true, false).expect("channels");
    // Set ADC gain, (delay, visible 0-7, infrared 0-7) → Result<()>
    // integration time x 2^n plus the recommended recovery count
    chip.set_gain(&mut delay, 2, 2).expect("gain");
    chip.set_gain(&mut delay, 0, 0).expect("gain");
    // Set high signal range, (delay, visible, infrared) → Result<()>
    // true divides the sensitivity by 14.5 (direct sunlight)
    chip.set_high_range(&mut delay, false, false).expect("range");
    // Set data alignment, (delay, visible_low, infrared_low) → Result<()>
    // true reports the least significant 16 bits
    chip.set_alignment(&mut delay, true, true).expect("alignment");
    // Set UV coefficients, (ucoef [u8; 4]) → Result<()>
    // datasheet defaults for an open-air UV index
    chip.set_uv_coefficients([0x7B, 0x6B, 0x01, 0x00]).expect("ucoef");
    // Read factory calibration, (delay) → Result<[u8; 12]>
    // GET_CAL then registers 0x22-0x2D
    println!("calibration {:?}", chip.read_calibration(&mut delay).expect("calibration"));

    // Start a forced measurement, (delay) → Result<()>
    // ALS_FORCE without waiting
    chip.force(&mut delay).expect("force");
    sleep_ms!(50);
    // Check measurement done, () → Result<bool>
    // IRQ_STATUS.ALS_INT
    println!("ready {}", chip.measurement_ready().expect("ready"));
    // Read command response, () → Result<(u8 counter, u8 error)>
    // RESPONSE decoded, error 0 = none
    println!("response {:?}", chip.read_response().expect("response"));
    // Poll interrupt status, () → Result<u8>
    // reads IRQ_STATUS and clears it
    println!("poll {}", chip.poll_interrupt().expect("poll"));

    // Start autonomous mode, (delay, interval_s 0-2.05) → Result<()>
    // MEAS_RATE in 31.25 us steps then ALS_AUTO
    chip.start_autonomous(&mut delay, 0.5).expect("autonomous");
    // Enable the INT pin, () → Result<()>
    // INT_OE and ALS_IE
    chip.enable_interrupt().expect("enable interrupt");
    sleep_ms!(1000);
    // Disable the INT pin, () → Result<()>
    chip.disable_interrupt().expect("disable interrupt");
    // Pause autonomous mode, (delay) → Result<()>
    // ALS_PAUSE
    chip.pause(&mut delay).expect("pause");
    // Set I2C address, (delay, address 7-bit) → Result<()>
    // I2C_ADDR parameter then BUSADDR, volatile
    chip.set_i2c_address(&mut delay, 0x60).expect("address");
    // Reset the chip, (delay) → Result<()>
    // RESET, waits, writes HW_KEY again
    chip.reset(&mut delay).expect("reset");
    println!("done");
    loop { sleep_ms!(1000); }
}
