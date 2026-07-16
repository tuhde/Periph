// To use I2C (DDC) instead of UART:
//   use linux_embedded_hal::I2cdev;
//   use periph::chips::gnss::I2cBus;
//   let i2c = I2cdev::new("/dev/i2c-1").expect("open i2c bus");
//   let mut gps = Neo6Minimal::new(I2cBus { i2c, addr: 0x42 });
// To use SPI instead of UART (spidev manages CS via the kernel; see
// rust/tests/transport/spi_test/src/main.rs for the NullCs dummy-pin
// pattern used below):
//   use linux_embedded_hal::SpidevBus;
//   use embedded_hal_bus::spi::ExclusiveDevice;
//   use periph::chips::gnss::SpiBus;
//   let bus = SpidevBus(spidev::Spidev::open("/dev/spidev0.0").expect("open spi"));
//   let device = ExclusiveDevice::new_no_delay(bus, NullCs).expect("spi device");
//   let mut gps = Neo6Minimal::new(SpiBus(device));

use periph::chips::gnss::{Neo6Minimal, UartBus};
use periph::transport::uart_linux::LinuxUart;

fn main() {
    let port = std::env::var("UART_PORT").unwrap_or_else(|_| "/dev/ttyS0".to_string());
    let baud: u32 = std::env::var("UART_BAUD").ok().and_then(|v| v.parse().ok()).unwrap_or(9600);

    let uart = LinuxUart::open(&port, baud).expect("open serial port");
    let mut gps = Neo6Minimal::new(UartBus(uart)); // Create NEO-6 driver, (bus: UartBus/I2cBus/SpiBus)

    loop {
        if gps.update().expect("update") {
            // Read + parse one NMEA sentence, () → Result<bool, Error>
            println!("{:?} {:?} {:?}", gps.latitude(), gps.longitude(), gps.altitude());
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}
