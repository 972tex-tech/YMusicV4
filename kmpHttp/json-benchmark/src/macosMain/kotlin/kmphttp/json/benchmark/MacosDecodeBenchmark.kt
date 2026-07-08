package kmphttp.json.benchmark

import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.Mode
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
open class MacosDecodeBenchmark : BaseDecodeBenchmark()
