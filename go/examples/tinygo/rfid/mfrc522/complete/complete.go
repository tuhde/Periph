//go:build tinygo

// MFRC522 complete example — TinyGo / Raspberry Pi Pico W.
//
// Exercises every method in the MFRC522Full API: card detection,
// version read, antenna gain inspection, self test, WUPA,
// anticollision-only Select, HLTA, MIFARE Classic authenticated
// read/write/value operations, MIFARE Ultralight page read/write,
// and RandomID.
package main

import (
	"encoding/hex"
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/transport"
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

	tr := transport.NewI2CTransport(i2c, 0x28)        // Create I2C transport, (i2c, addr=0x28) → *I2CTransport
	chip, err := rfid.NewMFRC522Full(tr)              // Create MFRC522 driver, (transport) → (*MFRC522Full, error)
	if err != nil {
		panic(err)
	}

	chipType, ver, err := chip.Version() // Read version register, () → (chipType, version, error)
	if err != nil {
		fmt.Println("version err:", err)
	} else {
		fmt.Printf("version: chip=0x%X ver=%d\n", chipType, ver)
	}

	gain, err := chip.AntennaGain() // Read antenna gain, () → (uint8, error)
	if err != nil {
		fmt.Println("antenna_gain err:", err)
	} else {
		fmt.Printf("antenna_gain: 0x%02X\n", gain)
	}

	if err := chip.SetAntennaGain(rfid.RX_GAIN_48_DB); err != nil { // Set antenna gain, (gain=RX_GAIN_48_DB) → error
		fmt.Println("set_antenna_gain err:", err)
	}

	present, err := chip.WakeupCard() // Send WUPA, () → (bool, error)
	if err != nil {
		fmt.Println("wakeup_card err:", err)
	} else {
		fmt.Printf("wakeup_card: %v\n", present)
	}

	uid, err := chip.SelectCard() // Anticollision + select only, () → ([]byte, error)
	if err != nil {
		fmt.Println("select_card err:", err)
	} else if uid == nil {
		fmt.Println("select_card: no card")
	} else {
		fmt.Printf("select_card uid: %s\n", hex.EncodeToString(uid))
	}

	if err := chip.HaltCard(); err != nil { // Send HLTA, () → error
		fmt.Println("halt_card err:", err)
	}

	if uid != nil && len(uid) == 4 {
		defaultKey := []byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}
		ok, err := chip.Authenticate(4, rfid.KEY_A, defaultKey, uid) // Run MFAuthent, (block, key_type, key, uid) → (bool, error)
		if err != nil {
			fmt.Println("authenticate err:", err)
		} else {
			fmt.Printf("authenticate: %v\n", ok)
			if ok {
				block, err := chip.ReadBlock(4) // Read MIFARE block, (block_address) → ([]byte, error)
				if err != nil {
					fmt.Println("read_block err:", err)
				} else {
					fmt.Printf("read_block: %s\n", hex.EncodeToString(block))
				}
				if err := chip.StopCrypto(); err != nil { // Clear MFCrypto1On, () → error
					fmt.Println("stop_crypto err:", err)
				}
			}
		}
	}

	pages, err := chip.ReadUltralightPage(0) // Read 4 pages, (page_address) → ([]byte, error)
	if err != nil {
		fmt.Println("read_ultralight_page err:", err)
	} else {
		fmt.Printf("read_ultralight_page(0): %s\n", hex.EncodeToString(pages))
	}

	randID, err := chip.GenerateRandomID() // Run Generate RandomID, () → ([]byte, error)
	if err != nil {
		fmt.Println("generate_random_id err:", err)
	} else {
		fmt.Printf("generate_random_id: %s\n", hex.EncodeToString(randID))
	}

	if err := chip.AntennaOff(); err != nil { // Disable antenna, () → error
		fmt.Println("antenna_off err:", err)
	}
	if err := chip.AntennaOn(); err != nil { // Enable antenna, () → error
		fmt.Println("antenna_on err:", err)
	}

	ok, err := chip.SelfTest() // Run self test, () → (bool, error)
	if err != nil {
		fmt.Println("self_test err:", err)
	} else {
		fmt.Printf("self_test: %v\n", ok)
	}

	if err := chip.Reset(); err != nil { // Re-run SoftReset + init, () → error
		fmt.Println("reset err:", err)
	}

	time.Sleep(2 * time.Second)
}
