package ua.andrii.homepower

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** Мінімальний клієнт Solarman V5 (Wi-Fi логер Deye, TCP 8899). */
object Solarman {

    fun readHolding(host: String, port: Int, serial: Long, start: Int, count: Int, timeoutMs: Int = 4000): IntArray {
        val request = buildFrame(serial, modbusRequest(1, 3, start, count), (System.nanoTime() and 0xFF).toInt())
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply { write(request); flush() }
            val input = DataInputStream(s.getInputStream())
            repeat(5) {
                val frame = readFrame(input)
                val ctrl = (frame[3].toInt() and 0xFF) or ((frame[4].toInt() and 0xFF) shl 8)
                if (ctrl != 0x1510) return@repeat // heartbeat тощо — пропускаємо
                return parseResponse(frame, count)
            }
            throw IOException("Логер не дав відповіді")
        }
    }

    /** Звичайний Modbus TCP (нові логери Deye DYDA_WiBLE 1.7+, порт 502). */
    fun readModbusTcp(host: String, port: Int, unit: Int, start: Int, count: Int, timeoutMs: Int = 4000): IntArray {
        val tid = (System.nanoTime() and 0xFFFF).toInt()
        val req = byteArrayOf(
            (tid shr 8).toByte(), tid.toByte(), 0, 0, 0, 6, unit.toByte(), 3,
            (start shr 8).toByte(), start.toByte(), (count shr 8).toByte(), count.toByte())
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply { write(req); flush() }
            val input = DataInputStream(s.getInputStream())
            val head = ByteArray(7); input.readFully(head)
            val len = ((head[4].toInt() and 0xFF) shl 8) or (head[5].toInt() and 0xFF)
            val pdu = ByteArray(len - 1); input.readFully(pdu)
            if ((pdu[0].toInt() and 0xFF) == 0x83) throw IOException("Modbus помилка ${pdu[1].toInt() and 0xFF}")
            if (pdu[0].toInt() != 3 || (pdu[1].toInt() and 0xFF) < count * 2) throw IOException("Неочікувана відповідь Modbus TCP")
            return IntArray(count) { i -> ((pdu[2 + 2 * i].toInt() and 0xFF) shl 8) or (pdu[3 + 2 * i].toInt() and 0xFF) }
        }
    }

    fun buildFrame(serial: Long, modbus: ByteArray, seq: Int): ByteArray {
        val payload = ByteArray(15 + modbus.size)
        payload[0] = 0x02 // frame type; sensor type + 3 таймери = нулі
        System.arraycopy(modbus, 0, payload, 15, modbus.size)
        val out = ByteArrayOutputStream()
        out.write(0xA5)
        out.write(payload.size and 0xFF); out.write(payload.size shr 8)
        out.write(0x10); out.write(0x45)          // control code 0x4510
        out.write(seq and 0xFF); out.write(0x00)  // sequence
        for (i in 0 until 4) out.write(((serial shr (8 * i)) and 0xFF).toInt())
        out.write(payload)
        val body = out.toByteArray()
        var sum = 0
        for (i in 1 until body.size) sum += body[i].toInt() and 0xFF
        out.write(sum and 0xFF)
        out.write(0x15)
        return out.toByteArray()
    }

    private fun readFrame(input: DataInputStream): ByteArray {
        var b: Int
        do { b = input.readUnsignedByte() } while (b != 0xA5)
        val lo = input.readUnsignedByte(); val hi = input.readUnsignedByte()
        val len = lo or (hi shl 8)
        val rest = ByteArray(8 + len + 2)
        input.readFully(rest)
        return byteArrayOf(0xA5.toByte(), lo.toByte(), hi.toByte()) + rest
    }

    private fun parseResponse(frame: ByteArray, count: Int): IntArray {
        val m = 11 + 14 // заголовок + службова частина payload, далі Modbus RTU
        if (frame.size < m + 5) throw IOException("Коротка відповідь логера")
        val func = frame[m + 1].toInt() and 0xFF
        if (func == 0x83) throw IOException("Modbus помилка ${frame[m + 2].toInt() and 0xFF}")
        val bytes = frame[m + 2].toInt() and 0xFF
        if (func != 3 || bytes < count * 2) throw IOException("Неочікувана відповідь (перевір серійник логера)")
        return IntArray(count) { i ->
            ((frame[m + 3 + 2 * i].toInt() and 0xFF) shl 8) or (frame[m + 4 + 2 * i].toInt() and 0xFF)
        }
    }

    private fun modbusRequest(slave: Int, func: Int, start: Int, count: Int): ByteArray {
        val f = byteArrayOf(slave.toByte(), func.toByte(),
            (start shr 8).toByte(), start.toByte(), (count shr 8).toByte(), count.toByte())
        val crc = crc16(f)
        return f + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc shr 1) xor 0xA001 else crc shr 1 }
        }
        return crc
    }
}
