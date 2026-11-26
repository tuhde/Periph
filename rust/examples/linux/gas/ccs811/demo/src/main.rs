//! Demo for the CCS811 — an indoor air-quality monitor with zones, compensation and baseline.

use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::gas::ccs811::{SOURCE_DATA_READY, SOURCE_THRESHOLD, STATUS_DATA_READY};
use periph::chips::gas::Ccs811Full;

fn classify(eco2: u16) -> &'static str {
    if eco2 < 1000 {
        "good"
    } else if eco2 <= 1500 {
        "fair"
    } else {
        "poor"
    }
}

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x5A);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    #[allow(unused_mut)]
    let mut delay = Delay;
    #[allow(unused_macros)]
    macro_rules! sleep_ms { ($n:expr) => { std::thread::sleep(std::time::Duration::from_millis($n)) }; }

    // Create CCS811 driver, (i2c, addr=0x5A) → Ccs811Full
    let mut chip = Ccs811Full::new(dev, addr);
    // Check HW_ID, start application, mode 1, (delay) → Result<()>
    chip.init(&mut delay).expect("init");

    // --- Identity ---
    // HW_ID 0x81 identifies the CCS81x family; the firmware versions tell which
    // algorithm revision computes eCO2 and TVOC.
    // Hardware ID and version, () → Result<(u8, u8)>
    println!("hardware {:?}", chip.hardware_version().expect("hw"));
    // Firmware versions, () → Result<(Ccs811Version, Ccs811Version)>
    println!("firmware {:?}", chip.firmware_version().expect("fw"));
    // STATUS and ERROR_ID, () → Result<u8>
    println!("status 0x{:02X}, error 0x{:02X}", chip.status().expect("status"), chip.error().expect("error"));

    // --- Air-quality monitor: 30 s at 1 Hz ---
    // Mode 1 delivers a sample every second. Values settle after about 20 min of
    // warm-up; a brand-new sensor needs a 48 h burn-in.
    for i in 0..30 {
        // Next sample, (delay) → Result<Ccs811Measurement {eco2_ppm, tvoc_ppb}>
        let m = chip.read(&mut delay).expect("read");
        println!("{:2} s  eCO2 {:4} ppm  TVOC {:4} ppb  {}", i, m.eco2_ppm, m.tvoc_ppb, classify(m.eco2_ppm));
    }
    // eCO2 only, (delay) → Result<u16 ppm>
    println!("eCO2 {} ppm", chip.read_eco2(&mut delay).expect("eco2"));
    // TVOC only, (delay) → Result<u16 ppb>
    println!("TVOC {} ppb", chip.read_tvoc(&mut delay).expect("tvoc"));

    // --- Environment compensation ---
    // Feeding the real temperature and humidity (e.g. from a BME280) corrects the
    // algorithm; without it the chip assumes 25 °C and 50 %RH.
    // Compensation data, (humidity_rh %, temperature_c °C) → Result<()>
    chip.set_environment(40.0, 23.0).expect("environment");
    // NTC voltages, () → Result<(u16 mV, u16 mV)>
    let (v_ref, _) = chip.read_ntc().expect("ntc");
    if v_ref != 0 {
        // NTC resistance, (r_ref_ohm Ω) → Result<f32 Ω>
        println!("NTC {:.0} ohm", chip.ntc_resistance(10000.0).expect("ntc"));
    } else {
        println!("no NTC circuit fitted");
    }

    // --- Threshold interrupt: only report zone changes ---
    // With INT_THRESH the chip raises nINT only when eCO2 crosses 1000 or
    // 2000 ppm by more than the hysteresis. Polling STATUS here shows each sample.
    // Zone boundaries, (low_medium_ppm, medium_high_ppm, hysteresis_ppm) → Result<()>
    chip.set_thresholds(1000, 2000, 50).expect("thresholds");
    // Threshold interrupt, (source) → Result<()>
    chip.enable_interrupt(SOURCE_THRESHOLD).expect("enable");
    for _ in 0..5 {
        // STATUS, () → Result<u8>
        while chip.poll_interrupt().expect("poll") & STATUS_DATA_READY == 0 {
            sleep_ms!(100);
        }
        // Reading releases nINT, (delay) → Result<Ccs811Measurement>
        let m = chip.read(&mut delay).expect("read");
        println!("event eCO2 {} ppm {}", m.eco2_ppm, classify(m.eco2_ppm));
    }
    // Threshold interrupt off, (source) → Result<()>
    chip.disable_interrupt(SOURCE_THRESHOLD).expect("disable");

    // --- Data-ready interrupt ---
    // INT_DATARDY pulls nINT low on every sample; wire it to an ISR, or poll STATUS.
    // Data-ready interrupt, (source) → Result<()>
    chip.enable_interrupt(SOURCE_DATA_READY).expect("enable");
    sleep_ms!(1200);
    // STATUS, () → Result<u8>
    println!("poll 0x{:02X}", chip.poll_interrupt().expect("poll"));
    // Data-ready interrupt off, (source) → Result<()>
    chip.disable_interrupt(SOURCE_DATA_READY).expect("disable");

    // --- Raw sensor data ---
    // The MOx sensor's current and voltage give its resistance; the algorithm
    // derives eCO2 and TVOC from it.
    // Raw data, () → Result<(u8 µA, f32 V)>
    let (current, voltage) = chip.read_raw().expect("raw");
    let r = if current > 0 { voltage / (current as f32 * 1e-6) } else { 0.0 };
    println!("raw {} uA, {:.3} V, {:.0} ohm", current, voltage, r);

    // --- Slower sampling: mode 2 (every 10 s) ---
    // Pulse heating saves power; pass through idle when changing modes.
    // Idle, (mode) → Result<()>
    chip.set_mode(0).expect("mode");
    // Every 10 s, (mode) → Result<()>
    chip.set_mode(2).expect("mode");
    for _ in 0..2 {
        // Next sample, (delay) → Result<Ccs811Measurement>
        let m = chip.read(&mut delay).expect("read");
        // Ready flag, () → Result<bool>
        println!("mode 2 {:?} ready {}", m, chip.data_ready().expect("ready"));
    }
    // Idle, (mode) → Result<()>
    chip.set_mode(0).expect("mode");
    // Back to every second, (mode) → Result<()>
    chip.set_mode(1).expect("mode");

    // --- Baseline ---
    // Save the baseline daily and restore it after a power cycle, but only after
    // about 20 min of warm-up; otherwise the chip relearns it from scratch.
    // Baseline, () → Result<u16>
    let baseline = chip.get_baseline().expect("baseline");
    println!("baseline 0x{:04X}", baseline);
    // Restore baseline, (baseline) → Result<()>
    chip.set_baseline(baseline).expect("baseline");

    // --- Wrap up ---
    // A soft reset puts the chip into boot mode; the driver starts the
    // application again in mode 1.
    // Software reset, (delay) → Result<()>
    chip.soft_reset(&mut delay).expect("reset");
    // STATUS, () → Result<u8>
    println!("status after reset 0x{:02X}", chip.status().expect("status"));
}
