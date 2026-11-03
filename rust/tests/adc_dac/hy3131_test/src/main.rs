use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::{Delay, SpidevBus};
use periph::chips::adc_dac::{Hy3131Full, Hy3131Minimal};
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
    let mut passed = 0i32;
    let mut failed = 0i32;

    let bus: u8 = std::env::var("SPI_BUS").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev: u8 = std::env::var("SPI_DEVICE").ok().and_then(|v| v.parse().ok()).unwrap_or(0);
    let dev_path = format!("/dev/spidev{}.{}", bus, dev);
    let mut spi = Spidev::open(dev_path).expect("open spi");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip_min = Hy3131Minimal::new(device, Delay, 1.2).expect("init HY3131 Minimal");
    let raw = chip_min.read_raw().expect("read raw");
    check_true!((-8_388_608..=8_388_607).contains(&raw), "read_raw_in_range", passed, failed);
    let v = chip_min.read_voltage().expect("read voltage");
    check_true!(v.is_finite(), "read_voltage_finite", passed, failed);
    check_true!(chip_min.data_ready().is_ok(), "data_ready_ok", passed, failed);
    let (spi, _) = chip_min.release();

    let mut chip = Hy3131Full::new(spi, Delay, 1.2).expect("init HY3131 Full");
    chip.configure_ad1(4, 2, 0, 0, 0, 0).expect("configure_ad1");
    check_true!(chip.read_raw().is_ok(), "read_raw_after_configure", passed, failed);
    chip.set_ad1_input(10, 3, 2).expect("input");
    chip.set_ad1_reference(1, 1).expect("ref");
    println!("PASS mux_accepted"); passed += 1;

    let tcode = chip.read_temperature_code().expect("tcode");
    check_true!(chip.read_temperature(tcode).is_ok(), "read_temperature_ok", passed, failed);

    chip.enable_ad2(5, 1, 0, false, false, 0, false).expect("ad2");
    let ad2 = chip.read_ad2_raw().expect("ad2 raw");
    check_true!((-262_144..=262_143).contains(&ad2), "read_ad2_raw_in_range", passed, failed);
    chip.enable_lpf(0).expect("lpf");
    check_true!(chip.read_lpf_raw().is_ok(), "read_lpf_raw_ok", passed, failed);
    chip.enable_rms().expect("rms");
    check_true!(chip.read_rms().map(|r| r >= 0.0).unwrap_or(false), "read_rms_nonneg", passed, failed);
    chip.enable_peak_hold(0).expect("pkh");
    let (lo, hi) = chip.read_peak().expect("peak");
    check_true!(lo <= hi, "read_peak_ordered", passed, failed);
    chip.enable_interrupts(0x04).expect("inte");
    check_true!(chip.read_flags().is_ok(), "read_flags_ok", passed, failed);
    chip.reset().expect("reset");
    println!("PASS reset_accepted"); passed += 1;

    println!("===DONE: {} passed, {} failed===", passed, failed);
    if failed > 0 { std::process::exit(1); }
}
