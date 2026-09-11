"""RFM95W demo — two-node round-trip link test.

Hardware: two RFM95W modules wired back-to-back (or two boards running
the same code). Both configured for 868 MHz, SF=7, BW=125 kHz, CR 4/5.

The demo runs 10 TX/RX iterations: transmits an incrementing 4-byte
counter, then immediately waits up to 1 s for the peer to echo it back.
Prints the round-trip time and per-packet RSSI/SNR on success, then
reports the total packet loss.
"""
from machine import SPI, Pin
from periph.connection.spi_micropython import SPIConnection
from periph.chips.comms.rfm9x import RFM95Full
import time
import struct

spi = SPI(1, baudrate=5_000_000, polarity=0, phase=0)
cs = Pin(5, Pin.OUT)
connection = SPIConnection(spi, cs)
radio = RFM95Full(connection, 868_000_000)                   # Create RFM95W driver, (connection, frequency_hz=868e6) → RFM95Full

# --- Configure for short-range link test ---
# SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s window;
# +17 dBm on PA_BOOST gives enough link margin for a desk-top loop-back.
radio.configure(sf=7, bandwidth_khz=125.0, coding_rate=5)   # Configure LoRa modem, (sf=7, bandwidth_khz=125.0, coding_rate=5) → None
radio.set_tx_power(17, use_pa_boost=True)                    # Set TX power, (power_dbm=17, use_pa_boost=True) → None

LOSS = 0
TOTAL = 10

for n in range(TOTAL):
    # --- Send a 4-byte big-endian counter ---
    tx_bytes = struct.pack(">I", n)
    t0 = time.ticks_ms()
    radio.send(tx_bytes)                                     # Send packet, (data=bytes ≤255 B) → None

    # --- Immediately listen for the echo from the peer ---
    pkt = radio.receive(timeout_ms=1000)                     # Receive single packet, (timeout_ms=1000) → bytes | None
    t1 = time.ticks_ms()

    if pkt is not None and pkt == tx_bytes:
        rssi = radio.last_packet_rssi()                      # Last packet RSSI, () → float dBm
        snr  = radio.last_packet_snr()                       # Last packet SNR, () → float dB
        print("[%2d] echo=%d B  rtt=%d ms  rssi=%.1f dBm  snr=%.1f dB"
              % (n, len(pkt), time.ticks_diff(t1, t0), rssi, snr))
    else:
        LOSS += 1
        print("[%2d] no echo  pkt=%s" % (n, pkt))

    time.sleep(0.2)

# --- Report the link quality summary ---
print("done — %d/%d successful, %d lost" % (TOTAL - LOSS, TOTAL, LOSS))
