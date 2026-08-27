package com.quant.terminal.api

data class ChatMessage(
    val role: String, // "user" atau "model"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class MarketPulseResponse(
    val status: String,
    val livePrice: Double = 0.0,
    val spread: Double = 0.0,
    val rsi: Double = 50.0,
    val choppiness: Double = 50.0,
    val mpi: Double = 0.0,
    val arbiterAction: String = "STANDBY"
)
