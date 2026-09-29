package pressure

import (
	"math"
	"testing"
)

func newLPS22DFConnection() *mockConnection {
	conn := newMockConnection()
	conn.setRegister(lps22dfRegWhoAmI, lps22dfChipID)
	return conn
}

func TestLPS22DFMinimalConstructionSequence(t *testing.T) {
	conn := newLPS22DFConnection()
	if _, err := NewLPS22DFMinimal(conn, false); err != nil {
		t.Fatalf("NewLPS22DFMinimal: %v", err)
	}
	// Expected order: WHO_AM_I read, SWRESET write, CTRL_REG1 write, CTRL_REG2 (BDU) write.
	writes := conn.writes
	if len(writes) != 4 {
		t.Fatalf("got %d writes, want 4: %v", len(writes), writes)
	}
	if writes[0][0] != lps22dfRegWhoAmI {
		t.Errorf("write[0] = %#x, want WHO_AM_I read %#x", writes[0][0], lps22dfRegWhoAmI)
	}
	if writes[1][0] != lps22dfRegCtrlReg2 || writes[1][1] != 0x04 {
		t.Errorf("write[1] = %v, want SWRESET write [CTRL_REG2, 0x04]", writes[1])
	}
	if writes[2][0] != lps22dfRegCtrlReg1 || writes[2][1] != (LPS22DFODR10Hz<<3)|LPS22DFAvg4 {
		t.Errorf("write[2] = %v, want CTRL_REG1 default", writes[2])
	}
	if writes[3][0] != lps22dfRegCtrlReg2 || writes[3][1] != 0x08 {
		t.Errorf("write[3] = %v, want BDU write [CTRL_REG2, 0x08]", writes[3])
	}
}

func TestLPS22DFMinimalConstructionBadChipID(t *testing.T) {
	conn := newMockConnection()
	conn.setRegister(lps22dfRegWhoAmI, 0x00)
	if _, err := NewLPS22DFMinimal(conn, false); err == nil {
		t.Error("NewLPS22DFMinimal with bad WHO_AM_I: expected error, got nil")
	}
}

func TestLPS22DFMinimalPressureAndTemperature(t *testing.T) {
	conn := newLPS22DFConnection()
	chip, err := NewLPS22DFMinimal(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFMinimal: %v", err)
	}

	conn.setRegister(lps22dfRegStatus, lps22dfStatusPDa|lps22dfStatusTDa)
	conn.setRegister(lps22dfRegPressOutXL, 0x00, 0x80, 0x0C) // raw=819200 -> 20000 Pa
	p, err := chip.Pressure()
	if err != nil || math.Abs(float64(p)-20000.0) > 1e-3 {
		t.Errorf("Pressure() = %v, %v, want ~20000.0, nil", p, err)
	}

	conn.setRegister(lps22dfRegTempOutL, 0x2E, 0x09) // raw=2350 -> 23.5 degC
	temp, err := chip.Temperature()
	if err != nil || math.Abs(float64(temp)-23.5) > 1e-3 {
		t.Errorf("Temperature() = %v, %v, want ~23.5, nil", temp, err)
	}

	// Negative values (two's complement sign extension).
	conn.setRegister(lps22dfRegPressOutXL, 0x00, 0xC0, 0xF9) // raw=-409600 -> -10000 Pa
	p2, err := chip.Pressure()
	if err != nil || math.Abs(float64(p2)-(-10000.0)) > 1e-3 {
		t.Errorf("Pressure() (negative) = %v, %v, want ~-10000.0, nil", p2, err)
	}

	conn.setRegister(lps22dfRegTempOutL, 0x0C, 0xFE) // raw=-500 -> -5.0 degC
	temp2, err := chip.Temperature()
	if err != nil || math.Abs(float64(temp2)-(-5.0)) > 1e-3 {
		t.Errorf("Temperature() (negative) = %v, %v, want ~-5.0, nil", temp2, err)
	}
}

