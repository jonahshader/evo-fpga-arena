package evo.comms

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._

class ProtocolTxTest extends AnyFunSuite {

  /** Receive a full message (header + payload) from the TX stream.
   *  Returns (msgType, flags, payload bytes). */
  def recvMessage(dut: ProtocolTx, maxCycles: Int = 200): (Int, Int, List[Int]) = {
    dut.io.stream.ready #= true

    // Collect bytes until tlast
    var bytes = List.empty[Int]
    var done = false
    var cycles = 0

    while (!done && cycles < maxCycles) {
      if (dut.io.stream.valid.toBoolean && dut.io.stream.ready.toBoolean) {
        bytes = bytes :+ (dut.io.stream.payload.data.toInt & 0xFF)
        if (dut.io.stream.payload.last.toBoolean) done = true
      }
      dut.clockDomain.waitRisingEdge()
      cycles += 1
    }

    assert(done, s"Message not completed within $maxCycles cycles (got ${bytes.length} bytes)")
    assert(bytes.length >= 4, s"Message too short: ${bytes.length} bytes")

    val msgType = bytes(0)
    val flags   = bytes(1)
    val length  = bytes(2) | (bytes(3) << 8)
    val payload = bytes.drop(4)
    assert(payload.length == length,
      s"Payload length mismatch: header says $length, got ${payload.length}")

    (msgType, flags, payload)
  }

  def initInputs(dut: ProtocolTx): Unit = {
    dut.io.stream.ready      #= false
    dut.io.stateChange       #= false
    dut.io.neState           #= NeState.IDLE
    dut.io.gaStateSend       #= false
    dut.io.gaState.currentGen      #= 0
    dut.io.gaState.referenceFitness #= 0
    dut.io.gameStateSend     #= false
    dut.io.gameState.p1.pos.x.raw #= 0
    dut.io.gameState.p1.pos.y.raw #= 0
    dut.io.gameState.p1.score     #= 0
    dut.io.gameState.p1.deadTimeout #= 0
    dut.io.gameState.p1.vel.x.raw #= 0
    dut.io.gameState.p1.vel.y.raw #= 0
    dut.io.gameState.p2.pos.x.raw #= 0
    dut.io.gameState.p2.pos.y.raw #= 0
    dut.io.gameState.p2.score     #= 0
    dut.io.gameState.p2.deadTimeout #= 0
    dut.io.gameState.p2.vel.x.raw #= 0
    dut.io.gameState.p2.vel.y.raw #= 0
    dut.io.gameState.coinPos.x    #= 0
    dut.io.gameState.coinPos.y    #= 0
    dut.io.gameState.age          #= 0
    dut.io.bramDumpParam      #= 0
    dut.io.bramDumpParamIndex #= 0
    dut.io.bramDumpParamValid #= false
    dut.io.testGo             #= false
  }

  test("ProtocolTx should send STATE_CHANGE") {
    SimConfig.withWave.compile(new ProtocolTx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Trigger state change to TRAINING
      dut.io.neState    #= NeState.TRAINING
      dut.io.stateChange #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.stateChange #= false

      val (msgType, _, payload) = recvMessage(dut)
      assert(msgType == CommsDefs.TelId.STATE_CHANGE, s"msgType should be STATE_CHANGE, got $msgType")
      assert(payload.length == 1, s"payload should be 1 byte, got ${payload.length}")
      assert(payload(0) == 1, s"state should be TRAINING (1), got ${payload(0)}")
    }
  }

