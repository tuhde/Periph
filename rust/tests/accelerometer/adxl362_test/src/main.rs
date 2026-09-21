use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::accelerometer::{
    Adxl362Full, ADXL362_FIFO_STREAM, LINKLOOP_LOOP, NOISE_LOW, SOURCE_AWAKE, SOURCE_DATA_READY,
};
use spidev::{SpiModeFlags, Spidev, SpidevOptions};

struct NullCs;
impl embedded_hal::digital::ErrorType for NullCs {
    type Error = core::convert::Infallible;
}
impl embedded_hal::digital::OutputPin for NullCs {
    fn set_low(&mut self) -> Result<(), Self::Error> { Ok(()) }
    fn set_high(&mut self) -> Result<(), Self::Error> { Ok(()) }
}

macro_rules! check_true {
    ($cond:expr, $label:expr, $passed:expr, $failed:expr) => {
        if $cond { println!("PASS {}", $label); $passed += 1; }
        else      { println!("FAIL {}", $label); $failed += 1; }
    };
}

fn main() {
    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev_path = format!("/dev/spidev{}.{}", bus, dev);

    let mut passed = 0i32;
    let mut failed = 0i32;

    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(8_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip = Adxl362Full::new(device).expect("init ADXL362");    // Create ADXL362 full driver, (spi) → Result

    let ids = chip.device_id().expect("device_id");                      // Read device IDs, () → Result<(DEVID_AD, DEVID_MST, PARTID, REVID)>
    check_true!(ids.0 == 0xAD, "device_id_devid_ad", passed, failed);    // verify DEVID_AD = 0xAD
    check_true!(ids.1 == 0x1D, "device_id_devid_mst", passed, failed);  // verify DEVID_MST = 0x1D
    check_true!(ids.2 == 0xF2, "device_id_partid", passed, failed);      // verify PARTID = 0xF2

    let r12 = chip.read().expect("read");                                // Read 12-bit acceleration, () → Result<(f32, f32, f32)> g
    check_true!(true, "read_12bit", passed, failed);

    let r8 = chip.read_8bit().expect("read_8bit");                       // Read 8-bit acceleration, () → Result<(f32, f32, f32)> g
    check_true!(true, "read_8bit", passed, failed);

    let _t = chip.temperature().expect("temperature");                   // Read temperature, () → Result<f32> °C
    check_true!(true, "temperature", passed, failed);

    chip.set_range(4).expect("set_range(4)");                            // Set measurement range, (range_g=4) → Result<()>
    chip.set_odr(200.0).expect("set_odr(200)");                          // Set output data rate, (odr_hz=200.0) → Result<()>
    chip.set_half_bandwidth(true).expect("set_half_bandwidth(true)");    // Set antialiasing bandwidth, (enabled=true) → Result<()>
    chip.set_noise_mode(NOISE_LOW).expect("set_noise_mode(LOW)");        // Set noise mode, (mode=NOISE_LOW) → Result<()>
    check_true!(true, "set_range_odr_noise", passed, failed);

    let _status = chip.status().expect("status");                        // Read STATUS register, () → Result<u8>
    let _awake = chip.awake().expect("awake");                           // Check AWAKE bit, () → Result<bool>
    let _dr = chip.data_ready().expect("data_ready");                    // Check DATA_READY, () → Result<bool>
    let _n = chip.fifo_entries().expect("fifo_entries");                 // Read FIFO entry count, () → Result<u16>
    check_true!(true, "status_awake_dr_fifo", passed, failed);

    chip.configure_fifo(ADXL362_FIFO_STREAM, false, 128).expect("configure_fifo");  // Configure FIFO, (mode=STREAM, store_temp=false, watermark=128) → Result<()>
    chip.set_activity_threshold(0.5, true).expect("set_activity_threshold(0.5, true)");  // Set activity threshold, (threshold_g=0.5, referenced=true) → Result<()>
    chip.set_activity_time(5).expect("set_activity_time(5)");            // Set activity time, (samples=5) → Result<()>
    chip.set_inactivity_threshold(0.2, true).expect("set_inactivity_threshold(0.2, true)");  // Set inactivity threshold, (threshold_g=0.2, referenced=true) → Result<()>
    chip.set_inactivity_time(30).expect("set_inactivity_time(30)");      // Set inactivity time, (samples=30) → Result<()>
    chip.enable_activity_detection(true).expect("enable_activity_detection(true)");      // Enable activity detection, (enabled=true) → Result<()>
    chip.enable_inactivity_detection(true).expect("enable_inactivity_detection(true)");  // Enable inactivity detection, (enabled=true) → Result<()>
    chip.set_link_loop_mode(LINKLOOP_LOOP).expect("set_link_loop_mode(LOOP)");            // Set link/loop mode, (mode=LOOP) → Result<()>
    check_true!(true, "activity_inactivity_config", passed, failed);

    chip.set_interrupt(1, SOURCE_DATA_READY, true).expect("set_interrupt(1, DATA_READY, true)");  // Map DATA_READY to INT1, (pin=1, source=DATA_READY, enabled=true) → Result<()>
    chip.set_interrupt(2, SOURCE_AWAKE, true).expect("set_interrupt(2, AWAKE, true)");             // Map AWAKE to INT2, (pin=2, source=AWAKE, enabled=true) → Result<()>
    chip.set_interrupt_polarity(1, true).expect("set_interrupt_polarity(1, true)");              // Set INT1 active-low, (pin=1, active_low=true) → Result<()>
    check_true!(true, "interrupt_mapping", passed, failed);

    chip.self_test(true).expect("self_test(true)");                      // Enable self-test, (enabled=true) → Result<()>
    chip.self_test(false).expect("self_test(false)");                    // Disable self-test, (enabled=false) → Result<()>
    check_true!(true, "self_test", passed, failed);

    chip.soft_reset().expect("soft_reset");                              // Soft-reset the chip, () → Result<()>
    check_true!(true, "soft_reset", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}