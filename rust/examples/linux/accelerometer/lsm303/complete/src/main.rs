use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::lsm303::{
    FIFO_BYPASS, FIFO_FIFO, MODE_HIGH_RESOLUTION, MODE_NORMAL, SOURCE_CLICK, SOURCE_DATA_READY,
};
use periph::chips::accelerometer::Lsm303Full;
use periph::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let mut chip = Lsm303Full::new(Connection::new(dev), 0x19, 0x1E, Delay).expect("init LSM303");

    // --- Accelerometer configuration ---
    chip.set_range(8).expect("set_range");                         // Set accelerometer range, (range_g) -> g
    chip.set_mode(MODE_NORMAL).expect("set_mode");                 // Set accelerometer mode, (mode) -> None
    chip.set_data_rate(400).expect("set_data_rate");               // Set accelerometer data rate, (rate_hz) -> Hz
    chip.set_axes(true, true, true).expect("set_axes");            // Enable accelerometer axes, (x, y, z) -> None
    chip.power_down().expect("power_down");                        // Stop accelerometer conversions, () -> None
    chip.set_data_rate(100).expect("set_data_rate");               // Set accelerometer data rate, (rate_hz) -> Hz
    chip.set_mode(MODE_HIGH_RESOLUTION).expect("set_mode");        // Set accelerometer mode, (mode) -> None
    chip.set_range(2).expect("set_range");                         // Set accelerometer range, (range_g) -> g

    // --- Reads ---
    let (ax, ay, az) = chip.read_acceleration().expect("read_acceleration");   // Read 3-axis acceleration, () -> (g, g, g)
    let raw = chip.read_acceleration_raw().expect("read_acceleration_raw");    // Read raw acceleration counts, () -> (LSB, LSB, LSB)
    let ready = chip.new_data_available().expect("new_data_available");        // Check new acceleration data, () -> bool
    let status = chip.read_status().expect("read_status");                     // Read accelerometer status, () -> u8

    // --- High-pass filter ---
    chip.set_high_pass(1, 2, true, true, false, true).expect("set_high_pass"); // Configure high-pass filter, (...)
    let reference = chip.reset_high_pass().expect("reset_high_pass");          // Reset high-pass filter, () -> u8
    chip.set_reference(0x40).expect("set_reference");                          // Set high-pass reference, (value) -> None

    // --- FIFO ---
    chip.set_fifo(FIFO_FIFO, 16, false).expect("set_fifo");                    // Configure FIFO, (mode, watermark, trigger_int2)
    let level = chip.fifo_level().expect("fifo_level");                        // Read FIFO level, () -> u8
    let (wm, ov, empty, lvl) = chip.read_fifo_status().expect("read_fifo_status"); // Read FIFO status, () -> (bool, bool, bool, u8)
    let samples = chip.read_fifo(None).expect("read_fifo");                    // Drain FIFO, (count) -> Vec
    chip.set_fifo(FIFO_BYPASS, 0, false).expect("set_fifo");                   // Configure FIFO, (mode, watermark, trigger_int2)

    // --- Inertial interrupt generators ---
    chip.set_motion_interrupt(1, 0x3F, 0.5, 30.0, true).expect("set_motion_interrupt"); // Configure motion interrupt, (...)
    chip.set_free_fall(1, 0.35, 30.0).expect("set_free_fall");                // Configure free-fall, (...)
    chip.set_orientation(1, false, false).expect("set_orientation");          // Configure orientation, (...)
    chip.set_latch(1, true).expect("set_latch");                              // Latch generator 1, (generator, enabled)
    let src = chip.read_interrupt_source(1).expect("read_interrupt_source");   // Read interrupt source, (generator) -> u8
    chip.set_latch(1, false).expect("set_latch");                             // Latch generator 1, (generator, enabled)

    // --- Click detection ---
    chip.set_click(0x01, 0x04, 0.5, 10.0, 20.0, 50.0).expect("set_click");    // Configure click detection, (...)
    let click = chip.read_click_source().expect("read_click_source");         // Read click source, () -> u8

    // --- Interrupt routing ---
    chip.set_interrupt_polarity(false).expect("set_interrupt_polarity");      // Set interrupt polarity, (active_low)
    chip.enable_interrupt(SOURCE_DATA_READY, 1).expect("enable_interrupt");    // Enable interrupt source, (source, pin)
    chip.enable_interrupt(SOURCE_CLICK, 1).expect("enable_interrupt");         // Enable interrupt source, (source, pin)
    chip.disable_interrupt(SOURCE_DATA_READY, 1).expect("disable_interrupt");  // Disable interrupt source, (source, pin)
    let isr = chip.poll_interrupt().expect("poll_interrupt");                  // Poll interrupt status, () -> u16

    // --- Magnetometer ---
    chip.set_mag_range(470).expect("set_mag_range");                          // Set magnetometer range, (range_ut) -> µT
    chip.set_mag_data_rate(30.0).expect("set_mag_data_rate");                 // Set magnetometer data rate, (rate_hz) -> Hz
    chip.set_mag_mode(1).expect("set_mag_mode");                             // Set magnetometer mode, (mode)
    chip.set_mag_mode(0).expect("set_mag_mode");                             // Set magnetometer mode, (mode)
    let mraw = chip.read_magnetic_raw().expect("read_magnetic_raw");          // Read raw magnetic counts, () -> (LSB, LSB, LSB)
    let (mx, my, mz) = chip.read_magnetic().expect("read_magnetic");          // Read 3-axis magnetic field, () -> (µT, µT, µT)
    let mready = chip.mag_data_ready().expect("mag_data_ready");              // Check new magnetometer data, () -> bool
    let (drdy, lock) = chip.read_mag_status().expect("read_mag_status");      // Read magnetometer status, () -> (bool, bool)
    let id = chip.read_mag_identity().expect("read_mag_identity");            // Read magnetometer identity, () -> [u8; 3]
    chip.enable_temperature(true).expect("enable_temperature");               // Enable temperature sensor, (enabled)
    let temp = chip.read_temperature_delta().expect("read_temperature_delta"); // Read relative temperature, () -> °C
    let heading = chip.heading(true).expect("heading");                       // Compute compass heading, (tilt_compensate) -> deg
    let flat = chip.heading(false).expect("heading");                         // Compute compass heading, (tilt_compensate) -> deg

    // --- System ---
    chip.reboot().expect("reboot");                                          // Reboot accelerometer memory, () -> None

    println!("accel=({:.3}, {:.3}, {:.3}) g raw={:?} ready={} status=0x{:02X}",
             ax, ay, az, raw, ready, status);
    println!("reference=0x{:02X} fifo level={} wm={} ov={} empty={} read={} samples={}",
             reference, level, wm, ov, empty, lvl, samples.len());
    println!("src=0x{:02X} click=0x{:02X} isr=0x{:04X}", src, click, isr);
    println!("mag=({:.1}, {:.1}, {:.1}) µT raw={:?} ready={} drdy={} lock={} id={:02X?}",
             mx, my, mz, mraw, mready, drdy, lock, id);
    println!("temp={:+.1} °C heading={:.1}° flat={:.1}°", temp, heading, flat);
}
