//go:build tinygo

// PN532 complete example — TinyGo / Raspberry Pi Pico W.
//
// Calls every method of the Full driver once.
package main

import (
	"fmt"
	"machine"
	"time"

	"github.com/tuhde/Periph/go/periph/chips/rfid"
	"github.com/tuhde/Periph/go/periph/connection"
)

func chk(err error) {
	if err != nil {
		panic(err)
	}
}

func main() {
	i2c := machine.I2C1
	chk(i2c.Configure(machine.I2CConfig{
		SDA:       machine.GP4,
		SCL:       machine.GP5,
		Frequency: 400_000,
	}))

	conn := connection.NewI2CConnection(i2c, 0x24, nil, nil) // Create I2C connection, (i2c, addr=0x24) → (*I2CConnection)

	chip, err := rfid.NewPN532Full(conn) // Create PN532 driver, (connection) → (*PN532Full, error)
	// Stores the connection only; no bus traffic yet. I0 low and I1 high select the I²C interface.
	chk(err)
	chk(chip.Init()) // Wake, check firmware, SAM normal mode, finite retries, () → error
	// GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.

	fw, err := chip.FirmwareVersion() // GetFirmwareVersion, () → (PN532FirmwareVersion, error)
	// IC 0x32, firmware 1.6 on current parts; the support bits list the protocols.
	chk(err)
	fmt.Printf("%+v\n", fw)
	gs, err := chip.GeneralStatus() // GetGeneralStatus, () → (PN532GeneralStatus, error)
	// Last error code, external field detected, active targets, SAM status.
	chk(err)
	fmt.Printf("%+v\n", gs)
	ok, err := chip.SelfTest(0x00) // Diagnose, (test=0x00) → (bool, error)
	// Communication line test: the chip echoes an 8-byte pattern.
	chk(err)
	fmt.Println("self test", ok)
	ok, err = chip.SelfTest(0x01) // Diagnose, (test=0x01) → (bool, error)
	// ROM checksum test.
	chk(err)
	fmt.Println("ROM test", ok)

	uid, err := chip.ReadUID(time.Second) // Detect a type A card, (timeout=1 s) → ([]byte | nil, error)
	// InListPassiveTarget at 106 kbps type A, then InRelease.
	chk(err)
	fmt.Printf("UID %X\n", uid)

	targets, err := chip.ListPassiveTargets(rfid.PN532Baud106A, 1, nil, time.Second) // InListPassiveTarget, (baud=PN532Baud106A, maxTargets=1, initiatorData=nil, timeout=1 s) → ([]PN532Target, error)
	// Keeps the target active; Tg is the logical number for the next commands.
	chk(err)
	if len(targets) > 0 {
		t := targets[0]
		fmt.Printf("%+v\n", t)
		chk(chip.Deselect(t.Tg)) // InDeselect, (target=0) → error
		// Puts the card to sleep but keeps its information in the PN532.
		chk(chip.Select(t.Tg)) // InSelect, (target=0) → error
		// Wakes and reselects the deselected card.
		if len(t.UID) == 7 {
			page, err := chip.NTAGReadPage(t.Tg, 4) // NTAG READ, (target, page) → ([16]byte, error)
			// Returns pages 4..7 (16 bytes).
			fmt.Printf("%X %v\n", page, err)
			err = chip.NTAGWritePage(t.Tg, 4, [4]byte{1, 2, 3, 4}) // NTAG WRITE, (target, page, data [4]byte) → error
			// Writes one 4-byte user page.
			fmt.Println(err)
		} else {
			key := [6]byte{0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF}
			err := chip.MifareAuthenticate(t.Tg, 4, key, t.UID, false) // MIFARE auth, (target, block, key [6]byte, uid, keyB=false) → error
			// Key A authentication of sector 1 with the transport key.
			fmt.Println(err)
			block, err := chip.MifareReadBlock(t.Tg, 4) // MIFARE READ, (target, block) → ([16]byte, error)
			// Reads block 4.
			if err == nil {
				err = chip.MifareWriteBlock(t.Tg, 4, block) // MIFARE WRITE, (target, block, data [16]byte) → error
				// Writes the same 16 bytes back.
			}
			fmt.Println(err)
		}
		data, err := chip.DataExchange(t.Tg, []byte{0x30, 0x00}) // InDataExchange, (target, data) → ([]byte, error)
		// Raw READ of block/page 0 with PN532 framing and CRC.
		fmt.Printf("%X %v\n", data, err)
		chk(chip.Release(t.Tg)) // InRelease, (target=0) → error
		// Ends the session with the card.
	}

	chk(chip.SetRFField(false, false)) // RFConfiguration item 1, (on, autoRFCA=false) → error
	// RF field off; a card in the field loses power.
	chk(chip.SetRFField(true, false)) // RFConfiguration item 1, (on, autoRFCA=false) → error
	// RF field back on.
	chk(chip.SetMaxRetries(2, 0xFF, 1)) // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → error
	// 0xFF would retry forever.
	txMode, err := chip.ReadRegister(0x6302) // ReadRegister, (address) → (uint8, error)
	// 0x6302 is CIU_TxMode in the PN532's SFR space.
	chk(err)
	chk(chip.WriteRegister(0x6302, txMode)) // WriteRegister, (address, value) → error
	// Writes the value back unchanged.
	raw, err := chip.SendCommand(0x02, nil, time.Second) // Raw command, (command, data, timeout=1 s) → ([]byte, error)
	// GetFirmwareVersion through the raw frame interface.
	chk(err)
	fmt.Printf("%X\n", raw)
	thru, err := chip.CommunicateThru([]byte{0x26}) // InCommunicateThru, (data) → ([]byte, error)
	// Raw RF exchange with the current target; fails without one.
	fmt.Printf("%X %v\n", thru, err)

	chk(chip.StartCommand(0x02, nil)) // Command without waiting, (command, data) → error
	// Writes the frame and reads the ACK only.
	ready, err := chip.WaitReady(time.Second) // Wait for a frame, (timeout) → (bool, error)
	// Polls the status byte every 1 ms.
	chk(err)
	poll, err := chip.PollInterrupt() // Frame ready?, () → (bool, error)
	// A single status-byte read.
	chk(err)
	fmt.Println("ready", ready, "poll", poll)
	resp, err := chip.ReadResponse(0x02, time.Second) // Read the pending response, (command, timeout=1 s) → ([]byte, error)
	// Header, NACK, full frame, checksums.
	chk(err)
	fmt.Printf("%X\n", resp)

	chk(chip.OnInterrupt(func() { fmt.Println("frame ready") })) // Subscribe to IRQ, (callback) → error
	// Falling edge on the connection's IRQ pin, or a 10 ms status-byte poll without one.
	_, err = chip.SendCommand(0x02, nil, time.Second) // Raw command, (command, data, timeout=1 s) → ([]byte, error)
	chk(err)
	time.Sleep(100 * time.Millisecond)
	chk(chip.OffInterrupt()) // Unsubscribe, () → error
	// Stops the edge handler or the polling goroutine.

	chk(chip.PowerDown(0x20)) // PowerDown, (wakeupSources=0x20) → error
	// Sleeps until the next I²C access; the driver retries the wake-up write.
}
