//go:build tinygo

// RFM95W demo — two-node round-trip link test — TinyGo / Raspberry Pi
// Pico W.
//
// Hardware: two RFM95W modules wired back-to-back (or two Pico Ws running
// the same code). Both configured for 868 MHz, SF=7, BW=125 kHz, CR 4/5.
// SPI0 on GP18=SCK, GP19=SDO, GP16=SDI, GP17=CS.
//
// The demo runs 10 TX/RX iterations: transmits an incrementing 4-byte
// counter, then immediately waits up to 1 s for the peer to echo it back.
// Prints the round-trip time and per-packet RSSI/SNR on success, then
// reports the total packet loss.
package main

import (
	"encoding/binary"
	"time"

	"machine"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	spi := machine.SPI0
	if err := spi.Configure(machine.SPIConfig{
		Frequency: 5_000_000,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
		Mode:      0,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewSPIConnection(spi, machine.GP17, nil, nil) // Create SPI connection, (spi, cs=GP17) → (*SPIConnection)
	radio, err := comms.NewRFM95Full(conn, 868_000_000, nil, nil)    // Create RFM95W driver, (connection, frequency_hz=868e6, reset_pin=nil, dio0_pin=nil) → (*RFM95Full, error)
	if err != nil {
		panic(err)
	}

	// --- Configure for short-range link test ---
	// SF7 / 125 kHz / 4/5 keeps airtime low so the round-trip fits in a 1 s
	// window; +17 dBm on PA_BOOST gives enough link margin for a desk-top
	// loop-back.
	if err := radio.Configure(7, 125.0, 5, true); err != nil { // Configure LoRa modem, (sf=7, bandwidth_khz=125.0, coding_rate=5, crc=true) → error
		panic(err)
	}
	if err := radio.SetTxPower(17, true); err != nil { // Set TX power, (power_dbm=17, use_pa_boost=true) → error
		panic(err)
	}

	const total = 10
	loss := 0

	for n := 0; n < total; n++ {
		// --- Send a 4-byte big-endian counter ---
		txBytes := make([]byte, 4)
		binary.BigEndian.PutUint32(txBytes, uint32(n))
		t0 := time.Now()
		if err := radio.Send(txBytes); err != nil { // Send packet, (data=bytes ≤255 B) → error
			panic(err)
		}

		// --- Immediately listen for the echo from the peer ---
		pkt, err := radio.Receive(1000, false) // Receive single packet, (timeout_ms=1000, use_interrupt=false) → ([]byte, error)
		if err != nil {
			panic(err)
		}
		rtt := time.Since(t0)

		if pkt != nil && string(pkt) == string(txBytes) {
			rssi, err := radio.LastPacketRSSI() // Last packet RSSI, () → (float64 dBm, error)
			if err != nil {
				panic(err)
			}
			snr, err := radio.LastPacketSNR() // Last packet SNR, () → (float64 dB, error)
			if err != nil {
				panic(err)
			}
			println("echo", n, "rtt_ms", int32(rtt.Milliseconds()), "rssi", int32(rssi), "snr", int32(snr))
		} else {
			loss++
			println("no echo at", n)
		}

		time.Sleep(200 * time.Millisecond)
	}

	// --- Report the link quality summary ---
	println("done —", total-loss, "/", total, "successful,", loss, "lost")
}
