//go:build tinygo

// MCP3422 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method of the public API: one-shot reads, resolution and gain,
// continuous conversion, status reads, scans and the general calls.
package main

import (
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	machine.I2C1.Configure(machine.I2CConfig{Frequency: 400 * machine.KHz, SDA: machine.GP4, SCL: machine.GP5})
	conn := connection.NewI2CConnection(machine.I2C1, adcdac.MCP3422I2CAddress, nil, nil) // Create I2C connection, (i2c, addr=0x68) → *I2CConnection
	defer conn.Close()

	adc, err := adcdac.NewMCP3422Full(conn) // Create MCP3422 Full driver, (conn) → (*MCP3422Full, error)
	must(err)
	// one-shot, 16 bit, x1, RDY = 0

	raw, err := adc.ReadRaw(0) // One-shot code, (channel 0–1) → (int32, error)
	must(err)
	// writes RDY = 1, O/C = 0, waits, polls RDY
	v, err := adc.ReadVoltage(1) // One-shot voltage, (channel 0–1) → (float32 V, error)
	must(err)
	both, err := adc.ReadAll() // Both channels, () → ([2]float32 V, error)
	must(err)
	scan, err := adc.ReadScan([]int{0, 1, 0}) // Scan a list of channels, (channels) → ([]float32 V, error)
	must(err)
	println(raw, v, both[0], both[1], len(scan))

	must(adc.SetResolution(18)) // Select 18 bit / 3.75 SPS, (bits 12|14|16|18) → error
	// ErrMCP3422Resolution for other values
	must(adc.SetGain(8))                        // Select PGA x8, (gain 1|2|4|8) → error
	println(adc.GetResolution(), adc.GetGain()) // Read back the selection, () → (int, int)
	println(adc.Lsb(), adc.FullScale())         // LSB and full scale, () → (float32 V, float32 V)
	// 2·2.048/2^bits and 2.048/gain

	must(adc.SetResolution(12))   // Back to 12 bit / 240 SPS, (bits) → error
	must(adc.SetGain(1))          // Back to x1, (gain) → error
	must(adc.SetChannel(0))       // Channel for continuous conversion, (channel 0–1) → error
	must(adc.StartContinuous(-1)) // Start continuous conversion, (channel, negative = keep) → error
	// O/C = 1; the first sample after a change is discarded
	println(adc.IsContinuous())          // Report the mode, () → bool
	cont, err := adc.ReadContinuousRaw() // Next fresh code, () → (int32, error)
	must(err)
	contV, err := adc.ReadContinuous() // Next fresh voltage, () → (float32 V, error)
	must(err)
	cfg, err := adc.ReadConfig() // Read the config byte, () → (uint8, error)
	must(err)
	ready, err := adc.IsReady() // Report RDY = 0, () → (bool, error)
	must(err)
	println(cont, contV, cfg, ready)
	must(adc.StopContinuous()) // Back to one-shot / standby, () → error

	must(adc.GeneralCallConvert()) // Start a conversion on every device, () → error
	// sends 0x00 0x08
	time.Sleep(100 * time.Millisecond)
	pending, err := adc.ReadPendingRaw() // Result of that conversion, () → (int32, error)
	must(err)
	println(pending)
	must(adc.GeneralCallReset()) // Reset to power-on defaults, () → error
	// sends 0x00 0x06; the driver state follows
}