// Regression: Temperature() must poll STATUS.T_DA before reading TEMP_OUT,
// exactly like Pressure() polls STATUS.P_DA -- it previously read TEMP_OUT_L/H
// unconditionally with no STATUS check at all.
func TestLPS22DFMinimalTemperaturePollsStatusFirst(t *testing.T) {
	conn := newLPS22DFConnection()
	chip, err := NewLPS22DFMinimal(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFMinimal: %v", err)
	}
	conn.setRegister(lps22dfRegStatus, lps22dfStatusTDa)
	conn.setRegister(lps22dfRegTempOutL, 0x2E, 0x09)

	if _, err := chip.Temperature(); err != nil {
		t.Fatalf("Temperature: %v", err)
	}

	writes := conn.writes
	n := len(writes)
	if n < 2 {
		t.Fatalf("expected at least 2 writes for Temperature(), got %d", n)
	}
	if writes[n-2][0] != lps22dfRegStatus {
		t.Errorf("write[n-2] = %v, want a STATUS read (%#x) immediately before TEMP_OUT_L", writes[n-2], lps22dfRegStatus)
	}
	if writes[n-1][0] != lps22dfRegTempOutL {
		t.Errorf("write[n-1] = %v, want TEMP_OUT_L read (%#x)", writes[n-1], lps22dfRegTempOutL)
	}
}

func TestLPS22DFFullConfigure(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	if err := full.Configure(LPS22DFODR50Hz, LPS22DFAvg16, true, 1, true); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	writes := conn.writes
	n := len(writes)
	wantCtrl1 := byte((LPS22DFODR50Hz & 0x0F << 3) | (LPS22DFAvg16 & 0x07))
	wantCtrl2 := byte(0x10 | 0x20 | 0x08) // EN_LPFP | LFPF_CFG | BDU
	if writes[n-2][0] != lps22dfRegCtrlReg1 || writes[n-2][1] != wantCtrl1 {
		t.Errorf("CTRL_REG1 write = %v, want [%#x, %#x]", writes[n-2], lps22dfRegCtrlReg1, wantCtrl1)
	}
	if writes[n-1][0] != lps22dfRegCtrlReg2 || writes[n-1][1] != wantCtrl2 {
		t.Errorf("CTRL_REG2 write = %v, want [%#x, %#x]", writes[n-1], lps22dfRegCtrlReg2, wantCtrl2)
	}
}

func TestLPS22DFFullOneShot(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	conn.setRegister(lps22dfRegStatus, lps22dfStatusPDa)
	if err := full.OneShot(); err != nil {
		t.Fatalf("OneShot: %v", err)
	}
	writes := conn.writes
	n := len(writes)
	if writes[n-3][0] != lps22dfRegCtrlReg1 || writes[n-3][1] != 0x00 {
		t.Errorf("write[n-3] = %v, want CTRL_REG1=0x00 (power-down)", writes[n-3])
	}
	if writes[n-2][0] != lps22dfRegCtrlReg2 || writes[n-2][1] != 0x09 {
		t.Errorf("write[n-2] = %v, want CTRL_REG2=0x09 (BDU|ONESHOT)", writes[n-2])
	}
	if writes[n-1][0] != lps22dfRegStatus {
		t.Errorf("write[n-1] = %v, want a STATUS poll read", writes[n-1])
	}
}

func TestLPS22DFFullAltitude(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	conn.setRegister(lps22dfRegStatus, lps22dfStatusPDa)
	conn.setRegister(lps22dfRegPressOutXL, 0x00, 0x00, 0x00) // raw=0 -> 0 Pa
	alt, err := full.Altitude(101325.0)
	if err != nil {
		t.Fatalf("Altitude: %v", err)
	}
	if math.Abs(float64(alt)-44330.0) > 1.0 {
		t.Errorf("Altitude() at 0 Pa = %v, want ~44330.0 (theoretical max)", alt)
	}
}

