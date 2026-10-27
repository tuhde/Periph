use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{
    Lsm303Full, LSM303_SOURCE_DATA_READY, SOURCE_CLICK, SOURCE_IA1, SOURCE_MAG_DATA_READY,
};
use periph::connection::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    let conn = Connection::new(dev);                                              // Create bus connection, (i2cdev) → Connection
    let mut delay = Delay;
    let mut chip = Lsm303Full::new(conn, &mut delay).expect("init LSM303");       // Create LSM303 full driver, (connection, delay) → Result

    chip.set_range(8).expect("set_range");                                        // Set accelerometer full-scale range, (range_g=8 g)
                                                                                  // RMW CTRL_REG4_A FS bits; updates cached sensitivity
    chip.set_data_rate(100).expect("set_data_rate");                              // Set accelerometer data rate, (rate_hz=100 Hz)
                                                                                  // RMW CTRL_REG1_A ODR bits (1620/5376 low-power only)
    chip.set_mode(2).expect("set_mode");                                          // Set operating mode, (mode=2 high-resolution)
                                                                                  // RMW LPen (CTRL1) and HR (CTRL4); updates shift/sensitivity
    chip.set_axes(true, true, true).expect("set_axes");                           // Enable axes, (x=true, y=true, z=true)
                                                                                  // CTRL_REG1_A Xen/Yen/Zen bits
    chip.set_high_pass(0, 0, false, true, false, false).expect("set_high_pass");  // Configure high-pass filter, (mode=0, cutoff=0, filter_output=false, on_ia1=true, on_ia2=false, on_click=false)
                                                                                  // Writes CTRL_REG2_A: filter the generator-1 event path
    let reference = chip.reset_high_pass().expect("reset_high_pass");             // Reset high-pass filter, () → u8
                                                                                  // Reading REFERENCE_A resets the filter in HPM=00
    chip.set_reference(reference).expect("set_reference");                        // Write high-pass reference, (value=0–255)
                                                                                  // REFERENCE_A, used as the filter reference in HPM=01
    chip.set_fifo(2, 16, false).expect("set_fifo");                               // Configure FIFO, (mode=2 stream, watermark=16, trigger_int2=false)
                                                                                  // FIFO_CTRL_REG_A FM/FTH + CTRL_REG5_A FIFO_EN
    let level = chip.fifo_level().expect("fifo_level");                           // Read unread sample count, () → u8 samples
                                                                                  // FSS<4:0> from FIFO_SRC_REG_A (0–32)
    chip.set_motion_interrupt(1, 0x15, 0.5, 10.0, false).expect("set_motion_interrupt"); // Configure generator, (generator=1, events=0x15 mask, threshold_g=0.5 g, duration_ms=10 ms, and_mode=false)
                                                                                  // INT1_CFG/THS/DURATION for the threshold event
    chip.set_free_fall(2, 0.35, 30.0).expect("set_free_fall");                    // Configure free-fall detection, (generator=2, threshold_g=0.35 g, duration_ms=30 ms)
                                                                                  // Convenience: all-axes-low AND event, INT2_CFG = 0x95
    chip.set_orientation(1, false, false).expect("set_orientation");              // Configure 6D orientation, (generator=1, position=false, four_d=false)
                                                                                  // 6D movement recognition with all six direction zones
    chip.set_latch(1, true).expect("set_latch");                                  // Latch generator interrupt, (generator=1, enabled=true)
                                                                                  // CTRL_REG5_A LIR_INT1: holds INT1 until source is read
    chip.set_click(1, 4, 0.5, 10.0, 20.0, 50.0).expect("set_click");              // Configure click detection, (single=1 X, double=4 Z, threshold_g=0.5 g, time_limit_ms=10 ms, latency_ms=20 ms, window_ms=50 ms)
                                                                                  // CLICK_CFG/THS + TIME_LIMIT/LATENCY/WINDOW registers
    chip.set_interrupt_polarity(false).expect("set_interrupt_polarity");          // Set INT polarity, (active_low=false)
                                                                                  // CTRL_REG6_A H_LACTIVE: INT1/INT2 active-high
    chip.enable_interrupt(LSM303_SOURCE_DATA_READY, 1).expect("enable_interrupt"); // Route source to INT pin, (source, pin=1)
                                                                                   // CTRL_REG3_A I1_DRDY1: new-sample signal on INT1
    chip.enable_interrupt(SOURCE_IA1, 1).expect("enable_interrupt");              // Route source to INT pin, (source, pin=1)
                                                                                  // CTRL_REG3_A I1_AOI1: generator 1 events on INT1
    chip.enable_interrupt(SOURCE_CLICK, 2).expect("enable_interrupt");            // Route source to INT pin, (source, pin=2)
                                                                                  // CTRL_REG6_A I2_CLICKen: clicks on INT2
    chip.set_mag_range(810).expect("set_mag_range");                              // Set magnetometer full scale, (range_ut=810 µT)
                                                                                  // CRB_REG_M GN bits; updates cached conversion gains
    chip.set_mag_data_rate(30.0).expect("set_mag_data_rate");                     // Set magnetometer data rate, (rate_hz=30 Hz)
                                                                                  // RMW CRA_REG_M DO bits, keeping TEMP_EN
    chip.set_mag_mode(0).expect("set_mag_mode");                                  // Set magnetometer mode, (mode=0 continuous)
                                                                                  // MR_REG_M MD bits: continuous conversion
    let (mx, my, mz) = chip.read_magnetic_raw().expect("read_magnetic_raw");      // Read raw magnetic counts, () → (i16, i16, i16) counts
                                                                                  // Signed 12-bit counts, X/Y/Z order after reordering
    let (drdy, lock) = chip.read_mag_status().expect("read_mag_status");          // Read magnetometer status, () → (bool drdy, bool lock)
                                                                                  // SR_REG_M: new data available, output block locked
    let identity = chip.read_mag_identity().expect("read_mag_identity");          // Read magnetometer identity, () → [u8; 3]
                                                                                  // IRA/IRB/IRC = 0x48 0x34 0x33 (same as HMC5883L)
    chip.enable_temperature(true).expect("enable_temperature");                   // Enable temperature sensor, (enabled=true)
                                                                                  // RMW CRA_REG_M TEMP_EN; updates at the mag data rate
    let temperature = chip.read_temperature_delta().expect("read_temperature_delta"); // Read relative temperature, () → f32 °C
                                                                                  // 8 LSB/°C, relative to an unspecified reference
    let heading = chip.heading(true).expect("heading");                           // Compute compass heading, (tilt_compensate=true) → f32 °
                                                                                  // Tilt-compensated atan2(y, x), wrapped to 0–360°
    let status = chip.poll_interrupt().expect("poll_interrupt");                  // Read and clear interrupt sources, () → u16 status
                                                                                  // Combined SOURCE_* bits; clears latched flags
    let source = chip.read_interrupt_source(1).expect("read_interrupt_source");   // Read generator source, (generator=1) → u8
                                                                                  // INT1_SOURCE_A: IA + axis flags; clears the interrupt
    let click = chip.read_click_source().expect("read_click_source");             // Read click source, () → u8
                                                                                  // CLICK_SRC_A: IA/DCLICK/SCLICK + axis; clears it
    let (ax, ay, az) = chip.read_acceleration_raw().expect("read_acceleration_raw"); // Read raw acceleration counts, () → (i16, i16, i16) counts
                                                                                  // 12/10/8-bit right-aligned per mode
    let available = chip.new_data_available().expect("new_data_available");       // Check new accelerometer sample, () → bool
                                                                                  // STATUS_REG_A.ZYXDA
    let status_a = chip.read_status().expect("read_status");                      // Read accelerometer status, () → u8
                                                                                  // STATUS_REG_A byte: overrun + data-ready flags
    let fifo_status = chip.read_fifo_status().expect("read_fifo_status");         // Read FIFO status, () → (bool wtm, bool ovrn, bool empty, u8 level)
                                                                                  // Decoded FIFO_SRC_REG_A
    let mag_ready = chip.mag_data_ready().expect("mag_data_ready");               // Check new magnetometer sample, () → bool
                                                                                  // SR_REG_M.DRDY
    chip.disable_interrupt(SOURCE_IA1, 1).expect("disable_interrupt");            // Remove source from INT pin, (source, pin=1)
                                                                                  // Clears CTRL_REG3_A I1_AOI1
    chip.reboot(&mut delay).expect("reboot");                                     // Reboot memory content, (delay)
                                                                                  // CTRL_REG5_A BOOT: reload factory trim, wait ≥ 5 ms
    println!("reference={} fifo_level={} raw_mag=({},{},{}) drdy={} lock={} id={:?} T={:.1} C heading={:.1} status=0x{:04X} int1_src=0x{:02X} click_src=0x{:02X} raw_acc=({},{},{}) zyxda={} status_a=0x{:02X} fifo_status={:?} mag_drdy={}",
             reference, level, mx, my, mz, drdy, lock, identity, temperature, heading, status, source, click, ax, ay, az, available, status_a, fifo_status, mag_ready);
}
