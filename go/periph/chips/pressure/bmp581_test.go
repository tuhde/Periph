package pressure

import (
	"math"
	"testing"
)

func preloadBmp581(conn *mockConnection) {
	conn.setRegister(bmp581RegChipID, 0x50)
	conn.setRegister(bmp581RegStatus, bmp581StatusNVMRdy)
	conn.setRegister(bmp581RegTempXLSB, 0x00, 0x10, 0x00)  // raw_t = 0x1000 -> 0.0625 °C
	conn.setRegister(bmp581RegPressXLSB, 0x04, 0x00, 0x00) // raw_p = 0x04 -> 0.0625 Pa
}

func TestBMP581FullAPI(t *testing.T) {
	conn := newMockConnection()
	preloadBmp581(conn)

	sensor, err := NewBMP581Full(conn, false)
	if err != nil {
		t.Fatalf("NewBMP581Full: %v", err)
	}

	if w := lastWriteTo(conn.writes, bmp581RegODRConfig); w == nil || w[1] != 0x71 {
		t.Errorf("init: expected ODR_CONFIG=0x71, got %v", w)
	}
	if w := lastWriteTo(conn.writes, bmp581RegOSRConfig); w == nil || w[1] != 0x40 {
		t.Errorf("init: expected OSR_CONFIG=0x40, got %v", w)
	}

	v, err := sensor.Temperature()
	if err != nil || math.Abs(float64(v)-0.0625) > 1e-6 {
		t.Errorf("Temperature() = %v, %v, want 0.0625", v, err)
	}
	v, err = sensor.Pressure()
	if err != nil || math.Abs(float64(v)-0.0625) > 1e-6 {
		t.Errorf("Pressure() = %v, %v, want 0.0625", v, err)
	}
	p, t2, err := sensor.Both()
	if err != nil || math.Abs(float64(p)-0.0625) > 1e-6 || math.Abs(float64(t2)-0.0625) > 1e-6 {
		t.Errorf("Both() = %v, %v, %v, want 0.0625, 0.0625", p, t2, err)
	}

	conn.setRegister(bmp581RegChipID, 0x50)
	if cid, err := sensor.ChipID(); err != nil || cid != 0x50 {
		t.Errorf("ChipID() = %d, %v, want 0x50", cid, err)
	}

	conn.setRegister(bmp581RegRevID, 0x32)
	if rid, err := sensor.RevID(); err != nil || rid != 0x32 {
		t.Errorf("RevID() = %d, %v, want 0x32", rid, err)
	}

	conn.setRegister(bmp581RegStatus, 0x09)
	if st, err := sensor.Status(); err != nil || st != 0x09 {
		t.Errorf("Status() = %d, %v, want 0x09", st, err)
	}

	conn.setRegister(bmp581RegIntStatus, 0x11)
	if ist, err := sensor.InterruptStatus(); err != nil || ist != 0x11 {
		t.Errorf("InterruptStatus() = %d, %v, want 0x11", ist, err)
	}

	conn.setRegister(bmp581RegIntStatus, 0x01)
	if drdy, err := sensor.DataReady(); err != nil || !drdy {
		t.Errorf("DataReady() = %v, %v, want true", drdy, err)
	}

	if err := sensor.Configure(0x17, BMP581OSR16X, BMP581OSR4X, true); err != nil {
		t.Errorf("Configure: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegODRConfig); w == nil || w[1] != ((0x17<<2)|0x01) {
		t.Errorf("Configure: expected ODR_CONFIG=0x5D, got %v", w)
	}
	if w := lastWriteTo(conn.writes, bmp581RegOSRConfig); w == nil || w[1] != (0x40|(4<<3)|2) {
		t.Errorf("Configure: expected OSR_CONFIG=0x62, got %v", w)
	}

	if err := sensor.SetMode(BMP581ModeStandby); err != nil {
		t.Errorf("SetMode: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegODRConfig); w == nil || w[1] != ((0x17<<2)|0x00) {
		t.Errorf("SetMode(STANDBY): expected ODR_CONFIG=0x5C, got %v", w)
	}

	if err := sensor.SetMode(BMP581ModeContinuous); err != nil {
		t.Errorf("SetMode(Continuous): %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegODRConfig); w == nil || w[1] != ((0x17<<2)|0x03) {
		t.Errorf("SetMode(Continuous): expected ODR_CONFIG=0x5F, got %v", w)
	}

	conn.setRegister(bmp581RegDspConfig, 0x00)
	if err := sensor.SetIIRFilter(BMP581IIRCoeff3, BMP581IIRBypass); err != nil {
		t.Errorf("SetIIRFilter: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegDspConfig); w == nil || (w[1]&0x28) != 0x28 {
		t.Errorf("SetIIRFilter: expected DSP_CONFIG with shdw_sel bits set, got %v", w)
	}
	if w := lastWriteTo(conn.writes, bmp581RegDspIIR); w == nil || w[1] != ((BMP581IIRCoeff3<<3)|BMP581IIRBypass) {
		t.Errorf("SetIIRFilter: expected DSP_IIR=0x10, got %v", w)
	}

	if err := sensor.EnableDRDYInterrupt(true); err != nil {
		t.Errorf("EnableDRDYInterrupt: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegIntSource); w == nil || (w[1]&BMP581IntSourceDRDY) == 0 {
		t.Errorf("EnableDRDYInterrupt: expected DRDY bit set, got %v", w)
	}

	if err := sensor.EnableFIFOInterrupt(true, false); err != nil {
		t.Errorf("EnableFIFOInterrupt: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegIntSource); w == nil || (w[1]&BMP581IntSourceFIFOThs) == 0 {
		t.Errorf("EnableFIFOInterrupt: expected FIFO_THS bit set, got %v", w)
	}

	if err := sensor.EnableOORInterrupt(true); err != nil {
		t.Errorf("EnableOORInterrupt: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegIntSource); w == nil || (w[1]&BMP581IntSourceOORP) == 0 {
		t.Errorf("EnableOORInterrupt: expected OOR bit set, got %v", w)
	}

	if err := sensor.ConfigureInterrupt(1, 1, true, true); err != nil {
		t.Errorf("ConfigureInterrupt: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegIntConfig); w == nil || w[1] != 0x0F {
		t.Errorf("ConfigureInterrupt: expected 0x0F, got %v", w)
	}

	if err := sensor.SetOORThreshold(110000.0, 200.0, 2); err != nil {
		t.Errorf("SetOORThreshold: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegOORThrPLSB); w == nil {
		t.Error("SetOORThreshold: missing LSB write")
	}
	if w := lastWriteTo(conn.writes, bmp581RegOORThrPMSB); w == nil {
		t.Error("SetOORThreshold: missing MSB write")
	}
	if w := lastWriteTo(conn.writes, bmp581RegOORRange); w == nil {
		t.Error("SetOORThreshold: missing RANGE write")
	}
	if w := lastWriteTo(conn.writes, bmp581RegOORConfig); w == nil || (w[1]&0xC0) != (2<<6) {
		t.Errorf("SetOORThreshold: expected count_limit=2 bits, got %v", w)
	}

	if err := sensor.ConfigureFIFO(BMP581FIFOBoth, BMP581FIFOStream, 8); err != nil {
		t.Errorf("ConfigureFIFO: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp581RegFifoSel); w == nil || w[1] != 0x03 {
		t.Errorf("ConfigureFIFO: expected FIFO_SEL=0x03, got %v", w)
	}
	if w := lastWriteTo(conn.writes, bmp581RegFifoConfig); w == nil || w[1] != 0x08 {
		t.Errorf("ConfigureFIFO: expected FIFO_CONFIG=0x08, got %v", w)
	}

	conn.setRegister(bmp581RegFifoCount, 4)
	if n, err := sensor.FIFOCount(); err != nil || n != 4 {
		t.Errorf("FIFOCount() = %d, %v, want 4", n, err)
	}

	conn.setRegister(bmp581RegOSREff, 0xA0)
	if op, ot, err := sensor.EffectiveOSR(); err != nil || op != 4 || ot != 0 {
		t.Errorf("EffectiveOSR() = %d, %d, %v, want 4, 0", op, ot, err)
	}
	if valid, err := sensor.ODRIsValid(); err != nil || !valid {
		t.Errorf("ODRIsValid() = %v, %v, want true", valid, err)
	}
}