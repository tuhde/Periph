package pressure

import (
	"math"
	"testing"
)

// mockConnection, lastWriteTo, newMockConnection are defined in
// bmp180_test.go (shared across this package's unit tests).

func preloadBmp280Calibration(conn *mockConnection) {
	// Spec's Data Conversion "Validation" worked example (datasheet page 23):
	// dig_T1=27504, dig_T2=26435, dig_T3=-1000, dig_P1=36477, dig_P2=-10685,
	// dig_P3=3024, dig_P4=2855, dig_P5=140, dig_P6=-7, dig_P7=15500,
	// dig_P8=-14600, dig_P9=6000. Calibration NVM is little-endian.
	conn.setRegister(bmp280RegCalStart,
		0x70, 0x6B, // dig_T1 = 27504
		0x43, 0x67, // dig_T2 = 26435
		0x18, 0xFC, // dig_T3 = -1000
		0x7D, 0x8E, // dig_P1 = 36477
		0x43, 0xD6, // dig_P2 = -10685
		0xD0, 0x0B, // dig_P3 = 3024
		0x27, 0x0B, // dig_P4 = 2855
		0x8C, 0x00, // dig_P5 = 140
		0xF9, 0xFF, // dig_P6 = -7
		0x8C, 0x3C, // dig_P7 = 15500
		0xF8, 0xC6, // dig_P8 = -14600
		0x70, 0x17, // dig_P9 = 6000
	)
}

func preloadBmp280Data(conn *mockConnection) {
	// UT=519888, UP=415148 (same worked example), one 6-byte burst - unlike
	// BMP180, both ADCs come from a single read.
	conn.setRegister(bmp280RegData,
		0x65, 0x5A, 0xC0, // adc_P = 415148
		0x7E, 0xED, 0x00, // adc_T = 519888
	)
}

