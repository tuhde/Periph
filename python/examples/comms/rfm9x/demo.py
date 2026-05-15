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
rfm = RFM95Full(transport, 868_000_000, reset_pin, dio0_pin)

# --- Configure for long-range desk link ---
# SF7 gives good balance of range and data rate; 125 kHz BW is ISM band default;
# 4/5 coding rate is standard; CRC ensures payload integrity.
rfm.configure(sf=7, bandwidth_khz=125, coding_rate=5, crc=True)  # Configure modem, (sf 6-12, bandwidth_khz, coding_rate 5-8, crc=True) → None

# +17 dBm is safe maximum for PA_BOOST without active cooling.
rfm.set_tx_power(17, use_pa_boost=True)                              # Set TX power, (power_dbm 2-20, use_pa_boost=True) → None

# --- Ping-pong exchange loop ---
# Send an incrementing counter, then wait up to 2s for an echo back.
# print round-trip time, RSSI, and SNR for each successful exchange.
counter = 0
successes = 0
failures = 0

for i in range(10):
    tx_payload = bytes([(counter >> 8) & 0xFF, counter & 0xFF])
    tx_start = time.ticks_ms()
    rfm.send(tx_payload)                                       # Transmit packet, (data: bytes) → None

    rx = rfm.receive(timeout_ms=2000)                          # Receive packet, (timeout_ms=2000, use_interrupt=False) → bytes | None
    round_trip = time.ticks_diff(time.ticks_ms(), tx_start)

    if rx:
        rssi = rfm.last_packet_rssi()                        # Read packet RSSI, () → float dBm
        snr = rfm.last_packet_snr()                          # Read packet SNR, () → float dB
        print('seq={} rtt={}ms rssi={:.1f} snr={:.1f}'.format(counter, round_trip, rssi, snr))
        successes += 1
    else:
        print('seq={} timeout'.format(counter))
        failures += 1

    counter = (counter + 1) & 0xFFFF
    time.sleep_ms(100)

print('=== {} success, {} lost ==='.format(successes, failures))