//go:build linux && !tinygo

// RFM95W complete example — Linux host.
//
// Exercises every method in the RFM95Full API: identity, hardware reset,
// modem configuration, TX power, carrier frequency, send, single-packet
// receive (polling and interrupt), continuous receive, link-quality
// readouts, and power management.
package main

import (
	"fmt"
	"os"
	"strconv"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/comms"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	bus, err := strconv.Atoi(envOr("SPI_BUS", "0"))
	if err != nil {
		panic(err)
	}
	device, err := strconv.Atoi(envOr("SPI_DEVICE", "0"))
	if err != nil {
		panic(err)
	}
	freq, err := strconv.ParseUint(envOr("RFM9X_FREQUENCY_HZ", "868000000"), 10, 32)
	if err != nil {
		panic(err)
	}
	resetLine, err := strconv.Atoi(envOr("RFM9X_RESET_LINE", "14"))
	if err != nil {
		panic(err)
	}
	dio0Line, err := strconv.ParseUint(envOr("RFM9X_DIO0_LINE", "15"), 10, 32)
	if err != nil {
		panic(err)
	}

	conn, err := connection.NewSPIConnection(bus, device, 5_000_000, nil, nil) // Create SPI connection, (bus=0, device=0, max_speed=5 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	resetPin, err := connection.NewGpioOutputPin(resetLine) // Reserve NRESET line, (line=14) → (*GpioOutputPin, error)
	if err != nil {
		panic(err)
	}
	dio0Pin, err := connection.NewGpioInputPin(uint32(dio0Line)) // Reserve DIO0 line, (line=15) → (*GpioInputPin, error)
	if err != nil {
		panic(err)
	}

	radio, err := comms.NewRFM95Full(conn, uint32(freq), resetPin, dio0Pin) // Create RFM95W driver, (connection, frequency_hz=868e6, reset_pin, dio0_pin) → (*RFM95Full, error)
	if err != nil {
		panic(err)
	}

	ver, err := radio.Version() // Read silicon revision, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("version: 0x%02X\n", ver)
	// expect 0x12 (SX1276)

	if err := radio.Configure(7, 125.0, 5, true); err != nil { // Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500, coding_rate=5–8, crc=true) → error
		panic(err)
	}
	// sets BW=125 kHz, SF7, CR 4/5, CRC on

	if err := radio.SetTxPower(17, true); err != nil { // Set TX power, (power_dbm=2–20, use_pa_boost=true) → error
		panic(err)
	}
	// PA_BOOST pin, +17 dBm

	if err := radio.SetFrequency(uint32(freq)); err != nil { // Change carrier frequency, (frequency_hz=862e6–1020e6) → error
		panic(err)
	}

	if err := radio.Standby(); err != nil { // Enter STDBY mode, () → error
		panic(err)
	}

	if err := radio.Send([]byte("hello world")); err != nil { // Send packet, (data=bytes ≤255 B) → error
		panic(err)
	}
	// STDBY → fill FIFO → TX → poll TxDone → STDBY

	pkt, err := radio.Receive(2000, false) // Receive single packet, (timeout_ms=2000, use_interrupt=false) → ([]byte, error)
	if err != nil {
		panic(err)
	}
	// RXSINGLE → poll RxDone/RxTimeout → read FIFO
	if pkt != nil {
		rssi, err := radio.LastPacketRSSI() // Last packet RSSI, () → (float64 dBm, error)
		if err != nil {
			panic(err)
		}
		// converted from RegPktRssiValue using -137 offset
		snr, err := radio.LastPacketSNR() // Last packet SNR, () → (float64 dB, error)
		if err != nil {
			panic(err)
		}
		// RegPktSnrValue is signed 8-bit × 0.25 dB
		fmt.Printf("packet: %v rssi=%.1f snr=%.1f\n", pkt, rssi, snr)
	}

	pkt, err = radio.Receive(2000, true) // Receive single packet (interrupt), (timeout_ms=2000, use_interrupt=true) → ([]byte, error)
	if err != nil {
		panic(err)
	}
	// waits on a DIO0 rising edge instead of polling RegIrqFlags
	fmt.Printf("interrupt receive: %v\n", pkt)

	if err := radio.ReceiveContinuous(); err != nil { // Enter continuous RX, () → error
		panic(err)
	}
	for i := 0; i < 20; i++ {
		pkt, err := radio.ReadPacket() // Read buffered packet, () → ([]byte, error)
		if err != nil {
			panic(err)
		}
		// drains FIFO when RxDone fires
		if pkt != nil {
			rssi, err := radio.RSSI() // Current channel RSSI, () → (float64 dBm, error)
			if err != nil {
				panic(err)
			}
			// readable while in continuous RX
			fmt.Printf("got: %v rssi=%.1f\n", pkt, rssi)
		}
		time.Sleep(50 * time.Millisecond)
	}
	if err := radio.StopReceive(); err != nil { // Return to STDBY from RX_CONT, () → error
		panic(err)
	}

	if err := radio.Sleep(); err != nil { // Enter SLEEP mode, () → error
		panic(err)
	}
	// lowest power; FIFO inaccessible
	time.Sleep(250 * time.Millisecond)
	if err := radio.Standby(); err != nil {
		panic(err)
	}

	if err := radio.Reset(); err != nil { // Hardware reset, () → error
		panic(err)
	}
	// pulses NRESET and re-runs the LoRa init sequence
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
