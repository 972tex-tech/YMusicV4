package kmphttp.json.benchmark

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.BenchmarkMode
import kotlinx.benchmark.BenchmarkTimeUnit
import kotlinx.benchmark.Mode
import kotlinx.benchmark.OutputTimeUnit
import kotlinx.benchmark.Scope
import kotlinx.benchmark.State
import kotlinx.coroutines.runBlocking
import okio.Buffer
import com.squareup.moshi.JsonReader as MoshiJsonReader

/**
 * JVM-only Moshi benchmarks for comparison with kotlinx-serialization and JsonReader.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(BenchmarkTimeUnit.MILLISECONDS)
open class JvmBenchmark : BaseDecodeBenchmark() {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val userDataAdapter = moshi.adapter(UserData::class.java)
    private val mapAdapter = moshi.adapter(Map::class.java)

    // ==================== Small JSON ====================

    @Benchmark
    fun smallJson_moshi(): Map<*, *> = runBlocking {
        val buffer = Buffer()
        smallJsonBuffer.copyTo(buffer)
        mapAdapter.fromJson(MoshiJsonReader.of(buffer))!!.also {
            check(it.size == 3) { "smallJson should have 3 keys, got ${it.size}" }
        }
    }

    // ==================== Large Key/Value ====================

    @Benchmark
    fun largeKeyValue_moshi(): Map<*, *> = runBlocking {
        val buffer = Buffer()
        largeKeyValueJsonBuffer.copyTo(buffer)
        mapAdapter.fromJson(MoshiJsonReader.of(buffer))!!.also {
            check(it.size == 1) { "largeKeyValue should have 1 key, got ${it.size}" }
        }
    }

    // ==================== Large Key/Value with Escapes ====================

    @Benchmark
    fun largeKeyValueEscape_moshi(): Map<*, *> = runBlocking {
        val buffer = Buffer()
        largeKeyValueWithEscapeJsonBuffer.copyTo(buffer)
        mapAdapter.fromJson(MoshiJsonReader.of(buffer))!!.also {
            check(it.size == 1) { "largeKeyValueEscape should have 1 key, got ${it.size}" }
        }
    }

    // ==================== Many Keys ====================

    @Benchmark
    fun manyKeys_moshi(): Map<*, *> = runBlocking {
        val buffer = Buffer()
        manyKeysJsonBuffer.copyTo(buffer)
        mapAdapter.fromJson(MoshiJsonReader.of(buffer))!!.also {
            check(it.size == 200) { "manyKeys should have 200 keys, got ${it.size}" }
        }
    }

    // ==================== Nested Deeply ====================

    @Benchmark
    fun nestedDeeply_moshi(): Map<*, *> = runBlocking {
        val buffer = Buffer()
        nestedDeeplyJsonBuffer.copyTo(buffer)
        mapAdapter.fromJson(MoshiJsonReader.of(buffer))!!.also {
            check(it.size == 1) { "nestedDeeply should have 1 key at root, got ${it.size}" }
        }
    }

    // ==================== Parse to Class (with unknown keys) ====================

    @Benchmark
    fun parseToClass_moshi(): UserData {
        val buffer = Buffer()
        parseToClassJsonBuffer.copyTo(buffer)
        return userDataAdapter.fromJson(MoshiJsonReader.of(buffer))!!.also {
            check(it.id == 123) { "parseToClass id should be 123, got ${it.id}" }
        }
    }
}
