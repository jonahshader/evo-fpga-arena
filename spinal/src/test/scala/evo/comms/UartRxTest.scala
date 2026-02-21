package evo.comms

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class UartRxTest extends AnyFunSuite {

  /** Helper: send one byte over UART (start bit, 8 data bits LSB-first, stop bit). */
  private def sendByte(dut: UartRx, byte: Int, clksPerBit: Int): Unit = {
    // Start bit (low)
    dut.io.rxSerial #= false
    dut.clockDomain.waitRisingEdge(clksPerBit)

    // 8 data bits, LSB first
    for (i <- 0 until 8) {
      dut.io.rxSerial #= ((byte >> i) & 1) == 1
      dut.clockDomain.waitRisingEdge(clksPerBit)
    }

    // Stop bit (high)
    dut.io.rxSerial #= true
    dut.clockDomain.waitRisingEdge(clksPerBit)
  }

  test("UartRx should receive a byte") {
    val clksPerBit = 16
    SimConfig.withWave.compile(new UartRx(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.rxSerial #= true
      dut.clockDomain.waitRisingEdge(10)

      val testByte = 0xA5
      sendByte(dut, testByte, clksPerBit)

      // Wait for cleanup
      dut.clockDomain.waitRisingEdge(10)

      val received = dut.io.rxByte.toInt
      assert(received == testByte, s"Expected 0x${testByte.toHexString}, got 0x${received.toHexString}")
    }
  }

  test("UartRx should receive multiple bytes in sequence") {
    val clksPerBit = 16
    SimConfig.withWave.compile(new UartRx(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.rxSerial #= true
      dut.clockDomain.waitRisingEdge(10)

      for (testByte <- Seq(0x55, 0xAA, 0x00, 0xFF, 0x12, 0x34)) {
        sendByte(dut, testByte, clksPerBit)
        dut.clockDomain.waitRisingEdge(10)

        val received = dut.io.rxByte.toInt
        assert(received == testByte,
          s"Expected 0x${testByte.toHexString}, got 0x${received.toHexString}")
      }
    }
  }

  test("UartRx should generate data valid pulse") {
    val clksPerBit = 16
    SimConfig.withWave.compile(new UartRx(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.rxSerial #= true
      dut.clockDomain.waitRisingEdge(10)

      // Send byte inline so we can check rxDv each cycle
      val testByte = 0x42
      var sawDvPulse = false

      // Start bit
      dut.io.rxSerial #= false
      for (_ <- 0 until clksPerBit) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.rxDv.toBoolean) sawDvPulse = true
      }

      // 8 data bits
      for (i <- 0 until 8) {
        dut.io.rxSerial #= ((testByte >> i) & 1) == 1
        for (_ <- 0 until clksPerBit) {
          dut.clockDomain.waitRisingEdge()
          if (dut.io.rxDv.toBoolean) sawDvPulse = true
        }
      }

      // Stop bit + extra wait
      dut.io.rxSerial #= true
      for (_ <- 0 until clksPerBit + 10) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.rxDv.toBoolean) sawDvPulse = true
      }

      assert(sawDvPulse, "Should have seen rxDv pulse")
      assert(dut.io.rxByte.toInt == testByte)
    }
  }
}
