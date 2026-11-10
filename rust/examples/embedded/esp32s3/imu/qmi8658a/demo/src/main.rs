#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::qmi8658a::{Qmi8658aFull, SOURCE_TAP};

esp_app_desc!();

const ADDR: u8 = 0x6B;

#[esp_hal::main]
fn main() -> ! {
    let peripherals = esp_hal::init(esp_hal::Config::default());

    let i2c = I2c::new(peripherals.I2C0, Config::default())
        .unwrap()
        .with_sda(peripherals.GPIO1)
        .with_scl(peripherals.GPIO2);
    let mut delay = Delay::new();

    // Create QMI8658A driver, (i2c, ADDR, delay) → Result
    let mut imu = Qmi8658aFull::new(i2c, ADDR, &mut delay).expect("init QMI8658A");

    // --- Identify the part and run the built-in checks ---
    // The revision, firmware version and USID identify the silicon; the
    // self-tests need the board lying still and the gyro calibration trims the
    // X/Y gains before real use.
    // Read device info, (delay) → Result<(u8, [u8; 3], [u8; 6])>
    let (revision, firmware, usid) = imu.device_info(&mut delay).expect("device info");
    println!("revision 0x{:02X} firmware {:?} usid {:?}", revision, firmware, usid);
    // Run accel self-test, (delay) → Result<(bool, [f32; 3])> m/s²
    let (accel_ok, _) = imu.self_test_accel(&mut delay).expect("accel self-test");
    println!("accel self-test: {}", if accel_ok { "PASS" } else { "FAIL" });
    // Run gyro self-test, (delay) → Result<(bool, [f32; 3])> rad/s
    let (gyro_ok, _) = imu.self_test_gyro(&mut delay).expect("gyro self-test");
    println!("gyro self-test: {}", if gyro_ok { "PASS" } else { "FAIL" });
    // Calibrate gyroscope, (delay) → Result<(bool, u8)>
    let (cal_ok, cod_status) = imu.calibrate_gyro(&mut delay).expect("calibrate");
    println!("gyro calibration: {} (0x{:02X})", if cal_ok { "OK" } else { "FAILED" }, cod_status);

    // --- Configure ±4 g / ±512 dps at 112.1 Hz ---
    // ODR code 6 gives 112.1 Hz for both sensors; mode 3 filters keep the
    // noise bandwidth near 15 Hz.
    // Configure accelerometer, (full_scale, odr) → Result
    imu.configure_accel(1, 6).expect("configure accel");
    // Configure gyroscope, (full_scale, odr) → Result
    imu.configure_gyro(5, 6).expect("configure gyro");
    // Configure low-pass filters, (accel_filter, accel_mode, gyro_filter, gyro_mode) → Result
    imu.configure_filters(true, 3, true, 3).expect("configure filters");

    // --- Step counting and tap detection ---
    // The pedometer defaults suit ~50 Hz, so the window parameters are scaled
    // for 112 Hz; the tap engine runs best above 200 Hz and is only a coarse
    // detector at this rate.
    // Configure pedometer, (sample_count, peak_to_peak_mg, peak_mg, time_up, time_low, entry_count, precision, report_every, delay) → Result
    imu.configure_pedometer(112, 200.0, 100.0, 450, 45, 10, 0, 1, &mut delay).expect("configure pedometer");
    // Enable pedometer, (enabled) → Result
    imu.enable_pedometer(true).expect("enable pedometer");
    // Configure tap engine, (priority, peak_window, tap_window, double_tap_window, alpha, gamma, peak_threshold, quiet_threshold, delay) → Result
    imu.configure_tap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4, &mut delay).expect("configure tap");
    // Enable tap engine, (enabled) → Result
    imu.enable_tap(true).expect("enable tap");

    // --- Stream into the FIFO ---
    // Stream mode with 32 samples and a watermark of 16 is drained once per
    // second; each sample is a 12-byte frame (accel XYZ then gyro XYZ).
    // Configure FIFO, (mode, size, watermark) → Result
    imu.configure_fifo(2, 1, 16).expect("configure fifo");
    delay.delay_ms(200);

    for iteration in 0..600 {
        // --- Sample, tilt and step count ---
        // Check for new data, () → Result<bool>
        if imu.data_ready().expect("data ready") {
            // Read one coherent sample, () → Result<((f32, f32, f32), (f32, f32, f32), f32)> m/s², rad/s, °C
            let ((ax, ay, az), (gx, gy, gz), temp) = imu.read_all().expect("read_all");
            let roll = libm::atan2f(ay, az).to_degrees();
            let pitch = libm::atan2f(-ax, libm::sqrtf(ay * ay + az * az)).to_degrees();
            let gmag = libm::sqrtf(gx * gx + gy * gy + gz * gz);
            // Read step count, () → Result<u32>
            let steps = imu.step_count().expect("step count");
            println!("roll {:.1} pitch {:.1} |gyro| {:.3} rad/s  {:.1} C  steps {}", roll, pitch, gmag, temp, steps);
        }

        // --- Report taps ---
        // poll_interrupt() flags a tap without the INT pin wired.
        // Poll interrupt status, () → Result<u16>
        let status = imu.poll_interrupt().expect("poll interrupt");
        if status & SOURCE_TAP != 0 {
            // Read tap event, () → Result<(u8, u8, bool)>
            let (taps, axis, _negative) = imu.tap_status().expect("tap status");
            println!("TAP {} on axis {}", if taps == 2 { "double" } else { "single" }, match axis { 1 => 'X', 2 => 'Y', 3 => 'Z', _ => '?' });
        }

        // --- Drain the FIFO once per second ---
        if iteration % 10 == 9 {
            let mut fifo = [0u8; 240];
            // Read FIFO fill, () → Result<u16> bytes
            let n = imu.fifo_count().expect("fifo count");
            // Drain the FIFO, (buf, delay) → Result<usize>
            let got = imu.read_fifo(&mut fifo, &mut delay).expect("read fifo");
            println!("FIFO: {} bytes ({}), {} frames", n, got, got / 12);
        }
        delay.delay_ms(100);
    }

    loop {
        delay.delay_ms(1000);
    }
}
