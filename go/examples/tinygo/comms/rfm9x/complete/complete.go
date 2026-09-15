//go:build tinygo

// RFM95W complete example — TinyGo / Raspberry Pi Pico W.
//
// Configures machine.SPI0 (GP18=SCK, GP19=SDO, GP16=SDI, GP17=CS) plus
// NRESET on GP14 and DIO0 on GP15, then exercises every method in the
// RFM95Full API: identity, hardware reset, modem configuration, TX
// power, carrier frequency, send, single-packet receive (polling and
// interrupt), continuous receive, link-quality readouts, and power
// management.
package main

import (
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
	resetPin := connection.NewGpioOutputPin(machine.GP14)            // Configure NRESET line, (pin=GP14) → (*GpioOutputPin)
	dio0Pin := connection.NewGpioInputPin(machine.GP15)              // Configure DIO0 line, (pin=GP15) → (*GpioInputPin)

	radio, err := comms.NewRFM95Full(conn, 868_000_000, resetPin, dio0Pin) // Create RFM95W driver, (connection, frequency_hz=868e6, reset_pin, dio0_pin) → (*RFM95Full, error)
	if err != nil {
		panic(err)
	}

	ver, err := radio.Version() // Read silicon revision, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	println("version:", ver)
	// expect 0x12 (SX1276)

	if err := radio.Configure(7, 125.0, 5, true); err != nil { // Configure LoRa modem, (sf=6–12, bandwidth_khz=7.8–500, coding_rate=5–8, crc=true) → error
		panic(err)
	}
	// sets BW=125 kHz, SF7, CR 4/5, CRC on

	if err := radio.SetTxPower(17, true); err != nil { // Set TX power, (power_dbm=2–20, use_pa_boost=true) → error
		panic(err)
	}
	// PA_BOOST pin, +17 dBm

	if err := radio.SetFrequency(868_000_000); err != nil { // Change carrier frequency, (frequency_hz=862e6–1020e6) → error
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
		println("packet len:", len(pkt))
		_ = rssi
		_ = snr
	}

	pkt, err = radio.Receive(2000, true) // Receive single packet (interrupt), (timeout_ms=2000, use_interrupt=true) → ([]byte, error)
	if err != nil {
		panic(err)
	}
	// waits on a DIO0 rising edge instead of polling RegIrqFlags
	println("interrupt receive len:", len(pkt))

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
			println("got len:", len(pkt), "rssi:", int(rssi))
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
