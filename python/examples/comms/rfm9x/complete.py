from machine import SPI, Pin
from periph.transport.spi_micropython import SPITransport
from periph.chips.comms.rfm9x import RFM95Full
import time

spi = SPI(1, baudrate=5_000_000, polarity=0, phase=0)
cs = Pin(5, Pin.OUT)
reset = Pin(14, Pin.OUT)
transport = SPITransport(spi, cs)
radio = RFM95Full(transport, 868_000_000, reset_pin=reset)

ver = radio.version()                                        # Read silicon revision, () → int
                                                            # expect 0x12 (SX1276)
print("version:", hex(ver))

radio.configure(sf=7, bandwidth_khz=125.0, coding_rate=5)   # Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500, coding_rate=5–8, crc=True) → None
                                                            # sets BW=125 kHz, SF7, CR 4/5, CRC on

radio.set_tx_power(17, use_pa_boost=True)                    # Set TX power, (power_dbm=2–20, use_pa_boost=True) → None
                                                            # PA_BOOST pin, +17 dBm

radio.set_frequency(868_000_000)                             # Change carrier frequency, (frequency_hz=862e6–1020e6) → None

radio.standby()                                             # Enter STDBY mode, () → None

radio.send(b"hello world")                                   # Send packet, (data=bytes ≤255 B) → None
                                                            # STDBY → fill FIFO → TX → poll TxDone → STDBY

pkt = radio.receive(timeout_ms=2000)                         # Receive single packet, (timeout_ms=2000) → bytes | None
                                                            # RXSINGLE → poll RxDone/RxTimeout → read FIFO
if pkt is not None:
    rssi = radio.last_packet_rssi()                          # Last packet RSSI, () → float dBm
                                                            # converted from RegPktRssiValue using -137 offset
    snr = radio.last_packet_snr()                            # Last packet SNR, () → float dB
                                                            # RegPktSnrValue is signed 8-bit × 0.25 dB
    print("packet:", pkt, "rssi=", rssi, "snr=", snr)

radio.receive_continuous()                                  # Enter continuous RX, () → None
while True:
    pkt = radio.read_packet()                               # Read buffered packet, () → bytes | None
                                                            # drains FIFO when RxDone fires
    if pkt is not None:
        rssi = radio.rssi()                                 # Current channel RSSI, () → float dBm
                                                            # readable while in continuous RX
        print("got:", pkt, "rssi=", rssi)
    time.sleep(0.05)
radio.stop_receive()                                        # Return to STDBY from RX_CONT, () → None

radio.sleep()                                               # Enter SLEEP mode, () → None
                                                            # lowest power; FIFO inaccessible
time.sleep(0.25)
radio.standby()
