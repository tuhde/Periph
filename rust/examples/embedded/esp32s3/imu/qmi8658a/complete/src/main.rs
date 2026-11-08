#![no_std]
#![no_main]

use esp_backtrace as _;
use esp_bootloader_esp_idf::esp_app_desc;
use embedded_hal::delay::DelayNs;
use esp_hal::delay::Delay;
use esp_hal::i2c::master::{Config, I2c};
use esp_println::println;
use periph::chips::imu::qmi8658a::{Qmi8658aFull, SOURCE_DATA_READY};

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

    // Read 3-axis acceleration, () → Result<(f32, f32, f32)> m/s²
    // converts raw counts at 16384 LSB/g (±2 g)
    let (ax, ay, az) = imu.accel().expect("accel");
    // Read 3-axis angular rate, () → Result<(f32, f32, f32)> rad/s
    // converts raw counts at 128 LSB/dps (±256 dps)
    let (gx, gy, gz) = imu.gyro().expect("gyro");
    // Read raw accel counts, () → Result<(i16, i16, i16)>
    // one 6-byte burst, signed 16-bit
    let (rax, ray, raz) = imu.accel_raw().expect("accel raw");
    // Read raw gyro counts, () → Result<(i16, i16, i16)>
    // one 6-byte burst, signed 16-bit
    let (rgx, rgy, rgz) = imu.gyro_raw().expect("gyro raw");
    // Read one coherent sample, () → Result<((f32, f32, f32), (f32, f32, f32), f32)> m/s², rad/s, °C
    // one 14-byte burst of registers 0x33…0x40
    let (accel, gyro, temp) = imu.read_all().expect("read_all");
    // Read die temperature, () → Result<f32> °C
    // signed 8.8 fixed point, raw / 256
    let t = imu.temperature().expect("temperature");
    // Read sample counter, () → Result<u32>
    // 24-bit counter, wraps at 0xFFFFFF
    let ts = imu.timestamp().expect("timestamp");
    // Check for new data, () → Result<bool>
    // STATUS0 aDA / gDA
    let ready = imu.data_ready().expect("data ready");
    // Configure accelerometer, (full_scale, odr) → Result
    // CTRL2: 0=±2, 1=±4, 2=±8, 3=±16 g; ODR code 5 = 224.2 Hz
    imu.configure_accel(1, 5).expect("configure accel");
    // Configure gyroscope, (full_scale, odr) → Result
    // CTRL3: 0=±16 … 7=±2048 dps
    imu.configure_gyro(5, 5).expect("configure gyro");
    // Configure low-pass filters, (accel_filter, accel_mode, gyro_filter, gyro_mode) → Result
    // CTRL5: mode 3 = 13.37 % of ODR
    imu.configure_filters(true, 3, true, 3).expect("configure filters");
    // Enable sensors, (accel, gyro, gyro_snooze) → Result
    // CTRL7 aEN / gEN / gSN
    imu.set_sensors(true, true, false).expect("set sensors");
    // Configure FIFO, (mode, size, watermark) → Result
    // stream mode, 32 samples, watermark 16
    imu.configure_fifo(2, 1, 16).expect("configure fifo");
    delay.delay_ms(500);
    // Read FIFO flags, () → Result<u8>
    // raw FIFO_STATUS byte
    let fifo_flags = imu.fifo_status().expect("fifo status");
    // Read FIFO fill, () → Result<u16> bytes
    // two bytes per sample word
    let fifo_bytes = imu.fifo_count().expect("fifo count");
    // Drain the FIFO, (buf, delay) → Result<usize>
    // REQ_FIFO, chunked FIFO_DATA reads, FIFO_RD_MODE cleared
    let mut fifo = [0u8; 96];
    let got = imu.read_fifo(&mut fifo, &mut delay).expect("read fifo");
    // Reset the FIFO, (delay) → Result
    // CTRL9 RST_FIFO
    imu.reset_fifo(&mut delay).expect("reset fifo");
    // Back to bypass, (mode, size, watermark) → Result
    // bypass re-enables data-ready
    imu.configure_fifo(0, 3, 0).expect("bypass");
    // Configure any-motion, (threshold_g, window, axes, logic_and, delay) → Result
    // two CTRL9 CONFIGURE_MOTION sets
    imu.configure_any_motion(0.25, 3, 0x07, false, &mut delay).expect("any motion");
    // Configure no-motion, (threshold_g, window, axes, logic_and, delay) → Result
    // same command, no-motion fields
    imu.configure_no_motion(0.125, 30, 0x07, true, &mut delay).expect("no motion");
    // Configure significant motion, (wait_window, confirm_window, delay) → Result
    // wait and confirm windows in samples
    imu.configure_significant_motion(150, 600, &mut delay).expect("significant motion");
    // Enable motion engines, (any_motion, no_motion, significant) → Result
    // CTRL8 bits 1–3
    imu.enable_motion(true, true, true).expect("enable motion");
    // Disable motion engines, (any_motion, no_motion, significant) → Result
    // clears CTRL8 bits 1–3
    imu.enable_motion(false, false, false).expect("disable motion");
    // Configure tap engine, (priority, peak_window, tap_window, double_tap_window, alpha, gamma, peak_threshold, quiet_threshold, delay) → Result
    // two CTRL9 CONFIGURE_TAP sets
    imu.configure_tap(0, 20, 50, 250, 0.0625, 0.25, 0.8, 0.4, &mut delay).expect("configure tap");
    // Enable tap engine, (enabled) → Result
    // CTRL8.Tap_EN
    imu.enable_tap(true).expect("enable tap");
    // Read tap event, () → Result<(u8, u8, bool)>
    // count 0/1/2, axis 0–3, polarity
    let (taps, axis, negative) = imu.tap_status().expect("tap status");
    // Disable tap engine, (enabled) → Result
    // clears CTRL8.Tap_EN
    imu.enable_tap(false).expect("disable tap");
    // Configure pedometer, (sample_count, peak_to_peak_mg, peak_mg, time_up, time_low, entry_count, precision, report_every, delay) → Result
    // two CTRL9 CONFIGURE_PEDOMETER sets (50 Hz values)
    imu.configure_pedometer(50, 200.0, 100.0, 200, 20, 10, 0, 4, &mut delay).expect("configure pedometer");
    // Enable pedometer, (enabled) → Result
    // CTRL8.Pedo_EN; 0→1 resets the count
    imu.enable_pedometer(true).expect("enable pedometer");
    // Read step count, () → Result<u32>
    // 24-bit count
    let steps = imu.step_count().expect("step count");
    // Clear step count, (delay) → Result
    // CTRL9 RESET_PEDOMETER
    imu.reset_step_count(&mut delay).expect("reset steps");
    // Disable pedometer, (enabled) → Result
    // clears CTRL8.Pedo_EN
    imu.enable_pedometer(false).expect("disable pedometer");
    // Set accel offset, (x, y, z, delay) m/s² → Result
    // signed 4.12 g via CTRL9
    imu.set_accel_offset(0.0, 0.0, 0.0, &mut delay).expect("accel offset");
    // Set gyro offset, (x, y, z, delay) rad/s → Result
    // signed 11.5 dps via CTRL9
    imu.set_gyro_offset(0.0, 0.0, 0.0, &mut delay).expect("gyro offset");
    // Calibrate gyroscope, (delay) → Result<(bool, u8)>
    // calibration-on-demand, ~1.5 s, keep still
    let (cal_ok, cod_status) = imu.calibrate_gyro(&mut delay).expect("calibrate");
    // Read gyro gains, () → Result<(u16, u16, u16)>
    // raw 16-bit gains from dVX…dVZ
    let (gain_x, gain_y, gain_z) = imu.get_gyro_gains().expect("gains");
    // Restore gyro gains, (x, y, z, delay) → Result
    // CTRL9 APPLY_GYRO_GAINS
    imu.set_gyro_gains(gain_x, gain_y, gain_z, &mut delay).expect("set gains");
    // Run accel self-test, (delay) → Result<(bool, [f32; 3])> m/s²
    // passes when every axis exceeds 200 mg
    let (accel_ok, accel_self) = imu.self_test_accel(&mut delay).expect("accel self-test");
    // Run gyro self-test, (delay) → Result<(bool, [f32; 3])> rad/s
    // passes when every axis exceeds 300 dps
    let (gyro_ok, gyro_self) = imu.self_test_gyro(&mut delay).expect("gyro self-test");
    // Read device info, (delay) → Result<(u8, [u8; 3], [u8; 6])>
    // CTRL9 COPY_USID: revision, firmware, usid
    let (revision, firmware, usid) = imu.device_info(&mut delay).expect("device info");
    // Control pull-ups, (aux, sdx, cs, i2c, delay) → Result
    // CTRL9 SET_RPU; false disables a group
    imu.set_pullups(true, true, true, true, &mut delay).expect("pullups");
    // Arm wake-on-motion, (threshold_mg, interrupt_pin, initial_level, blanking_samples, delay) → Result
    // accel 128 Hz ±2 g, CTRL9 WRITE_WOM_SETTING
    imu.set_wake_on_motion(100, 1, 0, 0, &mut delay).expect("wake on motion");
    // Leave wake-on-motion, (threshold_mg, interrupt_pin, initial_level, blanking_samples, delay) → Result
    // threshold 0 exits WoM mode
    imu.set_wake_on_motion(0, 1, 0, 0, &mut delay).expect("leave wake on motion");
    // Re-enable sensors, (accel, gyro, gyro_snooze) → Result
    // restores normal operation
    imu.set_sensors(true, true, false).expect("sensors");
    // Route interrupts, (fifo_pin, activity_pin) → Result
    // CTRL1.FIFO_INT_SEL and CTRL8.ACTIVITY_INT_SEL
    imu.set_interrupt_pins(2, 2).expect("interrupt pins");
    // Enable interrupt source, (source) → Result
    // clears DRDY_DIS, enables INT2
    imu.enable_interrupt(SOURCE_DATA_READY).expect("enable interrupt");
    // Poll interrupt status, () → Result<u16>
    // STATUS0, STATUS1 and FIFO_STATUS as SOURCE_* bits
    let status = imu.poll_interrupt().expect("poll interrupt");
    // Disable interrupt source, (source) → Result
    // sets DRDY_DIS, releases INT2
    imu.disable_interrupt(SOURCE_DATA_READY).expect("disable interrupt");
    // Enable SyncSample, (enabled, delay) → Result
    // locks each sample; reads wait for Avail and Locked
    imu.set_sync_sample(true, &mut delay).expect("sync on");
    // Disable SyncSample, (enabled, delay) → Result
    // restores the AHB clock gating
    imu.set_sync_sample(false, &mut delay).expect("sync off");
    // Power down, (down, delay) → Result
    // CTRL1.SensorDisable after disabling the sensors
    imu.set_power_down(true, &mut delay).expect("power down");
    // Wake, (down, delay) → Result
    // clears SensorDisable
    imu.set_power_down(false, &mut delay).expect("wake");
    // Re-enable sensors, (accel, gyro, gyro_snooze) → Result
    // needs 150 ms gyroscope turn-on
    imu.set_sensors(true, true, false).expect("sensors");
    delay.delay_ms(200);
    // Soft reset, (delay) → Result
    // RESET register, waits 15 ms
    imu.reset(&mut delay).expect("reset");
    println!("{:?} {:?} {:?} {} {} {} {} {} {}", accel, gyro, temp, t, ts, ready, ax + ay + az, gx + gy + gz, got);
    println!("{} {} {} {} {} {} {} {} {} {} {} {} {} {} {:?} {:?}", rax + ray + raz, rgx + rgy + rgz, fifo_flags, fifo_bytes, taps, axis, negative, steps, cal_ok, cod_status, accel_ok, gyro_ok, revision, status, accel_self, gyro_self);
    println!("{:?} {:?}", firmware, usid);
    println!("Complete example done");

    loop {
        delay.delay_ms(1000);
    }
}
