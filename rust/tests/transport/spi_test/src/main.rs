use linux_embedded_hal::Spidev;
use spidev::{SpidevOptions, SpiModeFlags};
use embedded_hal_bus::spi::ExclusiveDevice;
use periph::transport::spi::SpiDevice;

fn main() {
    let mut spi = Spidev::open("/dev/spidev0.0").expect("open spidev");
    spi.configure(&SpidevOptions::new()
        .max_speed_hz(1_000_000)
        .mode(SpiModeFlags::SPI_MODE_0)
        .build()).expect("configure spidev");

    let cs_pin = linux_embedded_hal::Pin::new(25);
    let device = ExclusiveDevice::new_no_delay(spi, cs_pin).expect("create device");

    let mut passed = 0i32;
    let mut failed = 0i32;

    let tx_data = [0x01u8, 0x02, 0x03];
    device.write(&tx_data).expect("write");
    println!("PASS write completed");
    passed += 1;

    let mut rx_buf = [0u8; 3];
    device.read(&mut rx_buf).expect("read");
    println!("PASS read completed");
    passed += 1;

    let cmd = [0x55u8, 0xAA];
    let mut resp = [0u8; 2];
    device.transaction(&mut [
        embedded_hal::spi::Operation::Write(&cmd),
        embedded_hal::spi::Operation::Read(&mut resp),
    ]).expect("write_read");
    println!("PASS write_read completed");
    passed += 1;

    println!("===DONE: {} passed, {} failed===", passed, failed);
}