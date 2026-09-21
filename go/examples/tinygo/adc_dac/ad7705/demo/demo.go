//go:build tinygo

// AD7705 demo example — bridge-pressure measurement (datasheet Applications § Pressure Measurement).
package main

import (
	"fmt"
	"math"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
)

const (
	tempCoeff       = 0.05
	tempReference   = 1.25
	changeThreshold = 0.001
)

func main() {
	machine.SPI0.Configure(machine.SPIConfig{
		Frequency: 5_000_000,
		Mode:      3,
		SCK:       machine.GP18,
		SDO:       machine.GP19,
		SDI:       machine.GP16,
	})
	cs := machine.GP17
	conn := connection.NewSPIConnection(machine.SPI0, cs, nil, nil)                  // Create SPI connection, (spi=SPI0, cs=GP17) → (*SPIConnection)

	chip, err := adcdac.NewAD7705Full(conn, 2.5, adcdac.MCLK2_4576MHz, nil)             // Create AD7705 driver, (connection, vref=2.5 V, mclk_hz=2_457_600 Hz, reset_pin=nil) → (*AD7705Full, error)
	if err != nil {
		fmt.Println("init:", err)
		return
	}

	// --- Configure both channels for the bridge-pressure application ---
	if err := chip.Configure(1, adcdac.GAIN128, true, true, 50); err != nil {       // Configure channel 1, (channel=1, gain=GAIN128, bipolar=true, buffered=true, output_rate_hz=50) → error
		fmt.Println("cfg1:", err)
		return
	}
	if err := chip.Configure(2, adcdac.GAIN2, true, false, 50); err != nil {        // Configure channel 2, (channel=2, gain=GAIN2, bipolar=true, buffered=false, output_rate_hz=50) → error
		fmt.Println("cfg2:", err)
		return
	}

	// --- Self-calibrate both channels before the measurement loop ---
	if err := chip.SelfCalibrate(1); err != nil {                                   // Self-calibrate channel, (channel=1) → error
		fmt.Println("cal1:", err)
		return
	}
	if err := chip.SelfCalibrate(2); err != nil {                                   // Self-calibrate channel, (channel=2) → error
		fmt.Println("cal2:", err)
		return
	}

	var lastPressure float64 = math.NaN()
	for {
		pressureRaw, err := chip.ReadVoltageChannel(1)                              // Read voltage, (channel=1) → (float64, error)
		if err != nil {
			continue
		}
		temp, err := chip.ReadVoltageChannel(2)                                      // Read voltage, (channel=2) → (float64, error)
		if err != nil {
			continue
		}
		pressure := pressureRaw - tempCoeff*(temp-tempReference)
		needPrint := math.IsNaN(lastPressure) || math.Abs(pressure-lastPressure) > changeThreshold
		if needPrint {
			fmt.Printf("→ pressure=%.4f V (raw %.4f V, temp %.4f V)\n", pressure, pressureRaw, temp)
			lastPressure = pressure
		}
		time.Sleep(200 * time.Millisecond)
	}
}
