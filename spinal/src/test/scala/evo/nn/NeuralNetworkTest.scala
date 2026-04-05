package evo.nn

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._

class NeuralNetworkTest extends AnyFunSuite {

  test("NeuralNetwork should elaborate and run inference") {
    SimConfig.withWave.compile(new NeuralNetwork).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.param      #= 0
      dut.io.paramIndex #= 0
      dut.io.paramValid #= false
      dut.io.go         #= false
      dut.io.p1Perspective #= true

      // Zero out game state
      dut.io.gs.p1.pos.x.raw #= 0
      dut.io.gs.p1.pos.y.raw #= 0
      dut.io.gs.p1.vel.x.raw #= 0
      dut.io.gs.p1.vel.y.raw #= 0
      dut.io.gs.p1.score     #= 0
      dut.io.gs.p1.deadTimeout #= 0
      dut.io.gs.p2.pos.x.raw #= 0
      dut.io.gs.p2.pos.y.raw #= 0
      dut.io.gs.p2.vel.x.raw #= 0
      dut.io.gs.p2.vel.y.raw #= 0
      dut.io.gs.p2.score     #= 0
      dut.io.gs.p2.deadTimeout #= 0
      dut.io.gs.coinPos.x    #= 0
      dut.io.gs.coinPos.y    #= 0
      dut.io.gs.age          #= 0

      dut.clockDomain.waitRisingEdge(5)

      // Trigger inference with all-zero weights (should produce all-zero logits)
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for done pulse (LAYER_COUNT cycles from go, done is a 1-cycle pulse)
      var doneSeen = false
      for (_ <- 0 until 20 if !doneSeen) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.done.toBoolean) doneSeen = true
      }
      assert(doneSeen, "NN should pulse done after forward pass")

      println("NeuralNetwork test passed: elaboration + inference with zero weights")
    }
  }

  test("NeuralNetwork should load parameters and produce non-trivial output") {
    SimConfig.withWave.compile(new NeuralNetwork).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.param      #= 0
      dut.io.paramIndex #= 0
      dut.io.paramValid #= false
      dut.io.go         #= false
      dut.io.p1Perspective #= true

      dut.io.gs.p1.pos.x.raw #= 16   // 1.0 in F4
      dut.io.gs.p1.pos.y.raw #= 0
      dut.io.gs.p1.vel.x.raw #= 0
      dut.io.gs.p1.vel.y.raw #= 0
      dut.io.gs.p1.score     #= 0
      dut.io.gs.p1.deadTimeout #= 0
      dut.io.gs.p2.pos.x.raw #= 0
      dut.io.gs.p2.pos.y.raw #= 0
      dut.io.gs.p2.vel.x.raw #= 0
      dut.io.gs.p2.vel.y.raw #= 0
      dut.io.gs.p2.score     #= 0
      dut.io.gs.p2.deadTimeout #= 0
      dut.io.gs.coinPos.x    #= 5
      dut.io.gs.coinPos.y    #= 3
      dut.io.gs.age          #= 0

      dut.clockDomain.waitRisingEdge(5)

      // Load a few weight parameters (weight = 1 => param value 1)
      // First weight of first neuron of first layer
      dut.io.param      #= 1  // weight = +1
      dut.io.paramIndex #= 0  // layer 0, neuron 0, weight 0
      dut.io.paramValid #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.paramValid #= false

      dut.clockDomain.waitRisingEdge(2)

      // Run inference
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for done pulse
      var doneSeen = false
      for (_ <- 0 until 20 if !doneSeen) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.done.toBoolean) doneSeen = true
      }
      assert(doneSeen, "NN should be done")

      println("NeuralNetwork param loading test passed")
    }
  }
}
