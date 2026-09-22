//go:build tinygo

// AD7705 complete example — TinyGo target.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/adc_dac"
	"github.com/tuhde/Periph/go/periph/connection"
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

	if err := chip.Configure(2, adcdac.GAIN8, true, true, 60); err != nil {        // Configure channel 2, (channel=2, gain=GAIN8, bipolar=true, buffered=true, output_rate_hz=60) → error
		fmt.Println("cfg2:", err)
		return
	}
	if err := chip.SelfCalibrate(2); err != nil {                                  // Self-calibrate channel, (channel=2) → error
		fmt.Println("cal2:", err)
		return
	}

	off2, err := chip.GetOffsetCalibration(2)                                      // Read offset calibration, (channel=2) → (uint32, error) 24-bit
	if err != nil {
		fmt.Println("off2:", err)
		return
	}
	gain2, err := chip.GetGainCalibration(2)                                       // Read gain calibration, (channel=2) → (uint32, error) 24-bit
	if err != nil {
		fmt.Println("gain2:", err)
		return
	}
	fmt.Printf("ch2 offset=%d gain=%d\n", off2, gain2)

	raw1, err := chip.ReadRawChannel(1)                                            // Read raw 16-bit code, (channel=1) → (uint16, error)
	if err != nil {
		fmt.Println("raw1:", err)
		return
	}
	v1, err := chip.ReadVoltageChannel(1)                                          // Read voltage, (channel=1) → (float64, error)
	if err != nil {
		fmt.Println("v1:", err)
		return
	}
	v2, err := chip.ReadVoltageChannel(2)                                          // Read voltage, (channel=2) → (float64, error)
	if err != nil {
		fmt.Println("v2:", err)
		return
	}
	fmt.Printf("ch1 raw=%d ch1 v=%.4f ch2 v=%.4f\n", raw1, v1, v2)

	if err := chip.Standby(); err != nil {                                          // Enter standby, () → error
		fmt.Println("standby:", err)
		return
	}
	if err := chip.Wakeup(); err != nil {                                           // Exit standby, () → error
		fmt.Println("wakeup:", err)
		return
	}
}
