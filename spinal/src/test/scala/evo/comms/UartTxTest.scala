package evo.comms

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite

class UartTxTest extends AnyFunSuite {

  test("UartTx should transmit a byte") {
    val clksPerBit = 10
    SimConfig.withWave.compile(new UartTx(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.txDv #= false
      dut.io.txByte #= 0
      dut.clockDomain.waitRisingEdge(5)

      // Verify idle state
      assert(!dut.io.txActive.toBoolean, "txActive should be false in idle")
      assert(dut.io.txSerial.toBoolean, "txSerial should be high (idle)")

      // Send byte 0x55
      val testByte = 0x55
      dut.io.txByte #= testByte
      dut.io.txDv #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.txDv #= false

      // Wait through start bit
      dut.clockDomain.waitRisingEdge(clksPerBit)

      assert(dut.io.txActive.toBoolean, "txActive should be true during transmission")

      // Check each data bit at mid-period
      for (bit <- 0 until 8) {
        dut.clockDomain.waitRisingEdge(clksPerBit / 2)
        val expectedBit = ((testByte >> bit) & 1) == 1
        assert(dut.io.txSerial.toBoolean == expectedBit,
          s"Bit $bit should be $expectedBit")
        dut.clockDomain.waitRisingEdge(clksPerBit - clksPerBit / 2)
      }

      // Wait for stop bit and cleanup
      dut.clockDomain.waitRisingEdge(clksPerBit + 2)

      assert(!dut.io.txActive.toBoolean, "txActive should be false after completion")
      assert(dut.io.txDone.toBoolean, "txDone should be true after transmission")
      assert(dut.io.txSerial.toBoolean, "txSerial should return to idle high")
    }
  }

  test("UartTx should handle back-to-back transmissions") {
    val clksPerBit = 10
    SimConfig.withWave.compile(new UartTx(clksPerBit)).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      dut.io.txDv #= false
      dut.io.txByte #= 0
      dut.clockDomain.waitRisingEdge(5)

      // Send first byte
      dut.io.txByte #= 0xA5
      dut.io.txDv #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.txDv #= false

      // Wait for first transmission: start + 8 data + stop + cleanup
      dut.clockDomain.waitRisingEdge(clksPerBit * 10 + 2)

      assert(dut.io.txDone.toBoolean, "txDone should pulse after first byte")

      // Send second byte
      dut.io.txByte #= 0x3C
      dut.io.txDv #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.txDv #= false

      dut.clockDomain.waitRisingEdge(clksPerBit * 10 + 2)

      assert(dut.io.txDone.toBoolean, "txDone should pulse after second byte")
    }
  }
}