  test("ProtocolTx should send GA_STATUS") {
    SimConfig.compile(new ProtocolTx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Set GA state and trigger
      dut.io.gaState.currentGen      #= 0x1234
      dut.io.gaState.referenceFitness #= 42
      dut.io.gaStateSend #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.gaStateSend #= false

      val (msgType, _, payload) = recvMessage(dut)
      assert(msgType == CommsDefs.TelId.GA_STATUS, s"msgType should be GA_STATUS")
      assert(payload.length == 4)

      val currentGen = payload(0) | (payload(1) << 8)
      val refFitness = (payload(2) | (payload(3) << 8)).toShort
      assert(currentGen == 0x1234, s"currentGen should be 0x1234, got 0x${currentGen.toHexString}")
      assert(refFitness == 42, s"referenceFitness should be 42, got $refFitness")
    }
  }

  test("ProtocolTx should send GAME_STATE") {
    SimConfig.compile(new ProtocolTx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Set game state
      dut.io.gameState.p1.pos.x.raw #= 0x0100 // 16.0 in F4
      dut.io.gameState.p1.pos.y.raw #= 0x0200
      dut.io.gameState.p1.score     #= -5
      dut.io.gameState.p1.deadTimeout #= 10
      dut.io.gameState.p2.pos.x.raw #= 0x0300
      dut.io.gameState.p2.pos.y.raw #= 0x0400
      dut.io.gameState.p2.score     #= 7
      dut.io.gameState.p2.deadTimeout #= 0
      dut.io.gameState.coinPos.x    #= 5
      dut.io.gameState.coinPos.y    #= 11
      dut.io.gameState.age          #= 0xABCD

      dut.io.gameStateSend #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.gameStateSend #= false

      val (msgType, _, payload) = recvMessage(dut)
      assert(msgType == CommsDefs.TelId.GAME_STATE)
      assert(payload.length == 20, s"payload should be 20 bytes, got ${payload.length}")

      // p1.pos.x = 0x0100 LE
      assert(payload(0) == 0x00 && payload(1) == 0x01,
        s"p1.pos.x should be 0x0100 LE, got 0x${payload(1).toHexString}${payload(0).toHexString}")

      // p1.score = -5 (0xFFFB as unsigned)
      val p1Score = (payload(4) | (payload(5) << 8)).toShort
      assert(p1Score == -5, s"p1.score should be -5, got $p1Score")

      // p1.deadTimeout = 10
      assert(payload(6) == 10, s"p1.deadTimeout should be 10, got ${payload(6)}")

      // coinPos
      assert(payload(14) == 5, s"coinPos.x should be 5, got ${payload(14)}")
      assert(payload(15) == 11, s"coinPos.y should be 11, got ${payload(15)}")

      // age = 0xABCD LE
      val age = payload(16) | (payload(17) << 8)
      assert(age == 0xABCD, s"age should be 0xABCD, got 0x${age.toHexString}")
    }
  }

  test("ProtocolTx should send TEST_RESP (zero-payload)") {
    SimConfig.compile(new ProtocolTx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      dut.io.testGo #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.testGo #= false

      val (msgType, _, payload) = recvMessage(dut)
      assert(msgType == CommsDefs.TelId.TEST_RESP, s"msgType should be TEST_RESP")
      assert(payload.isEmpty, "TEST_RESP should have no payload")
    }
  }

  test("ProtocolTx should prioritize gaStateSend over stateChange") {
    SimConfig.compile(new ProtocolTx).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitRisingEdge(5)

      // Trigger both simultaneously
      dut.io.gaState.currentGen      #= 100
      dut.io.gaState.referenceFitness #= 50
      dut.io.gaStateSend #= true

      dut.io.neState    #= NeState.PLAYING
      dut.io.stateChange #= true

      dut.clockDomain.waitRisingEdge()
      dut.io.gaStateSend #= false
      dut.io.stateChange #= false

      // First message should be GA_STATUS (higher priority)
      val (msgType1, _, _) = recvMessage(dut)
      assert(msgType1 == CommsDefs.TelId.GA_STATUS,
        s"First message should be GA_STATUS, got $msgType1")

      // stateChange pulse was consumed; it won't re-fire
      // (This matches VHDL behavior: pulses that arrive while busy are lost)
    }
  }
}
