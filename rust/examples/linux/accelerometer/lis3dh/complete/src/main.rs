use linux_embedded_hal::{Delay, I2cdev};
use periph::chips::accelerometer::{
    Lis3dhFull, LIS3DH_FIFO_FIFO, LIS3DH_MODE_HIGH_RESOLUTION, LIS3DH_MODE_NORMAL,
    LIS3DH_SOURCE_CLICK, LIS3DH_SOURCE_DATA_READY, LIS3DH_SOURCE_IA1,
};
use periph::connection::Connection;

fn main() {
    let i2c_bus: u8 = std::env::var("I2C_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(1);
    let addr: u8 = std::env::var("I2C_ADDR")
        .ok()
        .and_then(|v| u8::from_str_radix(v.trim_start_matches("0x"), 16).ok())
        .unwrap_or(0x18);

    let dev = I2cdev::new(format!("/dev/i2c-{}", i2c_bus)).expect("open i2c bus");
    // Create LIS3DH Full driver, (connection, addr=0x18, delay) → Result
    let mut chip = Lis3dhFull::new(Connection::new(dev), addr, Delay).expect("init LIS3DH");

    // --- Range, mode, data rate, axes ---
    // Set full-scale range, (range_g=2) → Result
    chip.set_range(8).expect("set_range");
    // reprograms FS in CTRL_REG4; the sensitivity becomes 4 mg/LSB
    // Set operating mode, (mode=2) → Result
    chip.set_mode(LIS3DH_MODE_NORMAL).expect("set_mode");
    // 12-bit high-resolution → 10-bit normal (LPen = 0, HR = 0)
    // Set output data rate, (rate_hz=100) → Result
    chip.set_data_rate(400).expect("set_data_rate");
    // ODR = 400 Hz; the driver waits one ODR period for the converter
    // Enable measurement axes, (x, y, z) → Result
    chip.set_axes(true, true, true).expect("set_axes");
    // Xen / Yen / Zen all set in CTRL_REG1

    // --- Power cycle and restore the defaults ---
    // Stop conversions, () → Result
    chip.power_down().expect("power_down");
    // ODR = 0000; the control registers are retained
    // Set output data rate, (rate_hz=100) → Result
    chip.set_data_rate(100).expect("set_data_rate");
    // leave power-down again at 100 Hz
    // Set operating mode, (mode=2) → Result
    chip.set_mode(LIS3DH_MODE_HIGH_RESOLUTION).expect("set_mode");
    // back to high-resolution 12-bit mode
    // Set full-scale range, (range_g=2) → Result
    chip.set_range(2).expect("set_range");
    // back to ±2 g (1 mg/LSB)

    // --- Reads ---
    // Read 3-axis acceleration, () → (f32, f32, f32) g
    let (x, y, z) = chip.read().expect("read");
    // 6-byte burst of 0x28-0x2D, scaled to g
    // Read raw counts, () → (i16, i16, i16) LSB
    let (rx, ry, rz) = chip.read_raw().expect("read_raw");
    // signed counts at the current resolution (12-bit here)
    // Check new data, () → bool
    let ready = chip.new_data_available().expect("new_data_available");
    // STATUS_REG.ZYXDA — a fresh sample set is ready
    // Read STATUS register, () → u8
    let status = chip.read_status().expect("read_status");
    // overrun and data-available flags

    // --- Auxiliary ADC and temperature ---
    // Enable auxiliary ADC, (enabled) → Result
    chip.enable_adc(true).expect("enable_adc");
    // sets ADC_EN in TEMP_CFG_REG
    // Read aux ADC channel, (channel=1) → f32 V
    let v1 = chip.read_adc(1).expect("read_adc");
    // 10-bit conversion mapped onto the ±400 mV input range
    // Read aux ADC channel, (channel=2) → f32 V
    let v2 = chip.read_adc(2).expect("read_adc");
    // second auxiliary input
    // Read aux ADC channel, (channel=3) → f32 V
    let v3 = chip.read_adc(3).expect("read_adc");
    // temperature input while TEMP_EN = 0
    // Disable auxiliary ADC, (enabled) → Result
    chip.enable_adc(false).expect("enable_adc");
    // clears ADC_EN again
    // Read relative temperature, () → i8 °C
    let temp = chip.read_temperature_delta().expect("read_temperature_delta");
    // enables ADC_EN + TEMP_EN on first use, 1 digit/°C

    // --- High-pass filter ---
    // Configure high-pass filter, (mode, cutoff, filter_output, on_ia1, on_ia2, on_click) → Result
    chip.set_high_pass(1, 2, true, true, false, true).expect("set_high_pass");
    // HPM = 01, HPCF = 10, filtered data routed to the output, IA1 and click
    // Reset high-pass filter, () → u8
    let ref_val = chip.reset_high_pass().expect("reset_high_pass");
    // reading REFERENCE resets the filter in HPM = 00
    // Set high-pass reference, (value) → Result
    chip.set_reference(0x40).expect("set_reference");
    // REFERENCE = 0x40 for HPM = 01

    // --- FIFO ---
    // Configure FIFO, (mode, watermark, trigger_int2) → Result
    chip.set_fifo(LIS3DH_FIFO_FIFO, 16, false).expect("set_fifo");
    // FIFO mode with a 16-sample watermark; FIFO_EN is set
    // Read FIFO level, () → u8
    let level = chip.fifo_level().expect("fifo_level");
    // FSS<4:0> — unread sample sets in the buffer
    // Read FIFO status, () → (bool, bool, bool, u8)
    let (watermark, overrun, empty, lvl) = chip.read_fifo_status().expect("read_fifo_status");
    // WTM / OVRN_FIFO / EMPTY flags plus the level
    // Drain FIFO, (count) → Vec<(f32, f32, f32)> g
    let samples = chip.read_fifo(None).expect("read_fifo");
    // one 6-byte burst per sample set, oldest first
    // Configure FIFO, (mode, watermark, trigger_int2) → Result
    chip.set_fifo(0, 0, false).expect("set_fifo");
    // back to bypass, which clears the buffer

    // --- Inertial interrupt generators ---
    // Configure motion interrupt, (generator, events, threshold_g, duration_ms, and_mode) → Result
    chip.set_motion_interrupt(1, 0x3F, 0.5, 30.0, true).expect("set_motion_interrupt");
    // generator 1: all six events, 0.5 g, 30 ms, AND combined
    // Configure free-fall, (generator, threshold_g, duration_ms) → Result
    chip.set_free_fall(1, 0.35, 60.0).expect("set_free_fall");
    // INT1_CFG = 0x95 (three axes low, AND), 0.35 g, 60 ms
    // Configure 6D/4D orientation, (generator, position, four_d) → Result
    chip.set_orientation(1, true, true).expect("set_orientation");
    // position recognition with the Z-axis zones disabled
    // Latch generator, (generator, enabled) → Result
    chip.set_latch(1, true).expect("set_latch");
    // LIR_INT1 — held until INT1_SRC is read
    // Read generator source, (generator) → u8
    let src = chip.read_interrupt_source(1).expect("read_interrupt_source");
    // INT1_SRC; reading clears the interrupt

    // --- Click detection ---
    // Configure click detection, (single, double, threshold_g, time_limit_ms, latency_ms, window_ms, latch) → Result
    chip.set_click(0x07, 0x04, 0.8, 20.0, 40.0, 200.0, true).expect("set_click");
    // single click on X/Y/Z, double click on Z, latched
    // Read click source, () → u8
    let click = chip.read_click_source().expect("read_click_source");
    // CLICK_SRC; reading clears the click interrupt

    // --- Activity and polarity ---
    // Configure sleep-to-wake activity, (threshold_g, duration_ms) → Result
    chip.set_activity(0.5, 100.0).expect("set_activity");
    // ACT_THS = 31 counts, ACT_DUR scaled by the ODR
    // Set INT polarity, (active_low) → Result
    chip.set_interrupt_polarity(true).expect("set_interrupt_polarity");
    // INT_POLARITY = 1 — active-low INT1 / INT2

    // --- Interrupt routing and delivery ---
    // Route source to INT pin, (source, pin) → Result
    chip.enable_interrupt(LIS3DH_SOURCE_CLICK, 1).expect("enable_interrupt");
    // click interrupt → INT1 (I1_CLICK)
    // Route source to INT pin, (source, pin) → Result
    chip.enable_interrupt(LIS3DH_SOURCE_IA1, 1).expect("enable_interrupt");
    // generator 1 → INT1 (I1_IA1)
    // Route source to INT pin, (source, pin) → Result
    chip.enable_interrupt(LIS3DH_SOURCE_DATA_READY, 1).expect("enable_interrupt");
    // data-ready → INT1 (I1_ZYXDA); the pin is ignored for INT1-only sources
    // Read interrupt status, () → u16
    let int_status = chip.poll_interrupt().expect("poll_interrupt");
    // combined status of every enabled source (clears the latched flags)
    // Remove source from INT pin, (source, pin) → Result
    chip.disable_interrupt(LIS3DH_SOURCE_DATA_READY, 1).expect("disable_interrupt");
    // clears I1_ZYXDA in CTRL_REG3

    // --- System ---
    // Connect or disconnect the SDO pull-up, (connected) → Result
    chip.set_sdo_pullup(false).expect("set_sdo_pullup");
    // CTRL_REG0 bit 7 set, bit 4 forced to 1
    // Reboot memory content, () → Result
    chip.reboot().expect("reboot");
    // BOOT = 1, 5 ms wait, then the init sequence runs again
    // Run self-test, (mode) → bool
    let st = chip.self_test(1).expect("self_test");
    // ±2 g / normal / 50 Hz; 17-360 LSb change on every axis

    println!(
        "x={:.3} y={:.3} z={:.3} raw=({}, {}, {}) ready={} status=0x{:02X} temp={}",
        x, y, z, rx, ry, rz, ready, status, temp
    );
    println!(
        "adc=({:.4}, {:.4}, {:.4}) V ref={} level={} samples={} click={} src={} fifo=({}, {}, {}, {}) self_test={}",
        v1, v2, v3, ref_val, level, samples.len(), click, src, watermark, overrun, empty, lvl,
        st
    );
    println!("interrupt status=0x{:04X}", int_status);
}