func TestBMP280FullAPI(t *testing.T) {
	conn := newMockConnection()
	preloadBmp280Calibration(conn)
	preloadBmp280Data(conn)

	sensor, err := NewBMP280Full(conn, false)
	if err != nil {
		t.Fatalf("NewBMP280Full: %v", err)
	}

	if w := lastWriteTo(conn.writes, bmp280RegCtrlMeas); w == nil || w[1] != 0x24 {
		t.Errorf("init: expected CTRL_MEAS=0x24 (sleep), got %v", w)
	}
	if w := lastWriteTo(conn.writes, bmp280RegConfig); w == nil || w[1] != 0x00 {
		t.Errorf("init: expected CONFIG=0x00, got %v", w)
	}

	// Temperature(): worked example -> T = 25.08 degC.
	if v, err := sensor.Temperature(); err != nil || math.Abs(float64(v)-25.08) > 1e-3 {
		t.Errorf("Temperature() = %v, %v, want 25.08, nil", v, err)
	}
	if w := lastWriteTo(conn.writes, bmp280RegCtrlMeas); w == nil || w[1] != 0x25 {
		t.Errorf("Temperature: expected forced trigger CTRL_MEAS=0x25, got %v", w)
	}

	// Pressure(): worked example -> p = 25767233/256/100 = 1006.5325... hPa.
	if v, err := sensor.Pressure(); err != nil || math.Abs(float64(v)-1006.5325390625) > 1e-1 {
		t.Errorf("Pressure() = %v, %v, want ~1006.5325, nil", v, err)
	}

	// ChipID(): expect 0x58.
	conn.setRegister(bmp280RegID, 0x58)
	if v, err := sensor.ChipID(); err != nil || v != 0x58 {
		t.Errorf("ChipID() = %v, %v, want 0x58, nil", v, err)
	}

	// Status(): raw status byte.
	conn.setRegister(bmp280RegStatus, 0x09)
	if v, err := sensor.Status(); err != nil || v != 0x09 {
		t.Errorf("Status() = %v, %v, want 0x09, nil", v, err)
	}

	// Configure(osrsT=2, osrsP=3, mode=3, filter=2, tSB=4):
	// CONFIG = (4<<5)|(2<<2) = 0x88; CTRL_MEAS = (2<<5)|(3<<2)|3 = 0x4F.
	if err := sensor.Configure(2, 3, 3, 2, 4); err != nil {
		t.Fatalf("Configure: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp280RegConfig); w == nil || w[1] != 0x88 {
		t.Errorf("Configure: expected CONFIG=0x88, got %v", w)
	}
	if w := lastWriteTo(conn.writes, bmp280RegCtrlMeas); w == nil || w[1] != 0x4F {
		t.Errorf("Configure: expected CTRL_MEAS=0x4F, got %v", w)
	}

	// SetOversampling(4, 5): mode stays 3 -> CTRL_MEAS=(4<<5)|(5<<2)|3=0x97.
	if err := sensor.SetOversampling(4, 5); err != nil {
		t.Fatalf("SetOversampling: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp280RegCtrlMeas); w == nil || w[1] != 0x97 {
		t.Errorf("SetOversampling: expected CTRL_MEAS=0x97, got %v", w)
	}

	// SetMode(1): CTRL_MEAS=(4<<5)|(5<<2)|1=0x95.
	if err := sensor.SetMode(1); err != nil {
		t.Fatalf("SetMode: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp280RegCtrlMeas); w == nil || w[1] != 0x95 {
		t.Errorf("SetMode: expected CTRL_MEAS=0x95, got %v", w)
	}

	// SetFilter(3): CONFIG=(4<<5)|(3<<2)=0x8C.
	if err := sensor.SetFilter(3); err != nil {
		t.Fatalf("SetFilter: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp280RegConfig); w == nil || w[1] != 0x8C {
		t.Errorf("SetFilter: expected CONFIG=0x8C, got %v", w)
	}

	// SetStandby(6): CONFIG=(6<<5)|(3<<2)=0xCC.
	if err := sensor.SetStandby(6); err != nil {
		t.Fatalf("SetStandby: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp280RegConfig); w == nil || w[1] != 0xCC {
		t.Errorf("SetStandby: expected CONFIG=0xCC, got %v", w)
	}

	// Altitude(): Pressure() re-reads DATA, still preloaded.
	if v, err := sensor.Altitude(1013.25); err != nil || math.Abs(float64(v)-56.07668235692459) > 0.5 {
		t.Errorf("Altitude() = %v, %v, want ~56.08, nil", v, err)
	}

	// SeaLevelPressure(altitudeM=200)
	if v, err := sensor.SeaLevelPressure(200); err != nil || math.Abs(float64(v)-1030.736388797547) > 0.5 {
		t.Errorf("SeaLevelPressure(200) = %v, %v, want ~1030.74, nil", v, err)
	}

	// Reset(): writes RESET=0xB6, re-reads calibration, re-applies current
	// configuration (tSB=6, filter=3, osrsT=4, osrsP=5, mode=1 from above).
	preloadBmp280Calibration(conn)
	if err := sensor.Reset(); err != nil {
		t.Fatalf("Reset: %v", err)
	}
	if w := lastWriteTo(conn.writes, bmp280RegReset); w == nil || w[1] != bmp280ResetCmd {
		t.Errorf("Reset: expected soft-reset write, got %v", w)
	}
	calReads := 0
	for _, w := range conn.writes {
		if len(w) == 1 && w[0] == bmp280RegCalStart {
			calReads++
		}
	}
	if calReads < 2 {
		t.Errorf("Reset: expected calibration to be re-read (>=2 reads), got %d", calReads)
	}
	if w := lastWriteTo(conn.writes, bmp280RegConfig); w == nil || w[1] != 0xCC {
		t.Errorf("Reset: expected re-applied CONFIG=0xCC, got %v", w)
	}
	if w := lastWriteTo(conn.writes, bmp280RegCtrlMeas); w == nil || w[1] != 0x95 {
		t.Errorf("Reset: expected re-applied CTRL_MEAS=0x95, got %v", w)
	}
}

// TestBMP280SPIMasksWriteAddresses covers the spi=true path: per
// specs/pressure/bmp280.md's SPI Register-address protocol, BMP280's I2C
// register addresses already have bit 7 set (0x88-0xFC), so SPI reads use
// the same reg value unmasked; only writes differ, clearing bit 7 (reg &
// 0x7F).
func TestBMP280SPIMasksWriteAddresses(t *testing.T) {
	conn := newMockConnection()
	preloadBmp280Calibration(conn)

	sensor, err := NewBMP280Full(conn, true)
	if err != nil {
		t.Fatalf("NewBMP280Full(spi): %v", err)
	}

	if w := lastWriteTo(conn.writes, bmp280RegCtrlMeas&0x7F); w == nil || w[1] != 0x24 {
		t.Errorf("spi init: expected masked CTRL_MEAS write (0x74)=0x24, got %v", w)
	}
	for _, w := range conn.writes {
		if len(w) == 2 && w[0] == bmp280RegCtrlMeas {
			t.Errorf("spi init: found unmasked CTRL_MEAS write %v, want bit 7 cleared", w)
		}
	}

	preloadBmp280Data(conn)
	if v, err := sensor.Temperature(); err != nil || math.Abs(float64(v)-25.08) > 1e-3 {
		t.Errorf("spi Temperature() = %v, %v, want 25.08, nil", v, err)
	}
}
