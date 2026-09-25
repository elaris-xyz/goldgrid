package com.vamahan.dailydraw.solana

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** The few JSON-RPC calls the app needs, with the account data already decoded. */
class SolanaRpc(private val url: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun call(method: String, params: JsonArray): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString().toRequestBody("application/json".toMediaType())
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                    val text = response.body?.string() ?: throw IOException("empty response")
                    val obj = json.parseToJsonElement(text).jsonObject
                    obj["error"]?.let { throw RpcException(it.toString()) }
                    return@withContext obj["result"] ?: JsonNull
                }
            } catch (e: RpcException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                delay(400L * (attempt + 1))
            }
        }
        throw lastError ?: IOException("rpc failed")
    }

    private fun decode(value: JsonElement): ByteArray? {
        if (value is JsonNull) return null
        val data = value.jsonObject["data"]?.jsonArray?.get(0)?.jsonPrimitive?.content ?: return null
        return Base64.decode(data, Base64.DEFAULT)
    }

    private val base64Config = buildJsonObject { put("encoding", "base64"); put("commitment", "confirmed") }

    suspend fun accountData(address: String): ByteArray? {
        val result = call("getAccountInfo", buildJsonArray { add(address); add(base64Config) })
        return decode(result.jsonObject["value"] ?: JsonNull)
    }

    suspend fun multipleAccounts(addresses: List<String>): List<ByteArray?> {
        if (addresses.isEmpty()) return emptyList()
        val result = call("getMultipleAccounts", buildJsonArray {
            add(buildJsonArray { addresses.forEach { add(it) } })
            add(base64Config)
        })
        return result.jsonObject["value"]!!.jsonArray.map { decode(it) }
    }

    /** Accounts of a program whose data at `offset` equals `bytesBase58`. */
    suspend fun programAccounts(program: String, offset: Int, bytesBase58: String): List<Pair<String, ByteArray>> {
        val result = call("getProgramAccounts", buildJsonArray {
            add(program)
            add(buildJsonObject {
                put("encoding", "base64")
                put("commitment", "confirmed")
                put("filters", buildJsonArray {
                    add(buildJsonObject {
                        put("memcmp", buildJsonObject { put("offset", offset); put("bytes", bytesBase58) })
                    })
                })
            })
        })
        return result.jsonArray.mapNotNull { entry ->
            val o = entry.jsonObject
            val data = decode(o["account"] ?: return@mapNotNull null) ?: return@mapNotNull null
            o["pubkey"]!!.jsonPrimitive.content to data
        }
    }

    suspend fun latestBlockhash(): String {
        val result = call("getLatestBlockhash", buildJsonArray { add(buildJsonObject { put("commitment", "confirmed") }) })
        return result.jsonObject["value"]!!.jsonObject["blockhash"]!!.jsonPrimitive.content
    }

    /**
     * The chain's unix time from the Clock sysvar. Round timing must come from here:
     * the program reads this clock, and a device clock can be minutes off (the dev
     * machine this was built on ran 215 s fast).
     */
    suspend fun chainTime(): Long {
        val data = accountData(CLOCK_SYSVAR) ?: throw IOException("clock sysvar missing")
        return ByteBuffer.wrap(data, 32, 8).order(ByteOrder.LITTLE_ENDIAN).long
    }

    suspend fun tokenBalance(tokenAccount: String): Long? {
        val result = try {
            call("getTokenAccountBalance", buildJsonArray { add(tokenAccount); add(buildJsonObject { put("commitment", "confirmed") }) })
        } catch (e: RpcException) {
            return null // account does not exist yet
        }
        return result.jsonObject["value"]?.jsonObject?.get("amount")?.jsonPrimitive?.content?.toLongOrNull()
    }

    suspend fun lamports(address: String): Long {
        val result = call("getBalance", buildJsonArray { add(address); add(buildJsonObject { put("commitment", "confirmed") }) })
        return result.jsonObject["value"]!!.jsonPrimitive.long
    }

    /** True once the transaction is confirmed; throws if it landed with an error. */
    suspend fun isConfirmed(signature: String): Boolean {
        val result = call("getSignatureStatuses", buildJsonArray { add(buildJsonArray { add(signature) }) })
        val status = result.jsonObject["value"]!!.jsonArray[0]
        if (status is JsonNull) return false
        val obj = status as JsonObject
        val err = obj["err"]
        if (err != null && err !is JsonNull) throw RpcException("transaction failed: $err")
        return obj["confirmationStatus"]?.jsonPrimitive?.content in setOf("confirmed", "finalized")
    }

    companion object {
        const val CLOCK_SYSVAR = "SysvarC1ock11111111111111111111111111111111"
    }
}

class RpcException(message: String) : IOException(message)
