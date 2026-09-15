package pressure

import (
	"math"
	"testing"
)

// mockConnection, newMockConnection, lastWriteTo, setRegister are defined in
// bmp180_test.go (shared across this package's unit tests).

func TestLPS33HWFullAPI(t *testing.T) {
	conn := newMockConnection()
	// WHO_AM_I = 0xB1
	conn.setRegister(lps33hwRegWhoAmI, 0xB1)

	// Preload STATUS to indicate P_DA | T_DA so read_press_temp doesn't loop.
	// Burst returns raw_pressure = 4096 (= 100 Pa) and raw_temperature = 2500
	// (= 25 °C).
	conn.setRegister(lps33hwRegStatus, LPS33HWStatusPDA|LPS33HWStatusTDA)
	conn.setRegister(lps33hwRegPressXl,
		0x00, 0x10, 0x00, 0xC4, 0x09,
	)
	// RES_CONF default value
	conn.setRegister(lps33hwRegResConf, 0x00)

	chip, err := NewLPS33HWFull(conn, 0x5C)
	if err != nil {
		t.Fatalf("NewLPS33HWFull: %v", err)
	}

	// Pressure(): 4096 raw -> 100 Pa.
	if v, err := chip.Pressure(); err != nil || math.Abs(float64(v)-100.0) > 1e-3 {
		t.Errorf("Pressure() = %v, %v, want 100.0, nil", v, err)
	}

	// Temperature(): 2500 raw -> 25 °C.
	if v, err := chip.Temperature(); err != nil || math.Abs(float64(v)-25.0) > 1e-3 {
		t.Errorf("Temperature() = %v, %v, want 25.0, nil", v, err)
	}

	// Status(): raw status byte.
	conn.setRegister(lps33hwRegStatus, 0x03)
	if v, err := chip.Status(); err != nil || v != 0x03 {
		t.Errorf("Status() = %v, %v, want 0x03, nil", v, err)
	}

	// InterruptStatus(): raw byte.
	conn.setRegister(lps33hwRegIntSource, 0x04)
	if v, err := chip.InterruptStatus(); err != nil || v != 0x04 {
		t.Errorf("InterruptStatus() = %v, %v, want 0x04, nil", v, err)
	}

	// Configure(odr=2, bdu=true, enLpfp=true, lpfpCfg=1, lcEn=false, sim=false):
	// ctrl1 = (2<<4)|(1<<3)|(1<<2)|(1<<1)|0 = 0x2E
	if err := chip.Configure(LPS33HWODR10Hz, 1, 1, LPS33HWLPFPBWODR20, 0, 0); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if w := lastWriteTo(conn.writes, lps33hwRegCtrlReg1); w == nil || w[1] != 0x2E {
		t.Errorf("Configure: expected CTRL_REG1=0x2E, got %v", w)
	}

	// SetPressureOffset(offsetHPa=16.0): raw = 16*16 = 256 -> RPDS_L=0x00, RPDS_H=0x01.
	if err := chip.SetPressureOffset(16.0); err != nil {
		t.Fatalf("SetPressureOffset: %v", err)
	}
	if w := lastWriteTo(conn.writes, lps33hwRegRpdsL); w == nil || w[1] != 0x00 {
		t.Errorf("SetPressureOffset: expected RPDS_L=0x00, got %v", w)
	}
	if w := lastWriteTo(conn.writes, lps33hwRegRpdsH); w == nil || w[1] != 0x01 {
		t.Errorf("SetPressureOffset: expected RPDS_H=0x01, got %v", w)
	}

	// AUTOZERO / AUTORIFP cycle
	if err := chip.SetAutozero(); err != nil {
		t.Errorf("SetAutozero: %v", err)
	}
	if err := chip.ClearAutozero(); err != nil {
		t.Errorf("ClearAutozero: %v", err)
	}
	if err := chip.SetAutorifp(); err != nil {
		t.Errorf("SetAutorifp: %v", err)
	}
	if err := chip.ClearAutorifp(); err != nil {
		t.Errorf("ClearAutorifp: %v", err)
	}

	// ConfigureInterrupt(drdy=1, fFth=1, fOvr=1, fFss5=1, intS=3, activeLow=1, openDrain=1):
	// ctrl3 = 0xFF
	if err := chip.ConfigureInterrupt(1, 1, 1, 1, 3, 1, 1); err != nil {
		t.Fatalf("ConfigureInterrupt: %v", err)
	}
	if w := lastWriteTo(conn.writes, lps33hwRegCtrlReg3); w == nil || w[1] != 0xFF {
		t.Errorf("ConfigureInterrupt: expected CTRL_REG3=0xFF, got %v", w)
	}

	// EnableFifo(mode=1, watermark=16): fifo_ctrl = (1<<5)|16 = 0x30
	if err := chip.EnableFifo(1, 16); err != nil {
		t.Fatalf("EnableFifo: %v", err)
	}
	if w := lastWriteTo(conn.writes, lps33hwRegFifoCtrl); w == nil || w[1] != 0x30 {
		t.Errorf("EnableFifo: expected FIFO_CTRL=0x30, got %v", w)
	}

	// FifoStatus(): raw byte
	conn.setRegister(lps33hwRegFifoStatus, 0x80)
	if v, err := chip.FifoStatus(); err != nil || v != 0x80 {
		t.Errorf("FifoStatus() = %v, %v, want 0x80, nil", v, err)
	}

	// DisableFifo()
	if err := chip.DisableFifo(); err != nil {
		t.Fatalf("DisableFifo: %v", err)
	}

	// ResetLpf()
	conn.setRegister(lps33hwRegLpfpRes, 0x00)
	if err := chip.ResetLpf(); err != nil {
		t.Fatalf("ResetLpf: %v", err)
	}

	// Reset()
	if err := chip.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}

	// Reboot()
	if err := chip.Reboot(); err != nil {
		t.Fatalf("Reboot: %v", err)
	}
}

func TestLPS33HWWrongChipID(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(lps33hwRegWhoAmI, 0x00) // wrong
	if _, err := NewLPS33HWMinimal(conn, 0x5C); err == nil {
		t.Error("expected error for wrong chip ID, got nil")
	}
}