func TestLPS22DFFullSoftwareReset(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	if err := full.SoftwareReset(); err != nil {
		t.Fatalf("SoftwareReset: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegCtrlReg2); v != 0x04 {
		t.Errorf("SoftwareReset() CTRL_REG2 write = %#x, want 0x04", v)
	}
}

func TestLPS22DFFullSetPressureOffset(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	// -12.34 hPa -> raw = round(-12.34*4096) = -50545 -> wraps to 14991 (0x3A8F).
	if err := full.SetPressureOffset(-1234.0); err != nil { // Pa, so -12.34 hPa
		t.Fatalf("SetPressureOffset: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegRpdsL); v != 0x8F {
		t.Errorf("RPDS_L write = %#x, want 0x8F", v)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegRpdsH); v != 0x3A {
		t.Errorf("RPDS_H write = %#x, want 0x3A", v)
	}
}

func TestLPS22DFFullSetPressureThreshold(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	// 900.0 hPa -> raw = round(900*16) & 0x7FFF = 14400 = 0x3840.
	if err := full.SetPressureThreshold(90000.0); err != nil { // Pa, so 900.0 hPa
		t.Fatalf("SetPressureThreshold: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegThsPL); v != 0x40 {
		t.Errorf("THS_P_L write = %#x, want 0x40", v)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegThsPH); v != 0x38 {
		t.Errorf("THS_P_H write = %#x, want 0x38", v)
	}
}

func TestLPS22DFFullConfigureInterrupt(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	if err := full.ConfigureInterrupt(true, true, true, true, true, true, true, true); err != nil {
		t.Fatalf("ConfigureInterrupt: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegCtrlReg3); v != (0x08 | 0x02 | 0x01) {
		t.Errorf("CTRL_REG3 write = %#x, want %#x", v, 0x08|0x02|0x01)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegCtrlReg4); v != (0x40 | 0x20 | 0x10 | 0x04 | 0x02 | 0x01) {
		t.Errorf("CTRL_REG4 write = %#x, want %#x", v, 0x40|0x20|0x10|0x04|0x02|0x01)
	}
}

func TestLPS22DFFullConfigurePressureEvent(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	if err := full.ConfigurePressureEvent(true, true, true); err != nil {
		t.Fatalf("ConfigurePressureEvent: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegInterruptCfg); v != (0x01 | 0x02 | 0x04) {
		t.Errorf("INTERRUPT_CFG write = %#x, want %#x", v, 0x01|0x02|0x04)
	}
}

func TestLPS22DFFullAutozeroAutorefpResetReference(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	if err := full.Autozero(); err != nil {
		t.Fatalf("Autozero: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegInterruptCfg); v != 0x20 {
		t.Errorf("Autozero() INTERRUPT_CFG write = %#x, want 0x20", v)
	}

	if err := full.Autorefp(); err != nil {
		t.Fatalf("Autorefp: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegInterruptCfg); v != 0x80 {
		t.Errorf("Autorefp() INTERRUPT_CFG write = %#x, want 0x80", v)
	}

	if err := full.ResetReference(); err != nil {
		t.Fatalf("ResetReference: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegInterruptCfg); v != 0x50 {
		t.Errorf("ResetReference() INTERRUPT_CFG write = %#x, want 0x50", v)
	}
}

func TestLPS22DFFullReferencePressure(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	conn.setRegister(lps22dfRegRefPL, 0x00, 0x10) // raw=4096 -> 100.0 Pa
	ref, err := full.ReferencePressure()
	if err != nil || math.Abs(float64(ref)-100.0) > 1e-3 {
		t.Errorf("ReferencePressure() = %v, %v, want ~100.0, nil", ref, err)
	}
}

func TestLPS22DFFullFifoModeAndWatermark(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	if err := full.SetFifoMode(LPS22DFFifoContToFifo); err != nil {
		t.Fatalf("SetFifoMode: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegFifoCtrl); v != (1<<2)|3 {
		t.Errorf("FIFO_CTRL write = %#x, want %#x", v, (1<<2)|3)
	}

	if err := full.SetFifoWatermark(100); err != nil {
		t.Fatalf("SetFifoWatermark: %v", err)
	}
	if v, _ := lastWriteToReg(conn, lps22dfRegFifoWtm); v != 100 {
		t.Errorf("FIFO_WTM write = %#x, want 100", v)
	}
}

func TestLPS22DFFullReadFifo(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	conn.setRegister(lps22dfRegFifoStatus1, 2)
	conn.setRegister(lps22dfRegFifoPressXL,
		0x00, 0x80, 0x0C, // sample 0: raw=819200 -> 20000 Pa
		0x00, 0xC0, 0xF9, // sample 1: raw=-409600 -> -10000 Pa
	)
	out := make([]float32, 4)
	n, err := full.ReadFifo(out)
	if err != nil {
		t.Fatalf("ReadFifo: %v", err)
	}
	if n != 2 {
		t.Fatalf("ReadFifo() returned %d samples, want 2", n)
	}
	if math.Abs(float64(out[0])-20000.0) > 1e-3 {
		t.Errorf("ReadFifo() sample 0 = %v, want ~20000.0", out[0])
	}
	if math.Abs(float64(out[1])-(-10000.0)) > 1e-3 {
		t.Errorf("ReadFifo() sample 1 = %v, want ~-10000.0", out[1])
	}
}

func TestLPS22DFFullReadFifoTruncatesToOutBufferLength(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	conn.setRegister(lps22dfRegFifoStatus1, 5)
	conn.setRegister(lps22dfRegFifoPressXL,
		0x00, 0x80, 0x0C,
		0x00, 0x80, 0x0C,
		0x00, 0x80, 0x0C,
		0x00, 0x80, 0x0C,
		0x00, 0x80, 0x0C,
	)
	out := make([]float32, 2)
	n, err := full.ReadFifo(out)
	if err != nil {
		t.Fatalf("ReadFifo: %v", err)
	}
	if n != 2 {
		t.Errorf("ReadFifo() with a 2-element buffer returned %d, want 2 (truncated)", n)
	}
}

func TestLPS22DFFullInterruptSource(t *testing.T) {
	conn := newLPS22DFConnection()
	full, err := NewLPS22DFFull(conn, false)
	if err != nil {
		t.Fatalf("NewLPS22DFFull: %v", err)
	}
	conn.setRegister(lps22dfRegIntSource, 0x85) // BOOT_ON | IA | PH
	src, err := full.InterruptSource()
	if err != nil {
		t.Fatalf("InterruptSource: %v", err)
	}
	if !src.BootOn || !src.IA || !src.PH || src.PL {
		t.Errorf("InterruptSource() = %+v, want {BootOn:true IA:true PH:true PL:false}", src)
	}
}

func TestLPS22DFSpiAddressing(t *testing.T) {
	conn := newMockConnection()
	// The mock's register map is keyed by the literal address byte sent, so
	// for SPI (read addresses have bit 7 set) the fixture must be preloaded
	// at the shifted address.
	conn.setRegister(lps22dfRegWhoAmI|0x80, lps22dfChipID)
	chip, err := NewLPS22DFMinimal(conn, true)
	if err != nil {
		t.Fatalf("NewLPS22DFMinimal (spi): %v", err)
	}
	// SPI reads (WHO_AM_I during construction) must set bit 7; writes (SWRESET,
	// CTRL_REG1, CTRL_REG2) must clear it.
	writes := conn.writes
	if writes[0][0] != lps22dfRegWhoAmI|0x80 {
		t.Errorf("SPI WHO_AM_I read address = %#x, want %#x", writes[0][0], lps22dfRegWhoAmI|0x80)
	}
	if writes[1][0] != lps22dfRegCtrlReg2&0x7F {
		t.Errorf("SPI SWRESET write address = %#x, want %#x", writes[1][0], lps22dfRegCtrlReg2&0x7F)
	}
	_ = chip
}
