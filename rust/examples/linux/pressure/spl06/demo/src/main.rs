//! Demo for the SPL06-007 — barometric altimeter and variometer.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::pressure::spl06::SOURCE_PRESSURE;
use periph::chips::pressure::{Spl06FifoEntry, Spl06Full, Spl06Kind};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x77);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create SPL06 driver, (i2c, addr=0x77) → Spl06Full
    let mut chip = Spl06Full::new(dev, addr);

    // --- Identity and calibration ---
    // init() checks ID = 0x10; the nine coefficients are factory-trimmed per
    // part and drive every compensated value below.
    // Check ID, read coefficients, apply defaults, (delay) → Result<()>
    chip.init(&mut delay).expect("init");
    // Decoded calibration, () → Spl06Coefficients
    println!("ID 0x10 verified {:?}", chip.coefficients());

    // --- Altimeter: 20 s at 1 Hz, altitude relative to the first reading ---
    // The first pressure becomes the reference level, so the altitude column
    // shows how far the sensor moved since start (about 12 Pa per metre).
    // Reference pressure, (delay) → Result<f32 Pa>
    let p0 = chip.read_pressure(&mut delay).expect("p0");
    for i in 0..20 {
        // Temperature and pressure, (delay) → Result<Spl06Measurement>
        let m = chip.read(&mut delay).expect("read");
        // Temperature only, (delay) → Result<f32 °C>
        let t = chip.read_temperature(&mut delay).expect("temperature");
        // Altitude vs. reference, (delay, sea_level_pa) → Result<f32 m>
        let h = chip.altitude(&mut delay, p0).expect("altitude");
        println!("{} s  {:.2} C ({:.2} C)  {:.2} Pa  {:.2} m", i, m.temperature_c, t, m.pressure_pa, h);
        sleep_ms!(1000);
    }

    // --- Oversampling: noise at 1x, 16x and 128x ---
    // More oversampling means less noise but longer measurements; above 8x
    // the driver sets P_SHIFT automatically.
    for osr in [1u8, 16, 128] {
        // Pressure oversampling, (oversampling, rate) → Result<()>
        chip.set_pressure_config(osr, 1).expect("pressure config");
        let mut v = [0f32; 10];
        for x in v.iter_mut() {
            // Pressure, (delay) → Result<f32 Pa>
            *x = chip.read_pressure(&mut delay).expect("pressure");
        }
        let mean = v.iter().sum::<f32>() / 10.0;
        let var = v.iter().map(|x| (x - mean) * (x - mean)).sum::<f32>() / 10.0;
        println!("{}x: mean {:.2} Pa  sd {:.2} Pa", osr, mean, libm_sqrt(var));
    }

    // --- Variometer: background mode at 8 Hz ---
    // The chip measures on its own; read_continuous only fetches the latest
    // results, so the host never waits for a conversion it started itself.
    // 16x, 8 Hz, (oversampling, rate) → Result<()>
    chip.set_pressure_config(16, 8).expect("pressure config");
    // 1x, 8 Hz, (oversampling, rate) → Result<()>
    chip.set_temperature_config(1, 8).expect("temperature config");
    // Background mode, (pressure, temperature) → Result<()>
    chip.start_continuous(true, true).expect("start");
    let mut last: Option<f32> = None;
    for _ in 0..16 {
        // Latest results, (delay) → Result<Spl06Measurement>
        let c = chip.read_continuous(&mut delay).expect("continuous");
        if let Some(l) = last {
            println!("{:.2} Pa  climb {:.2} m/s", c.pressure_pa, -(c.pressure_pa - l) / 12.0 * 8.0);
        }
        last = Some(c.pressure_pa);
    }
    // Ready flags, () → Result<(bool, bool)>
    println!("ready flags {:?}", chip.data_ready().expect("ready"));
    // Standby, () → Result<()>
    chip.stop().expect("stop");

    // --- FIFO: let the chip collect 32 results, then read them in one go ---
    // Each entry's LSB marks it as pressure or temperature; the host can sleep
    // while the chip fills the FIFO (32 entries = 2 s at 8 Hz for both).
    // Empty the FIFO, () → Result<()>
    chip.flush_fifo().expect("flush");
    // FIFO on, () → Result<()>
    chip.enable_fifo().expect("fifo on");
    // Background mode, (pressure, temperature) → Result<()>
    chip.start_continuous(true, true).expect("start");
    for _ in 0..50 {
        // Wait for full, () → Result<(bool empty, bool full)>
        if chip.fifo_status().expect("status").1 {
            break;
        }
        sleep_ms!(100);
    }
    // Standby, () → Result<()>
    chip.stop().expect("stop");
    let mut entries = [Spl06FifoEntry::default(); 32];
    // Drain FIFO, (&mut [Spl06FifoEntry]) → Result<usize>
    let n = chip.read_fifo(&mut entries).expect("fifo");
    let (mut sum, mut np) = (0f32, 0u32);
    for e in entries[..n].iter().filter(|e| e.kind == Spl06Kind::Pressure) {
        sum += e.value;
        np += 1;
    }
    println!("{} FIFO entries, {} pressure, mean {:.2} Pa", n, np, if np > 0 { sum / np as f32 } else { 0.0 });
    // FIFO off, () → Result<()>
    chip.disable_fifo().expect("fifo off");

    // --- Data-ready interrupt ---
    // SDO doubles as the interrupt line; the Rust driver leaves the pin to the
    // caller, so this loop polls INT_STS where an ISR would call poll_interrupt.
    // Pressure-ready interrupt, (source) → Result<()>
    chip.enable_interrupt(SOURCE_PRESSURE).expect("enable");
    // Clear stale status, () → Result<u8>
    chip.poll_interrupt().expect("poll");
    // Background mode, (pressure, temperature) → Result<()>
    chip.start_continuous(true, true).expect("start");
    for _ in 0..4 {
        sleep_ms!(500);
        // Read and clear INT_STS, () → Result<u8>
        println!("INT_STS 0x{:02X}", chip.poll_interrupt().expect("poll"));
    }
    // Disable source, (source) → Result<()>
    chip.disable_interrupt(SOURCE_PRESSURE).expect("disable");
    // Standby, () → Result<()>
    chip.stop().expect("stop");

    // --- Compensation by hand ---
    // compensate() turns raw register values into SI units with the stored
    // coefficients; useful for logging raw data and converting it later.
    // Back to 16x, (oversampling, rate) → Result<()>
    chip.set_pressure_config(16, 1).expect("pressure config");
    // Back to 1x, (oversampling, rate) → Result<()>
    chip.set_temperature_config(1, 1).expect("temperature config");
    // Raw results, (delay) → Result<(i32, i32)>
    let (praw, traw) = chip.read_raw(&mut delay).expect("raw");
    // Compensate, (praw, traw, p_oversampling, t_oversampling) → Result<Spl06Measurement>
    println!("raw {} {} -> {:?}", praw, traw, chip.compensate(praw, traw, 16, 1).expect("compensate"));

    // --- Wrap up ---
    // Standby, empty FIFO and a soft reset leave the chip as after power-up
    // (plus the driver defaults).
    // Standby, () → Result<()>
    chip.stop().expect("stop");
    // Empty the FIFO, () → Result<()>
    chip.flush_fifo().expect("flush");
    // Soft reset and defaults, (delay) → Result<()>
    chip.reset(&mut delay).expect("reset");
}

fn libm_sqrt(x: f32) -> f32 {
    x.sqrt()
}
