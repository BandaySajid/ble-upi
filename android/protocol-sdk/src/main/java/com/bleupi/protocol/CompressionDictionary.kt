package com.bleupi.protocol

object CompressionDictionary {
    private val indexToHandle = LinkedHashMap<Int, String>()
    private val handleToIndex = HashMap<String, Int>()

    init {
        val entries = listOf(
            0x01 to "okaxis", 0x02 to "okhdfcbank", 0x03 to "okicici", 0x04 to "oksbi",
            0x05 to "ybl", 0x06 to "paytm", 0x07 to "upi", 0x08 to "ibl",
            0x09 to "axl", 0x0A to "fbl", 0x0B to "yesbank", 0x0C to "idfcbank",
            0x0D to "barodampay", 0x0E to "pnb", 0x0F to "cbin", 0x10 to "canarabank",
            0x11 to "indianbank", 0x12 to "kotak", 0x13 to "unionbank", 0x14 to "iob",
            0x15 to "federal", 0x16 to "dbs", 0x17 to "hsbc", 0x18 to "citi",
            0x19 to "idbi", 0x1A to "indus", 0x1B to "bandhan", 0x1C to "csb",
            0x1D to "dhanlaxmi", 0x1E to "jandhan", 0x1F to "karnataka", 0x20 to "karurvysya",
            0x21 to "rbl", 0x22 to "sib", 0x23 to "uco", 0x24 to "allbank",
            0x25 to "andhra", 0x26 to "boi", 0x27 to "bom", 0x28 to "corporation",
            0x29 to "dena", 0x2A to "ezeepay", 0x2B to "finobank", 0x2C to "hdfc",
            0x2D to "icici", 0x2E to "imobile", 0x2F to "mahb", 0x30 to "obc",
            0x31 to "pingpay", 0x32 to "pockets", 0x33 to "psb", 0x34 to "purz",
            0x35 to "sbi", 0x36 to "sc", 0x37 to "sib", 0x38 to "synd",
            0x39 to "tjsb", 0x3A to "ubi", 0x3B to "united", 0x3C to "vijaya",
            0x3D to "wb", 0x3E to "yesbnk", 0x3F to "airtel", 0x40 to "amazon",
            0x41 to "free", 0x42 to "jiomart", 0x43 to "mobikwik", 0x44 to "phonepe",
            0x45 to "slice", 0x46 to "tapicash", 0x47 to "timecosmos", 0x48 to "zomato",
            0x49 to "abcd", 0x4A to "abfspay", 0x4B to "airtelpay", 0x4C to "albank",
            0x4D to "andb", 0x4E to "apay", 0x4F to "apl", 0x50 to "aufin",
            0x51 to "bdb", 0x52 to "bob", 0x53 to "cboi", 0x54 to "central",
            0x55 to "chqbook", 0x56 to "cityunion", 0x57 to "cnrb", 0x58 to "cosmos",
            0x59 to "cub", 0x5A to "dcb", 0x5B to "dcbbank", 0x5C to "demobank",
            0x5D to "digibank", 0x5E to "equitas", 0x5F to "esaf", 0x60 to "fdrl",
            0x61 to "finokwik", 0x62 to "gkash", 0x63 to "gpay", 0x64 to "idbibank",
            0x65 to "ind", 0x66 to "indie", 0x67 to "jio", 0x68 to "jkb",
            0x69 to "jsfb", 0x6A to "kbl", 0x6B to "kotak811", 0x6C to "kvb",
            0x6D to "lime", 0x6E to "lvb", 0x6F to "mpay", 0x70 to "nmb",
            0x71 to "nsdl", 0x72 to "obc", 0x73 to "okbiz", 0x74 to "okcredit",
            0x75 to "paytmqr", 0x76 to "pockets", 0x77 to "purzpay", 0x78 to "rajgov",
            0x79 to "shivalik", 0x7A to "slicepay", 0x7B to "suryoday", 0x7C to "tmb",
            0x7D to "ubi", 0x7E to "uco", 0x7F to "utkarsh", 0x80 to "yesbankpay"
        )
        for ((idx, handle) in entries) {
            indexToHandle[idx] = handle
            handleToIndex[handle] = idx
        }
    }

    fun getHandle(index: Int): String? = indexToHandle[index]

    fun getIndex(handle: String): Int = handleToIndex[handle] ?: 0

    val rawSuffixCode: Int get() = 0

    val version: Int get() = 1
}
