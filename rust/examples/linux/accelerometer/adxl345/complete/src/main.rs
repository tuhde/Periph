use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{
    Adxl345Full, FIFO_STREAM, INT_WATERMARK,
};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x53);

    let dev  = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut accel = Adxl345Full::new(dev, addr, false).expect("init ADXL345"); // Create ADXL345 Full driver, (i2c, addr=0x53, spi=false)

    accel.set_range(4).expect("set_range");                            // Set measurement range, (range_g) → () g
                                                                            // selects ±4 g; FULL_RES preserved so scale stays 3.9 mg/LSB
    accel.set_data_rate(200.0).expect("set_data_rate");                // Set output data rate, (rate_hz) → () Hz
                                                                            // picks the nearest supported value (200 Hz)
    accel.set_low_power(false).expect("set_low_power");                // Set low-power mode, (enabled) → ()
                                                                            // normal-power mode; LOW_POWER bit in BW_RATE cleared
    accel.calibrate_offset(0.0, 0.0, 1.0, 64).expect("calibrate");   // Calibrate offsets, (target_x=0 g, target_y=0 g, target_z=1 g, samples=128) → () g, g, g
                                                                            // averages 64 samples with Z axis up and writes OFSX/OFSY/OFSZ
    accel.set_tap_detection(0.5, 10.0, 0x07, false).expect("tap");    // Configure single-tap, (threshold_g, duration_ms, axes=0x07, suppress=false) → () g, ms
                                                                            // 0.5 g threshold, 10 ms duration, all axes, no suppress
    accel.set_double_tap(50.0, 200.0).expect("dtap");                  // Configure double-tap, (latency_ms, window_ms) → () ms, ms
                                                                            // 50 ms latency, 200 ms window between taps
    accel.set_fifo_mode(FIFO_STREAM, 16).expect("fifo");               // Configure FIFO, (mode, samples=16) → ()
                                                                            // stream mode, watermark 16 entries
    accel.set_interrupt(INT_WATERMARK, true, 1).expect("irq");        // Configure interrupt, (source, enabled, pin=1) → ()
                                                                            // enable watermark interrupt on INT1

    let (x, y, z) = accel.read().expect("read");                       // Read 3-axis acceleration, () → (f32, f32, f32) g
                                                                            // single-shot burst read of all 6 data bytes
    let mut fifo_buf = [(0.0f32, 0.0f32, 0.0f32); 32];
    let n_samples = accel.read_fifo(&mut fifo_buf).expect("read_fifo"); // Drain the FIFO, (out) → usize g
                                                                            // fills up to 32 (x, y, z) samples in *g*
    let count = accel.fifo_count().expect("fifo_count");               // FIFO entries available, () → u8
                                                                            // from FIFO_STATUS register
    let src = accel.read_interrupt_source().expect("read_irq_src");    // Read interrupt source, () → u8
                                                                            // bitmask of active INT_* sources; clears latches

    accel.self_test(false).expect("self_test");                        // Toggle self-test, (enabled) → ()
                                                                            // SELF_TEST bit in DATA_FORMAT cleared
    accel.set_sleep(false, 8).expect("set_sleep");                     // Set sleep mode, (enabled, wakeup_hz=8) → () Hz
                                                                            // wake up; no further state changes
    accel.set_link_mode(false).expect("set_link_mode");                // Set activity/inactivity link, (enabled) → ()
                                                                            // Link bit in POWER_CTL cleared
    accel.set_auto_sleep(false).expect("set_auto_sleep");              // Set auto-sleep, (enabled) → ()
                                                                            // AUTO_SLEEP bit cleared

    println!("x={:.3} y={:.3} z={:.3} g", x, y, z);
    println!("fifo_count={} interrupts=0x{:02X}", count, src);
    println!("samples={}", n_samples);
}