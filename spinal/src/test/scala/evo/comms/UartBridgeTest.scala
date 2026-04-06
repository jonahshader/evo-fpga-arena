package evo.comms

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class UartBridgeTest extends AnyFunSuite {

  val clksPerBit = 10

  /** Send a byte as serial bits into rxSerial (start + 8 data + stop). */
  def sendSerialByte(dut: UartBridge, byte: Int): Unit = {
    // Start bit (low)
    dut.io.rxSerial #= false
    dut.clockDomain.waitRisingEdge(clksPerBit)

    // 8 data bits, LSB first
    for (bit <- 0 until 8) {
      dut.io.rxSerial #= ((byte >> bit) & 1) == 1
      dut.clockDomain.waitRisingEdge(clksPerBit)
    }

    // Stop bit (high)
    dut.io.rxSerial #= true
    dut.clockDomain.waitRisingEdge(clksPerBit)
  }

  def initInputs(dut: UartBridge): Unit = {
    dut.io.rxSerial #= true
    dut.io.txStream.valid #= false
    dut.io.txStream.payload.data #= 0
    dut.io.txStream.payload.last #= false
    dut.io.rxStream.ready #= false // hold off until we're ready to capture
  }

  test("UartBridge RX: serial bytes appear on AXI-Stream output") {
    SimConfig.withWave.compile(new UartBridge(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Send 0xA5 over serial (ready=false so the data won't be consumed)
      sendSerialByte(dut, 0xA5)

      // Wait for UartRx to finish processing
      dut.clockDomain.waitRisingEdge(10)

      // rxStream.valid should be high (data is buffered, ready is low)
      var found = false
      for (_ <- 0 until 50 if !found) {
        if (dut.io.rxStream.valid.toBoolean) {
          found = true
        }
        dut.clockDomain.waitRisingEdge()
      }
      assert(found, "rxStream should have valid data after UART byte received")
      assert((dut.io.rxStream.payload.data.toInt & 0xFF) == 0xA5,
        s"Expected 0xA5, got 0x${(dut.io.rxStream.payload.data.toInt & 0xFF).toHexString}")

      // Accept the byte
      dut.io.rxStream.ready #= true
      dut.clockDomain.waitRisingEdge(2)

      // Valid should clear after fire
      assert(!dut.io.rxStream.valid.toBoolean, "rxStream should clear after accepted")
    }
  }

  test("UartBridge TX: AXI-Stream bytes produce serial output") {
    SimConfig.withWave.compile(new UartBridge(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Drive 0x55 into TX stream
      dut.io.txStream.payload.data #= 0x55
      dut.io.txStream.payload.last #= false
      dut.io.txStream.valid #= true

      // Wait for the stream to fire
      while (!(dut.io.txStream.ready.toBoolean && dut.io.txStream.valid.toBoolean)) {
        dut.clockDomain.waitRisingEdge()
      }
      dut.clockDomain.waitRisingEdge()
      dut.io.txStream.valid #= false

      // Wait for start bit to appear
      var foundStart = false
      for (_ <- 0 until 10 if !foundStart) {
        if (!dut.io.txSerial.toBoolean) {
          foundStart = true
        }
        dut.clockDomain.waitRisingEdge()
      }
      assert(foundStart, "Should see start bit on txSerial")

      // Skip to middle of start bit then to first data bit center
      dut.clockDomain.waitRisingEdge(clksPerBit / 2)

      // Sample each data bit at mid-period
      var receivedByte = 0
      for (bit <- 0 until 8) {
        dut.clockDomain.waitRisingEdge(clksPerBit)
        if (dut.io.txSerial.toBoolean) {
          receivedByte |= (1 << bit)
        }
      }
      assert(receivedByte == 0x55,
        s"Expected 0x55, got 0x${receivedByte.toHexString}")
    }
  }

  test("UartBridge TX: backpressure holds while UART is busy") {
    SimConfig.withWave.compile(new UartBridge(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Send first byte
      dut.io.txStream.payload.data #= 0xAA
      dut.io.txStream.valid #= true
      dut.io.txStream.payload.last #= false

      // Wait for fire
      while (!(dut.io.txStream.ready.toBoolean && dut.io.txStream.valid.toBoolean)) {
        dut.clockDomain.waitRisingEdge()
      }
      dut.clockDomain.waitRisingEdge()

      // Deassert valid so second byte doesn't auto-fire
      dut.io.txStream.valid #= false

      // Ready should be low (UART is busy)
      assert(!dut.io.txStream.ready.toBoolean,
        "TX stream should not be ready while UART is transmitting")

      // Wait for entire UART transmission
      dut.clockDomain.waitRisingEdge(clksPerBit * 11 + 10)

      // Now ready should be high again
      var ready = false
      for (_ <- 0 until 20 if !ready) {
        if (dut.io.txStream.ready.toBoolean) ready = true
        else dut.clockDomain.waitRisingEdge()
      }
      assert(ready, "TX stream should become ready after UART finishes")
    }
  }
}
