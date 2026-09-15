//go:build tinygo

// LPS33HW complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the LPS33HWFull API on the Pico W.
package main

import (
	"fmt"
	"machine"

	"github.com/tuhde/Periph/go/periph/chips/pressure"
	"github.com/tuhde/Periph/go/periph/connection"
)

func main() {
	i2c := machine.I2C1
	if err := i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}); err != nil {
		panic(err)
	}

	conn := connection.NewI2CConnection(i2c, 0x5C, nil, nil) // Create I2C connection, (i2c, addr=0x5C) → (*I2CConnection)
	chip, err := pressure.NewLPS33HWFull(conn, 0x5C)         // Create LPS33HW driver, (connection, addr=0x5C) → (*LPS33HWFull, error)
	if err != nil {
		panic(err)
	}

	st, err := chip.Status() // Read STATUS register, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	intsrc, err := chip.InterruptStatus() // Read INT_SOURCE register, () → (uint8, error)
	if err != nil {
		panic(err)
	}

	chip.Configure( // Configure chip, (odr 0–5, bdu, enLpfp, lpfpCfg, lcEn, sim) → error
		pressure.LPS33HWODR10Hz, 1, 1, pressure.LPS33HWLPFPBWODR20, 0, 0,
	) // sets CTRL_REG1 (ODR/BDU/EN_LPFP/LPFP_CFG/SIM) and LC_EN in RES_CONF

	pOs, tOs, err := chip.OneShot() // Trigger one-shot, () → (float32 Pa, float32 °C, error)
	if err != nil {
		panic(err)
	}
	p, err := chip.Pressure() // Read pressure, () → (float32 Pa, error)
	if err != nil {
		panic(err)
	}
	t, err := chip.Temperature() // Read temperature, () → (float32 °C, error)
	if err != nil {
		panic(err)
	}

	chip.SetPressureOffset(0.5) // Set pressure offset, (offsetHPa=0.5 hPa) → error
	chip.SetAutozero()           // Set AUTOZERO, () → error
	chip.ClearAutozero()         // Clear AUTOZERO, () → error
	chip.SetAutorifp()           // Set AUTORIFP, () → error
	chip.ClearAutorifp()         // Clear AUTORIFP, () → error

	chip.ConfigureInterrupt( // Configure INT_DRDY routing, (drdy, fFth, fOvr, fFss5, intS, activeLow, openDrain) → error
		1, 0, 0, 0, pressure.LPS33HWIntSDataSignals, 0, 0,
	)
	chip.ConfigurePressureInterrupt(1, 1, 5.0, true) // Configure pressure threshold interrupt, (highEn, lowEn, thresholdHPa, latch) → error

	chip.EnableFifo(pressure.LPS33HWFIFOModeStream, 16) // Enable FIFO, (mode=Stream, watermark=16) → error
	fst, err := chip.FifoStatus()                       // Read FIFO_STATUS, () → (uint8, error)
	if err != nil {
		panic(err)
	}
	chip.DisableFifo() // Disable FIFO, () → error

	chip.ResetLpf() // Reset LPF, () → error
	chip.Reset()    // Software reset, () → error
	chip.Reboot()   // Reboot from Flash, () → error

	fmt.Printf("status=0x%02X  int_source=0x%02X  fifo_status=0x%02X\n", st, intsrc, fst)
	fmt.Printf("one_shot: %.1f Pa / %.2f C, then: %.1f Pa / %.2f C\n", pOs, tOs, p, t)
}