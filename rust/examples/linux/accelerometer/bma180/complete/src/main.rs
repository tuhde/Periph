use linux_embedded_hal::I2cdev;
use periph::chips::accelerometer::{Bma180Full, SOURCE_LOW_G, SOURCE_HIGH_G, SOURCE_SLOPE, SOURCE_TAP, SOURCE_NEW_DATA};

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x40);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Bma180Full::new(dev, addr).expect("init BMA180");

    chip.set_range(8.0).expect("set range");                                    // Set measurement range, (range_g=8 g) → ()
                                                                                 // selects ±8 g; LSB scale changes from 4096 to 1024 LSB/g
    chip.set_bandwidth(40).expect("set bandwidth");                              // Set bandwidth, (bandwidth_hz=40 Hz) → ()
                                                                                 // picks nearest low-pass value
    chip.set_filter_mode(1).expect("set filter mode");                          // Set filter mode, (mode=1 high-pass 1 Hz) → ()
    chip.set_mode(0).expect("set mode");                                        // Set mode, (mode=0 low-noise) → ()
    chip.set_resolution(14).expect("set resolution");                            // Set resolution, (bits=14) → ()

    let (rx, ry, rz) = chip.read_raw().expect("read raw");                      // Read raw 14-bit counts, () → (i16, i16, i16)
    let temp = chip.read_temperature().expect("read temp");                      // Read temperature, () → float °C
    let ready = chip.new_data_available().expect("new data available");          // Check new data, () → bool

    chip.set_shadow(false).expect("set shadow");                                // Set shadow mode, (enabled=False) → ()
    chip.set_sample_skip(false).expect("set sample skip");                      // Set sample skip, (enabled=False) → ()

    chip.set_low_g(0.3, 40, 0.05, 0x07, 0, true).expect("set low-g");           // Configure low-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → ()
    chip.set_high_g(1.8, 20, 0.1, 0x07, 0, true).expect("set high-g");           // Configure high-g, (threshold_g, duration_ms, hysteresis_g, axes, counter, filtered) → ()
    chip.set_slope(0.3, 3, 0x07, true).expect("set slope");                      // Configure slope, (threshold_g, samples, axes, filtered) → ()
    chip.set_alert(false).expect("set alert");                                   // Set alert, (enabled=False) → ()
    chip.set_tap(0.5, 250, 0x07, true).expect("set tap");                        // Configure tap, (threshold_g, window_ms, axes, filtered) → ()
    chip.set_latch(true).expect("set latch");                                    // Set latch, (enabled=True) → ()

    let flags = chip.poll_interrupt().expect("poll interrupt");                  // Read STATUS_REG3, () → u8
    chip.clear_interrupt().expect("clear interrupt");                            // Clear latched interrupts, () → ()

    chip.set_wake_up(true, 80).expect("set wake-up");                            // Set self-wake-up, (enabled, pause_ms) → ()

    let (x, y, z) = chip.read().expect("read");                                  // Read 3-axis acceleration, () → (f32, f32, f32) g
    let (al, ml) = chip.read_version().expect("read version");                  // Read version, () → (u8, u8)
    let c1 = chip.read_customer(0).expect("read customer");                     // Read scratch byte, (index) → u8
    chip.write_customer(1, 0xA5).expect("write customer");                     // Write scratch byte, (index, value) → ()
    let st = chip.self_test().expect("self_test");                              // Run self-test, () → bool
    chip.calibrate_offset(0x07, 1).expect("calibrate offset");                   // Calibrate offset, (axes, mode) → ()
    chip.soft_reset().expect("soft_reset");                                     // Soft reset, () → ()
    chip.sleep().expect("sleep");                                               // Sleep, () → ()
    chip.wake().expect("wake");                                                 // Wake-up, () → ()

    chip.disable_interrupt(SOURCE_LOW_G).expect("disable");                     // Disable interrupt, (source) → ()
    chip.disable_interrupt(SOURCE_HIGH_G).expect("disable");
    chip.disable_interrupt(SOURCE_SLOPE).expect("disable");
    chip.disable_interrupt(SOURCE_TAP).expect("disable");
    chip.enable_interrupt(SOURCE_NEW_DATA).expect("enable");
    chip.disable_interrupt(SOURCE_NEW_DATA).expect("disable");

    println!("raw=({:},{:},{:}) temp={:.1} flags=0x{:02X} al={} ml={} c1=0x{:02X} st={}",
             rx, ry, rz, temp, flags, al, ml, c1, if st { "PASS" } else { "FAIL" });
}