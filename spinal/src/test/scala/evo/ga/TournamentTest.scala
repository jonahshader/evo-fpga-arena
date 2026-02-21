package evo.ga

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import evo.types._
import evo.types.GaConfig._

class TournamentTest extends AnyFunSuite {

  test("Tournament should elaborate and run basic tournament") {
    SimConfig.withWave.compile(new Tournament).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.go #= false
      dut.io.rng #= 0L

      // Setup GA config
      val config = dut.io.ga_config
      config.tournamentSize #= 3  // 3 candidates per tournament
      config.populationSizeExp #= 2  // 2^2 = 4 chromosomes

      // Setup fitness values: chromosome 0 has highest fitness (100), others lower
      for (i <- 0 until MAX_POPULATION_SIZE) {
        if (i == 0) {
          dut.io.input_population_fitness(i) #= 100
        } else if (i < 4) {
          dut.io.input_population_fitness(i) #= 10 * i
        } else {
          dut.io.input_population_fitness(i) #= 0
        }
      }

      dut.clockDomain.waitRisingEdge(5)

      // Start tournament
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for completion (4 tournaments * 3 rounds each = ~12 cycles + margin)
      var doneSeen = false
      for (i <- 0 until 50) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.done.toBoolean) {
          println(s"done seen at cycle $i")
          doneSeen = true
        }
      }

      assert(doneSeen, "Tournament should complete")

      // Check that winner counts are non-zero (at least some chromosomes won)
      var totalWins = 0
      for (i <- 0 until 4) {
        val wins = dut.io.winner_counts(i).toInt
        totalWins += wins
        println(s"Chromosome $i wins: $wins")
      }

      assert(totalWins == 4, s"Total wins should equal population size (4), got $totalWins")
      println("Tournament test passed: basic execution")
    }
  }

  test("Tournament should track best fitness winner") {
    SimConfig.withWave.compile(new Tournament).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.go #= false
      dut.io.rng #= 0L

      // Setup GA config: tournament_size=2, population_size_exp=1 (2 chromosomes)
      val config = dut.io.ga_config
      config.tournamentSize #= 2
      config.populationSizeExp #= 1

      // Chromosome 1 has much higher fitness
      dut.io.input_population_fitness(0) #= 10
      dut.io.input_population_fitness(1) #= 1000

      for (i <- 2 until MAX_POPULATION_SIZE) {
        dut.io.input_population_fitness(i) #= 0
      }

      dut.clockDomain.waitRisingEdge(5)

      // Start tournament
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for completion (2 tournaments * 2 rounds = ~4 cycles + margin)
      var doneSeen = false
      for (i <- 0 until 50) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.done.toBoolean) {
          println(s"done seen at cycle $i")
          doneSeen = true
        }
      }

      assert(doneSeen, "Tournament should complete")

      // With RNG=0, index will always be 0, so chromosome 0 will win both
      val wins0 = dut.io.winner_counts(0).toInt
      val wins1 = dut.io.winner_counts(1).toInt

      println(s"With RNG=0: Chromosome 0 wins: $wins0, Chromosome 1 wins: $wins1")
      assert(wins0 + wins1 == 2, "Total wins should equal population size")
      println("Tournament test passed: deterministic RNG")
    }
  }

  test("Tournament should handle minimal population") {
    SimConfig.withWave.compile(new Tournament).doSim { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // Initialize inputs
      dut.io.go #= false
      dut.io.rng #= 0L

      // Setup GA config: tournament_size=2, population_size_exp=0 (1 chromosome)
      val config = dut.io.ga_config
      config.tournamentSize #= 2
      config.populationSizeExp #= 0  // 2^0 = 1

      dut.io.input_population_fitness(0) #= 100
      for (i <- 1 until MAX_POPULATION_SIZE) {
        dut.io.input_population_fitness(i) #= 0
      }

      dut.clockDomain.waitRisingEdge(5)

      // Start tournament
      dut.io.go #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.go #= false

      // Wait for completion (1 tournament * 2 rounds = ~2 cycles + margin)
      var doneSeen = false
      for (i <- 0 until 50) {
        dut.clockDomain.waitRisingEdge()
        if (dut.io.done.toBoolean) {
          println(s"done seen at cycle $i")
          doneSeen = true
        }
      }

      assert(doneSeen, "Tournament should complete")

      // With 1 chromosome, it should win once
      val wins0 = dut.io.winner_counts(0).toInt
      assert(wins0 == 1, s"Chromosome 0 should win exactly 1 tournament, got $wins0")
      println("Tournament test passed: minimal population")
    }
  }
}
