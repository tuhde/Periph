//go:build linux && !tinygo

// AD7705 complete example — Linux host.
package main

import (
	"fmt"
	"os"
	"strconv"

	"github.com/tuhde/Periph/go/periph/chips/adcdac"
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

	conn, err := connection.NewSPIConnection(bus, device, 3, 5_000_000, nil, nil)   // Create SPI connection, (bus=0, device=0, mode=3, max_speed=5 MHz) → (*SPIConnection, error)
	if err != nil {
		panic(err)
	}
	defer conn.Close()

	chip, err := adcdac.NewAD7705Full(conn, 2.5, adcdac.MCLK2_4576MHz)             // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz) → (*AD7705Full, error)
	if err != nil {
		panic(err)
	}

	if err := chip.Configure(2, adcdac.GAIN8, true, true, 60); err != nil {        // Configure channel 2, (channel=2, gain=GAIN8, bipolar=true, buffered=true, output_rate_hz=60) → error
		panic(err)
	}                                                                              // sets gain/bipolar/buffered/output_rate; does not calibrate
	if err := chip.SelfCalibrate(2); err != nil {                                  // Self-calibrate channel, (channel=2) → error
		panic(err)
	}                                                                              // runs internal self-calibration, blocking until DRDY

	off2, err := chip.GetOffsetCalibration(2)                                      // Read offset calibration, (channel=2) → (uint32, error) 24-bit
	if err != nil {
		panic(err)
	}
	gain2, err := chip.GetGainCalibration(2)                                       // Read gain calibration, (channel=2) → (uint32, error) 24-bit
	if err != nil {
		panic(err)
	}
	fmt.Printf("ch2 offset=%d gain=%d\n", off2, gain2)

	raw1, err := chip.ReadRawChannel(1)                                            // Read raw 16-bit code, (channel=1) → (uint16, error)
	if err != nil {
		panic(err)
	}
	v1, err := chip.ReadVoltageChannel(1)                                          // Read voltage, (channel=1) → (float64, error)
	if err != nil {
		panic(err)
	}
	v2, err := chip.ReadVoltageChannel(2)                                          // Read voltage, (channel=2) → (float64, error)
	if err != nil {
		panic(err)
	}
	fmt.Printf("ch1 raw=%d ch1 v=%.4f ch2 v=%.4f\n", raw1, v1, v2)

	if err := chip.Standby(); err != nil {                                          // Enter standby, () → error
		panic(err)
	}                                                                              // sets STBY=1 (~10 µA, registers retained)
	if err := chip.Wakeup(); err != nil {                                           // Exit standby, () → error
		panic(err)
	}                                                                              // clears STBY; blocks until a fresh conversion is available
}

func envOr(k, def string) string {
	if v, ok := os.LookupEnv(k); ok {
		return v
	}
	return def
}
