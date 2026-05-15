import time
from machine import SPI, Pin
from periph.transport.spi_micropython import SPITransport
from periph.chips.comms.rfm9x import RFM95Full

spi = SPI(1, baudrate=5000000, polarity=0, phase=0,
          sck=Pin(10), mosi=Pin(11), miso=Pin(12))
cs = Pin(13, Pin.OUT)
reset_pin = Pin(14, Pin.OUT)
dio0_pin = Pin(15, Pin.IN)
transport = SPITransport(spi, cs)
rfm = RFM95Full(transport, 868_000_000, reset_pin, dio0_pin)  # Create RFM95 driver, (transport, frequency_hz=868 MHz, reset_pin, dio0_pin)

version = rfm.version()                                        # Read silicon revision, () → int
print('version: 0x{:02X}'.format(version))
                                                           # checks silicon revision matches expected 0x12

rfm.configure(sf=7, bandwidth_khz=125, coding_rate=5, crc=True)  # Configure modem, (sf 6-12, bandwidth_khz, coding_rate 5-8, crc=True) → None
                                                           # sets spreading factor, bandwidth, coding rate, and CRC mode

rfm.set_tx_power(17, use_pa_boost=True)                              # Set TX power, (power_dbm 2-20, use_pa_boost=True) → None
                                                           # configures PA_BOOST pin for high-power transmission

rfm.set_frequency(915_000_000)                                      # Change carrier frequency, (frequency_hz int) → None
                                                           # switches to 915 MHz US band

rfm.send(b'Hello')                                                # Transmit packet, (data: bytes) → None
                                                           # enters TX mode, polls TxDone, returns to STDBY

packet = rfm.receive(timeout_ms=2000)                               # Receive packet, (timeout_ms=2000, use_interrupt=False) → bytes | None
if packet:
    print('received:', packet)
    rssi = rfm.last_packet_rssi()                                  # Read packet RSSI, () → float dBm
    snr = rfm.last_packet_snr()                                    # Read packet SNR, () → float dB
    print('rssi: {:.1f} dBm, snr: {:.1f} dB'.format(rssi, snr))
                                                           # shows link quality metrics for received packet

rfm.receive_continuous()                                           # Enter continuous receive mode, () → None
                                                           # keeps receiver always on, packets queued in FIFO

time.sleep_ms(500)
pkt = rfm.read_packet()                                           # Read packet from FIFO, () → bytes | None
if pkt:
    print('continuous rx:', pkt)

rfm.stop_receive()                                                # Return to STANDBY, () → None
                                                           # exits continuous receive mode

rssi_current = rfm.rssi()                                         # Read channel RSSI, () → float dBm
print('channel rssi: {:.1f} dBm'.format(rssi_current))

rfm.reset()                                                       # Hardware reset via NRESET pin, () → None
                                                           # pulses reset, re-initializes chip

rfm.standby()                                                     # Enter STANDBY mode, () → None

rfm.sleep()                                                       # Enter SLEEP mode, () → None
                                                           # puts chip in low-power sleep state