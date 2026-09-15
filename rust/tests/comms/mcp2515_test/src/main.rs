use embedded_hal_bus::spi::ExclusiveDevice;
use linux_embedded_hal::SpidevBus;
use periph::chips::comms::{
    MCP2515Full, OPMOD_NORMAL, OPMOD_LOOPBACK,
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
        .max_speed_hz(5_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spi");
    let bus_obj = SpidevBus(spi);
    let device = ExclusiveDevice::new_no_delay(bus_obj, NullCs).expect("spi device");

    let mut chip = MCP2515Full::new(device, 125, 8).expect("init MCP2515");    // Create MCP2515 full driver, (spi, bitrate_kbps=125, osc_mhz=8) → Result

    let mode = chip.get_mode().unwrap_or(0xFF);                                 // Read current OPMOD, () → Result<u8>
    check_true!(mode == OPMOD_NORMAL, "mode_is_normal_after_init", passed, failed);

    chip.set_one_shot(false).expect("set_one_shot(false)");                     // Disable one-shot mode, (enable=false) → Result<()>
    chip.set_one_shot(true).expect("set_one_shot(true)");                       // Enable one-shot mode, (enable=true) → Result<()>
    chip.set_one_shot(false).expect("set_one_shot(false)");                     // Disable one-shot mode, (enable=false) → Result<()>
    check_true!(true, "set_one_shot_accepted", passed, failed);

    chip.set_mask(0, 0, false).expect("set_mask(0)");                           // Configure mask 0, (mask_num=0, mask=0, extended=false) → Result<()>
    chip.set_mask(1, 0, false).expect("set_mask(1)");                           // Configure mask 1, (mask_num=1, mask=0, extended=false) → Result<()>
    check_true!(true, "set_mask_accepted", passed, failed);

    chip.set_filter(0, 0, false).expect("set_filter(0)");                       // Configure filter 0, (filter_num=0, id=0, extended=false) → Result<()>
    chip.set_filter(5, 0x7FF, false).expect("set_filter(5)");                   // Configure filter 5, (filter_num=5, id=0x7FF, extended=false) → Result<()>
    check_true!(true, "set_filter_accepted", passed, failed);

    chip.set_rx_mode(0, 3).expect("set_rx_mode(0,3)");                         // Set RXB0 filter mode, (buf=0, mode=3=accept_all) → Result<()>
    chip.set_rx_mode(1, 3).expect("set_rx_mode(1,3)");                         // Set RXB1 filter mode, (buf=1, mode=3=accept_all) → Result<()>
    check_true!(true, "set_rx_mode_accepted", passed, failed);

    chip.set_mode(OPMOD_LOOPBACK).expect("set_mode(loopback)");                 // Enter loopback mode, (mode=OPMOD_LOOPBACK) → Result<()>
    check_true!(chip.get_mode().unwrap_or(0xFF) == OPMOD_LOOPBACK, "mode_is_loopback", passed, failed);

    let buf = chip.send_buffered(0x123, &[0x01, 0x02], false, 0).expect("send_buffered");  // Send on TXB0, (id=0x123, data, extended=false, buf=0) → Result<u8>
    check_true!(buf <= 2, "send_buffered_returns_valid_buf", passed, failed);

    let _ = chip.recv(50).expect("recv");                                       // Poll for received frame, (timeout_ms=50) → Result<Option<CanFrame>>
    check_true!(true, "recv_accepted", passed, failed);

    let errors = chip.read_errors().expect("read_errors");                      // Read TEC/REC/EFLG, () → Result<(u8, u8, u8)>
    check_true!(errors.0 < 0xFF && errors.1 < 0xFF, "read_errors_in_range", passed, failed);

    chip.abort_tx().expect("abort_tx");                                         // Abort pending TX, () → Result<()>
    check_true!(true, "abort_tx_accepted", passed, failed);

    chip.clear_overflow(0).expect("clear_overflow(0)");                         // Clear RXB0 overflow flag, (buf=0) → Result<()>
    chip.clear_overflow(1).expect("clear_overflow(1)");                         // Clear RXB1 overflow flag, (buf=1) → Result<()>
    check_true!(true, "clear_overflow_accepted", passed, failed);

    chip.reset().expect("reset");                                               // Issue SPI RESET, () → Result<()>
    check_true!(true, "reset_accepted", passed, failed);

    chip.set_mode(OPMOD_NORMAL).expect("set_mode(normal)");                     // Enter normal mode, (mode=OPMOD_NORMAL) → Result<()>
    check_true!(chip.get_mode().unwrap_or(0xFF) == OPMOD_NORMAL, "mode_is_normal_again", passed, failed);

    println!("===DONE: {} passed, {} failed===", passed, failed);
    std::process::exit(if failed == 0 { 0 } else { 1 });
}
