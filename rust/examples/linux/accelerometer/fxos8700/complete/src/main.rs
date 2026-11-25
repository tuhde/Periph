use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{
    Fxos8700Full, Fxos8700PulseConfig as PulseConfig, FXOS8700_FIFO_CIRCULAR, FXOS8700_FIFO_DISABLED,
    FXOS8700_MODE_HYBRID, FXOS8700_SOURCE_FF_MT, FXOS8700_SOURCE_ORIENTATION,
};
use periph::connection::Connection;
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1E);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create FXOS8700 Full driver, (connection, addr=0x1E, delay) → Result
    let mut chip = Fxos8700Full::new(Connection::new(dev), addr, Delay).expect("init FXOS8700");
    // verifies WHO_AM_I, resets and starts hybrid mode

    // --- Measurements ---
    // Read acceleration, () → (f32, f32, f32) g
    let (ax, ay, az) = chip.read_accel().expect("read_accel");
    // burst-reads 0x01-0x06, 14-bit left-justified
    // Read magnetic field, () → (f32, f32, f32) µT
    let (mx, my, mz) = chip.read_mag().expect("read_mag");
    // burst-reads 0x33-0x38, 0.1 µT/LSB
    println!("accel {:.4} {:.4} {:.4} g, mag {:.2} {:.2} {:.2} uT", ax, ay, az, mx, my, mz);
    // Read both sensors, () → Option<((f32, f32, f32), (f32, f32, f32))>
    let hybrid = chip.read_hybrid().expect("read_hybrid");
    // one 12-byte burst from 0x01 (hyb_autoinc_mode)
    println!("hybrid {:?}", hybrid);
    // Read raw accelerometer, () → (i16, i16, i16) counts
    println!("raw accel {:?}", chip.read_raw_accel().expect("read_raw_accel"));
    // signed 14-bit counts
    // Read raw magnetometer, () → (i16, i16, i16) counts
    println!("raw mag {:?}", chip.read_raw_mag().expect("read_raw_mag"));
    // signed 16-bit counts, 0.1 µT/LSB
    // Read die temperature, () → Option<f32> °C
    println!("temperature {:?}", chip.read_temperature().expect("read_temperature"));
    // int8(TEMP) × 0.96, relative only
    // Check accelerometer data ready, () → bool
    println!("new accel {}", chip.new_accel_data().expect("new_accel_data"));
    // STATUS.zyxdr
    // Check magnetometer data ready, () → bool
    println!("new mag {}", chip.new_mag_data().expect("new_mag_data"));
    // M_DR_STATUS.zyxdr
    // Read STATUS, () → u8
    println!("status {:#04x}", chip.read_status().expect("read_status"));
    // Read M_DR_STATUS, () → u8
    println!("mag status {:#04x}", chip.read_mag_status().expect("read_mag_status"));
    // Read SYSMOD, () → u8 0 standby | 1 wake | 2 sleep
    println!("system mode {}", chip.read_system_mode().expect("read_system_mode"));

    // Select operating mode, (mode 0|1|2) → Result<bool>
    chip.set_mode(FXOS8700_MODE_HYBRID).expect("set_mode");
    // 0 accel only, 1 mag only, 2 hybrid
    // Set accelerometer range, (range_g 2|4|8) → Result<bool>
    chip.set_range(4).expect("set_range");
    // rewrites XYZ_DATA_CFG.fs and the cached sensitivity
    // Set system data rate, (rate_hz 800..1.5625) → Result<bool>
    chip.set_data_rate(200.0).expect("set_data_rate");
    // each sensor runs at half this rate in hybrid mode
    // Set accelerometer oversampling, (mode 0..3, sleep_mode=None) → Result<bool>
    chip.set_accel_oversampling(0, Some(0)).expect("set_accel_oversampling");
    // MODS / SMODS in CTRL_REG2
    // Set magnetometer oversampling, (ratio 0..7, sleep_ratio=None) → Result<bool>
    chip.set_mag_oversampling(7, Some(7)).expect("set_mag_oversampling");
    // m_os / m_aslp_os
    // Set low-noise mode, (enabled) → Result<bool>
    chip.set_low_noise(false).expect("set_low_noise");
    // lnoise limits the range to ±4 g
    // Set accelerometer resolution, (bits 14|8) → Result<bool>
    chip.set_resolution(14).expect("set_resolution");
    // 8 selects fast read (MSB only)
    // Set high-pass output, (enabled, cutoff 0..3) → Result<bool>
    chip.set_high_pass_output(false, 0).expect("set_high_pass_output");
    // hpf_out and sel
    // Enter STANDBY, () → Result<bool>
    chip.standby().expect("standby");
    // polls SYSMOD until 0 (up to 300 ms)
    // Enter ACTIVE, () → Result
    chip.activate().expect("activate");
    // waits the 2/ODR + 2 ms turn-on time

    // Set accelerometer offsets, (x_g, y_g, z_g) → Result
    chip.set_accel_offset(0.0, 0.0, 0.0).expect("set_accel_offset");
    // OFF_X/Y/Z, 2 mg/LSB
    // Get accelerometer offsets, () → (f32, f32, f32) g
    println!("accel offset {:?}", chip.get_accel_offset().expect("get_accel_offset"));
    // Set hard-iron offsets, (x_ut, y_ut, z_ut) → Result
    chip.set_mag_offset(0.0, 0.0, 0.0).expect("set_mag_offset");
    // 15-bit values subtracted from the output
    // Get hard-iron offsets, () → (f32, f32, f32) µT
    println!("mag offset {:?}", chip.get_mag_offset().expect("get_mag_offset"));
    // Select raw magnetometer output, (raw) → Result
    chip.set_mag_raw(false).expect("set_mag_raw");
    // false applies the hard-iron offsets
    // Hardware hard-iron calibration, (enabled) → Result
    chip.set_mag_auto_calibration(true).expect("set_mag_auto_calibration");
    // MAX/MIN midpoint → M_OFF_* every ODR cycle
    std::thread::sleep(Duration::from_secs(1));
    // Read tracked min / max, () → ((f32, f32, f32), (f32, f32, f32)) µT
    println!("min/max {:?}", chip.read_mag_min_max().expect("read_mag_min_max"));
    chip.set_mag_auto_calibration(false).expect("set_mag_auto_calibration");
    // Reset min / max tracker, () → Result
    chip.reset_mag_min_max().expect("reset_mag_min_max");
    // m_maxmin_rst
    // Set automatic magnetic reset, (mode 0..3) → Result<bool>
    chip.set_mag_reset(0).expect("set_mag_reset");
    // 0 every ODR cycle, 3 disabled
    // Trigger one-shot degauss, () → Result
    chip.trigger_mag_reset().expect("trigger_mag_reset");
    // m_rst
    // Measure once in STANDBY, () → Result<Option<(f32, f32, f32)>> µT
    println!("one-shot {:?}", chip.measure_mag_once().expect("measure_mag_once"));
    // m_ost, restores ACTIVE
    // Compute tilt-compensated heading, (declination_deg) → f32 degrees
    println!("heading {:.1}", chip.heading(2.5).expect("heading"));
    // software compensation from one accel + one mag sample

    // Configure FIFO, (mode 0..3, watermark) → Result<bool>
    chip.set_fifo(FXOS8700_FIFO_CIRCULAR, 16).expect("set_fifo");
    // circular mode with a 16-sample watermark
    // Select FIFO trigger sources, (transient, orientation, pulse, motion) → Result
    chip.set_fifo_trigger(false, false, false, true).expect("set_fifo_trigger");
    // TRIG_CFG
    // Set FIFO gate, (enabled) → Result
    chip.set_fifo_gate(false).expect("set_fifo_gate");
    // fifo_gate
    std::thread::sleep(Duration::from_millis(200));
    // Read FIFO level, () → u8
    println!("fifo level {}", chip.fifo_level().expect("fifo_level"));
    // f_cnt, 0-32
    // Read FIFO status, () → (overflow, watermark, count)
    println!("fifo status {:?}", chip.read_fifo_status().expect("read_fifo_status"));
    let mut samples = [(0.0f32, 0.0f32, 0.0f32); 4];
    // Drain FIFO, (out) → Result<usize>
    let n = chip.read_fifo(&mut samples).expect("read_fifo");
    // one 6-byte transaction per sample
    println!("fifo {:?}", &samples[..n]);
    chip.set_fifo(FXOS8700_FIFO_DISABLED, 0).expect("set_fifo");

    // Configure freefall / motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → Result
    chip.set_freefall_motion(true, 7, 0.3, 60.0, true, false).expect("set_freefall_motion");
    // A_FFMT_CFG / THS / COUNT
    // Read A_FFMT_SRC, () → u8
    println!("motion {:#04x}", chip.read_motion_source().expect("read_motion_source"));
    // clears the flags when latched
    // Configure transient detector, (axes, threshold_g, debounce_ms, bypass_hpf, latch) → Result
    chip.set_transient(7, 0.4, 20.0, false, false).expect("set_transient");
    // TRANSIENT_CFG / THS / COUNT
    // Read TRANSIENT_SRC, () → u8
    println!("transient {:#04x}", chip.read_transient_source().expect("read_transient_source"));
    // clears the interrupt
    // Configure tap detection, (&PulseConfig) → Result
    chip.set_pulse(&PulseConfig { single: 0x04, ..Default::default() }).expect("set_pulse");
    // single tap on Z with default thresholds
    // Read PULSE_SRC, () → u8
    println!("pulse {:#04x}", chip.read_pulse_source().expect("read_pulse_source"));
    // clears the interrupt
    // Configure orientation detection, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → Result<bool>
    chip.set_orientation(true, 100.0, 45, 4, 4, 2).expect("set_orientation");
    // PL_CFG / COUNT / THS / BF_ZCOMP
    // Read orientation, () → (changed, lockout, portrait_landscape, back)
    println!("orientation {:?}", chip.read_orientation().expect("read_orientation"));
    // clears newlp
    // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → Result<bool>
    chip.set_auto_sleep(true, 12.5, 10.0, FXOS8700_SOURCE_FF_MT).expect("set_auto_sleep");
    // sleeps after 10 s of inactivity, motion wakes

    // Configure INT pins, (active_high, open_drain) → Result
    chip.set_interrupt_pins(false, false).expect("set_interrupt_pins");
    // ipol / pp_od
    // Enable and route interrupt, (source mask, pin 1|2) → Result<bool>
    chip.enable_interrupt(FXOS8700_SOURCE_FF_MT | FXOS8700_SOURCE_ORIENTATION, 1).expect("enable_interrupt");
    // CTRL_REG4 enable, CTRL_REG5 routing
    // Poll and clear interrupt sources, () → u8
    println!("interrupts {:#04x}", chip.poll_interrupt().expect("poll_interrupt"));
    // reads INT_SOURCE and the matching source registers
    // Disable interrupt sources, (source mask) → Result
    chip.disable_interrupt(FXOS8700_SOURCE_FF_MT | FXOS8700_SOURCE_ORIENTATION).expect("disable_interrupt");
    // clears int_en_*

    let mut reg = [0u8; 1];
    // Read raw registers, (reg, buf) → Result
    chip.read_register(0x5F, &mut reg).expect("read_register");
    // A_VECM_CFG, not wrapped by the API
    // Write raw registers, (reg, data) → Result
    chip.write_register(0x5F, &reg).expect("write_register");
    // no STANDBY handling
    // Run accelerometer self-test, () → (bool, i16, i16, i16) counts
    let (ok, dx, dy, dz) = chip.self_test().expect("self_test");
    // electrostatic test, checks X ≥ 192, Y ≥ 270, Z ≥ 1275
    println!("self-test ok={} {} {} {}", ok, dx, dy, dz);
    // Software reset, () → Result
    chip.reset().expect("reset");
    // the NACK on the write is tolerated; call init() afterwards
    // Re-initialise, () → Result
    chip.init().expect("init");
    // verifies WHO_AM_I and restarts hybrid mode
}
