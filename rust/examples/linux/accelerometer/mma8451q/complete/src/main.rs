use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{Mma8451QFull, MMA8451Q_SOURCE_PULSE};
use std::time::Duration;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x1C);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create MMA8451Q driver, (i2c bus, i2c addr) → Result
    let mut chip = Mma8451QFull::new(dev, addr).expect("init MMA8451Q");

    // Wait for the STANDBY → ACTIVE turn-on time, (ms) → None
    std::thread::sleep(Duration::from_millis(22));

    // Set full-scale range, (range_g) → None
    chip.set_range(4).expect("set_range");
    // selects ±4 g and updates the cached sensitivity

    // Set output data rate, (rate_hz) → None
    chip.set_data_rate(100.0).expect("set_data_rate");
    // selects 100 Hz and updates the cached ODR and time steps

    // Set oversampling mode, (mode, sleep_mode) → None
    chip.set_oversampling(2, Some(0)).expect("set_oversampling");
    // mode 2 is high resolution, sleep_mode 0 is normal oversampling

    // Set reduced-noise mode, (enabled) → None
    chip.set_low_noise(false).expect("set_low_noise");
    // low-noise mode limits the range to ±4 g

    // Set output resolution, (bits) → None
    chip.set_resolution(14).expect("set_resolution");
    // 14-bit output, 8-bit selects the fast-read mode

    // Set high-pass filtered output, (enabled, cutoff) → None
    chip.set_high_pass_output(false, 0).expect("set_high_pass_output");
    // disables the high-pass filter on the data output

    // Set per-axis offsets, (x_g, y_g, z_g) → None
    chip.set_offset(0.0, 0.0, 0.0).expect("set_offset");
    // writes the zero-g calibration offsets

    // Read per-axis offsets, () → (f32 x, f32 y, f32 z) g
    let offset = chip.get_offset().expect("get_offset");

    // Configure the freefall/motion detector, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → None
    chip.set_freefall_motion(false, 0x07, 0.2, 60.0, true, false).expect("set_freefall_motion");
    // arms the freefall detector on all three axes with a 0.2 g threshold

    // Read the freefall/motion source register, () → u8
    let motion_src = chip.read_motion_source().expect("read_motion_source");

    // Configure the transient detector, (axes, threshold_g, debounce_ms, bypass_hpf, latch) → None
    chip.set_transient(0x07, 0.5, 0.0, false, true).expect("set_transient");
    // arms the transient detector on all three axes

    // Read the transient source register, () → u8
    let transient_src = chip.read_transient_source().expect("read_transient_source");

    // Configure the tap detector, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms, latch, double_abort, lpf, hpf) → None
    chip.set_pulse(0x07, 0, (0.5, 0.5, 0.5), 30.0, 100.0, 300.0,
                    true, false, true, true).expect("set_pulse");
    // arms single-tap detection on all axes with a 0.5 g threshold

    // Read the tap source register, () → u8
    let pulse_src = chip.read_pulse_source().expect("read_pulse_source");

    // Configure orientation detection, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → None
    chip.set_orientation(true, 100.0, 45, 4, 4, 1).expect("set_orientation");
    // enables the portrait/landscape detector with a 45 deg trip angle

    // Read the orientation status, () → (bool changed, bool lockout, u8 portrait_landscape, bool back)
    let orientation = chip.read_orientation().expect("read_orientation");

    // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → None
    chip.set_auto_sleep(true, 12.5, 10.0, MMA8451Q_SOURCE_PULSE).expect("set_auto_sleep");
    // sleeps after 10 s of inactivity and wakes on a tap

    // Configure the FIFO gate, (enabled) → None
    chip.set_fifo_gate(false).expect("set_fifo_gate");
    // leaves the FIFO ungated around WAKE/SLEEP transitions

    // Configure the interrupt pins, (active_high, open_drain) → None
    chip.set_interrupt_pins(false, false).expect("set_interrupt_pins");
    // keeps the INT pins active-low and push-pull

    // Enable an interrupt source, (source, pin) → None
    chip.enable_interrupt(MMA8451Q_SOURCE_PULSE, 1).expect("enable_interrupt");
    // routes the tap interrupt to INT1

    // Disable an interrupt source, (source) → None
    chip.disable_interrupt(MMA8451Q_SOURCE_PULSE).expect("disable_interrupt");
    // clears the tap interrupt enable

    // Read and clear the interrupt status, () → u8
    let status = chip.poll_interrupt().expect("poll_interrupt");

    // Check for a new sample, () → bool
    let ready = chip.new_data_available().expect("new_data_available");

    // Read the raw STATUS register, () → u8
    let raw_status = chip.read_status().expect("read_status");

    // Read the system mode, () → u8
    let mode = chip.read_system_mode().expect("read_system_mode");

    // Read raw acceleration counts, () → (i16 x, i16 y, i16 z)
    let raw = chip.read_raw().expect("read_raw");

    // Read 3-axis acceleration, () → (f32 x, f32 y, f32 z) g
    let xyz = chip.read().expect("read");

    // Run the self-test, () → (bool ok, i16 dx, i16 dy, i16 dz)
    let st = chip.self_test().expect("self_test");

    println!("offset=({:.3}, {:.3}, {:.3}) motion=0x{:02X} transient=0x{:02X} pulse=0x{:02X}",
             offset.0, offset.1, offset.2, motion_src, transient_src, pulse_src);
    println!("orientation=(changed={} lockout={} lapo={} back={})",
             orientation.0, orientation.1, orientation.2, orientation.3);
    println!("status=0x{:02X} mode={} ready={} raw_status=0x{:02X} raw=({},{},{})",
             status, mode, ready, raw_status, raw.0, raw.1, raw.2);
    println!("xyz=({:.3}, {:.3}, {:.3}) self_test=({}, {}, {}, {})",
             xyz.0, xyz.1, xyz.2, st.0, st.1, st.2, st.3);
}
